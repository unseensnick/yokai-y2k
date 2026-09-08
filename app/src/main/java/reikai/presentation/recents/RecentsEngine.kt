package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Duration.Companion.seconds

/**
 * One rendered surface's recent activity, assembled over its per-type [RecentsProvider]s. Everything
 * describing the list (the chip, the ordered rows, loading, emptiness, the filter reason, the
 * last-updated line) is owned here and stored once; storing it per content type is what let the two
 * replaced screens disagree with themselves. Anything describing one type stays on a provider.
 * [lanes] is the surface's, not the chip's: every provider's lanes always run, and the chip only
 * selects whose rows assemble. Record: content-layer-recents-surface.md.
 */
@AssistedInject
class RecentsEngine(
    // Assisted: each provider wraps a ViewModel the screen has already resolved, so the list can only
    // be built at the call site.
    @Assisted private val providers: List<RecentsProvider>,
    @Assisted val surface: RecentsSurface,
    /** Public so the screen can offer the choice; the engine still owns which one is on. */
    @Assisted val modes: Set<RecentsMode>,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val updatesPreferences: UpdatesPreferences,
    private val libraryPreferences: LibraryPreferences,
) : ViewModel() {

    /**
     * Only the first [androidx.lifecycle.viewmodel.compose.viewModel] call for a given store builds the
     * engine; later calls return that instance and ignore this factory. That is what keeps exactly one
     * adapter pair alive, so do not "fix" it into something that runs per composition.
     */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(
            providers: List<RecentsProvider>,
            surface: RecentsSurface,
            modes: Set<RecentsMode>,
        ): RecentsEngine
    }

    companion object {
        /**
         * Anything derived over a provider holds that provider's feed subscription open, so it stops
         * once nothing renders it. The window matches the history models the read lane runs through.
         * A value a verb reads synchronously stays eager instead: unsubscribed, `value` is the seed.
         */
        private val OVER_PROVIDERS = SharingStarted.WhileSubscribed(5.seconds)
    }

    init {
        // Both are combined over, and `combine` of nothing never emits, so an empty one would leave the
        // surface loading forever rather than failing.
        require(providers.isNotEmpty()) { "A recents engine needs at least one provider" }
        require(modes.isNotEmpty()) { "A recents engine needs at least one mode to render" }
    }

    /** Every lane any of this surface's modes draws from, which is what its providers open. */
    private val lanes: Set<RecentsLaneKind> = modes.flatMapTo(mutableSetOf()) { it.lanes }

    /**
     * What is on screen now, restored from the last visit. One value for the surface, so switching
     * mode cannot leave a stale selection or search behind. A stored mode this surface does not render
     * is ignored rather than obeyed, which is what makes the preference safe to share across surfaces
     * and what absorbs a mode that no longer exists.
     */
    private val mutableMode = MutableStateFlow(
        sourcePreferences.recentsMode.get().takeIf { it in modes } ?: modes.first(),
    )
    val mode: StateFlow<RecentsMode> = mutableMode.asStateFlow()

    fun setMode(mode: RecentsMode) {
        require(mode in modes) { "$surface does not render $mode" }
        if (mutableMode.value == mode) return
        clearSelection()
        // Only the combined modes name a target, so a memo carried into History would let a row there
        // act on a chapter it does not name.
        mutableTargets.value = emptyMap()
        mutableMode.value = mode
        sourcePreferences.recentsMode.set(mode)
    }

    /**
     * The Manga / Novels chip, one per rendered surface. It decides which providers' rows assemble,
     * which is the engine's call and not one content type's. Eager, unlike the flows derived over it,
     * because every verb reads this synchronously to pick the providers it dispatches to.
     */
    val contentType: StateFlow<ContentType> by lazy {
        chipPreference.changes().stateIn(viewModelScope, SharingStarted.Eagerly, chipPreference.get())
    }

    /**
     * Flipping the chip drops the selection, matching the library engine. Rows the chip now hides
     * would otherwise keep counting in the toolbar until the next assembly prunes them, promising
     * more than the verbs would touch.
     */
    fun setContentType(type: ContentType) {
        if (contentType.value == type) return
        clearSelection()
        chipPreference.set(type)
    }

    private val chipPreference: Preference<ContentType>
        get() = when (surface) {
            RecentsSurface.UPDATES -> sourcePreferences.updatesContentType
            RecentsSurface.HISTORY -> sourcePreferences.historyContentType
            RecentsSurface.RECENTS -> sourcePreferences.recentsContentType
        }

    /**
     * The one ordered stream every render policy draws from, tagged with the chip that produced it
     * because the flow lags a chip flip by one emission and a policy must not render the wrong one.
     * Collapsing is not done here: its scope is a policy's decision (see [RecentsAssembly]).
     * `by lazy` like every scope-touching member, so the engine can be constructed in a unit test.
     */
    val assembled: StateFlow<RecentsAssembled?> by lazy {
        combine(
            contentType,
            // The memo is emptied here rather than on the assembly, which folds the search query in as
            // well: a keystroke must not throw away resolutions, while a chapter write must, because
            // it re-runs the lane queries and the target it resolved to may now be read.
            combine(providers.map(::collectedLanes)) { it.toList() }
                .onEach { mutableTargets.value = emptyMap() },
            // Every provider's, not just the active ones': the keys are EntryIds and group ids are
            // unique across both content types, so one map serves whatever the chip ends up showing.
            combine(providers.map { it.membership }) { maps -> maps.fold(emptyMap<EntryId, Long>()) { a, b -> a + b } },
            query,
        ) { chip, lanesPerProvider, membership, query ->
            val active = activeIndices(chip).flatMap { lanesPerProvider[it] }
            val rows = orderRecents(active.flatMap { it.items }).filter { matchesQuery(it, query) }
            RecentsAssembled(
                chip = chip,
                items = rows,
                membership = membership,
                // Over the active providers only: an unloaded novel lane used to hold the manga chip's
                // spinner, since one flag was read for a list the other type was not in.
                loading = active.any { !it.loaded },
            )
        }
            // The transform sorts the whole feed; keep it off the main thread.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, OVER_PROVIDERS, null)
    }

    /**
     * What the surface actually draws: the mode's policy over the assembly, grouped and filtered.
     * It lives here rather than in the renderer because the selection is pruned to it, and a rule
     * about what is on screen cannot be enforced from a place that only learns it afterwards.
     * Null while the assembly is a chip behind, which the screen draws as loading, exactly as it did
     * when it applied that guard itself.
     */
    val rendered: StateFlow<RecentsRendered?> by lazy {
        combine(
            combine(assembled, contentType) { assembled, chip -> assembled?.takeIf { it.chip == chip } },
            mode,
            groupBySeries,
            expandedGroups,
            rowGate,
        ) { assembled, mode, grouped, expanded, gate ->
            assembled?.let {
                RecentsRendered(
                    rows = renderRows(mode, it, grouped, expanded) { item -> showsRow(item, gate, mode) },
                    loading = it.loading,
                    membership = it.membership,
                )
            }
        }
            // Beside the transform rather than inside it, because it is an effect on the selection.
            // A null carries no statement about what is on screen, so it prunes nothing: pruning
            // there would empty a selection every time the chip flip lagged by one emission.
            .onEach { rendered -> rendered?.let { pruneSelection(it.rows.orderedChapterRefs()) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, OVER_PROVIDERS, null)
    }

    /** When the library behind the current chip last updated, the newer of the two under All. */
    val lastUpdated: StateFlow<Long> by lazy {
        combine(
            contentType,
            combine(providers.map { it.lastUpdated }) { it.toList() },
        ) { chip, perProvider ->
            activeIndices(chip).maxOfOrNull { perProvider[it] } ?: 0L
        }.stateIn(viewModelScope, OVER_PROVIDERS, 0L)
    }

    /**
     * Whether a library behind the current chip is updating, so a refreshing indicator ends when the
     * job does. The two replaced screens faked this with a fixed one-second delay, which said nothing
     * about whether anything was actually running.
     */
    val refreshing: StateFlow<Boolean> by lazy {
        combine(
            contentType,
            combine(providers.map { it.updating }) { it.toList() },
        ) { chip, perProvider ->
            activeIndices(chip).any { perProvider[it] }
        }.stateIn(viewModelScope, OVER_PROVIDERS, false)
    }

    /**
     * Whether a filter is narrowing this surface, so an empty feed can say why. Asked of the mode on
     * screen rather than of every mode the surface renders: a surface drawing several of them always
     * has the updated lane somewhere, which would report a history feed as filtered by a filter that
     * cannot reach it. The chip is asked for the same reason, since scanlator exclusion reaches
     * nothing on a novel feed.
     */
    val filterActive: StateFlow<Boolean> by lazy {
        combine(
            sourcePreferences.recentsCategoryFilterFlow(surface).map { it.active },
            rawChapterFilters.map { it.isActive },
            updatesPreferences.filterExcludedScanlators.changes(),
            contentType.map { chip -> activeIndices(chip).any { providers[it].contentType == ContentType.MANGA } },
            mode,
        ) { byCategory, byChapterState, byScanlator, chipShowsManga, mode ->
            recentsFilterActive(byCategory, byChapterState, byScanlator, chipShowsManga, mode)
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

    /**
     * The chapter-state filters the mode on screen offers, which is what a row is judged against. A
     * mode that draws no control for them filters by none: the four preferences are shared with the
     * Updates mode, and obeying them anyway would narrow a feed with nothing on screen saying so.
     */
    val chapterFilters: StateFlow<RecentsChapterFilters> by lazy {
        combine(mode, rawChapterFilters) { mode, filters ->
            if (mode.can(RecentsCapability.CHAPTER_FILTER)) filters else RecentsChapterFilters.NONE
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, RecentsChapterFilters.NONE)
    }

    /**
     * The swipe choices, read from the same two preferences the details screens use, so one setting
     * governs a chapter row wherever it is drawn. The property names are crossed on purpose: the
     * preference called `swipeToEndAction` is the action the start side runs.
     */
    val swipeActions: StateFlow<RecentsSwipeActions> by lazy {
        combine(
            libraryPreferences.swipeToEndAction.changes(),
            libraryPreferences.swipeToStartAction.changes(),
        ) { start, end -> RecentsSwipeActions(start = start, end = end) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                RecentsSwipeActions(
                    start = libraryPreferences.swipeToEndAction.get(),
                    end = libraryPreferences.swipeToStartAction.get(),
                ),
            )
    }

    /** The Updates mode's own display toggle. The combined modes ignore it: they have no ungrouped
     *  reading, so nothing there is the user's to switch. */
    val groupBySeries: StateFlow<Boolean> by lazy {
        sourcePreferences.updatesGroupBySeries.changes()
            .stateIn(viewModelScope, SharingStarted.Eagerly, sourcePreferences.updatesGroupBySeries.get())
    }

    // Which grouped rows are open. Deliberately not persisted and not a preference: it describes this
    // visit to the screen, and the screen it replaced kept it in a remember that died with the process.
    private val mutableExpandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = mutableExpandedGroups.asStateFlow()

    fun toggleGroupExpanded(key: String) {
        mutableExpandedGroups.update { if (key in it) it - key else it + key }
    }

    // One query for the surface, matched here rather than in SQL. Every model already writes the user's
    // custom title into the row it emits, so matching the displayed title is what makes a renamed entry
    // findable by the name on screen; the SQL search could only ever see the source title. It also gives
    // the updated and added lanes a search they have never had, at the cost of one pass over a feed that
    // is bounded per lane.
    private val mutableQuery = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> = mutableQuery.asStateFlow()

    fun search(query: String?) {
        mutableQuery.value = query
    }

    private val providersByType = providers.associateBy { it.contentType }

    private fun matchesQuery(item: RecentsItem, query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val title = providersByType[item.entryId.contentType]?.title(item) ?: return false
        return title.contains(query, ignoreCase = true)
    }

    // Selection. A chapter, not an entry: two chapters of one series are independently selectable, and
    // the raw chapter ids of the two content types overlap.

    private val mutableSelection = MutableStateFlow<Set<ChapterRef>>(emptySet())
    val selection: StateFlow<Set<ChapterRef>> = mutableSelection.asStateFlow()

    /** Selection plus its range anchor. `mutableSelection` mirrors the set for the screen to collect. */
    private var selectionState = SelectionState<ChapterRef>()

    private fun apply(next: SelectionState<ChapterRef>) {
        selectionState = next
        mutableSelection.value = next.selection
    }

    fun clearSelection() = apply(EntrySelection.clear())

    fun toggleSelection(chapter: ChapterRef) = apply(EntrySelection.toggle(selectionState, chapter))

    /**
     * [ordered] is the rendered order, which only the caller knows: it interleaves both content types,
     * and grouping and collapsing change it again. The replaced screen ranged over one model's own
     * list instead, so a sweep under the All chip skipped every row of the other type.
     */
    fun toggleRangeSelection(chapter: ChapterRef, ordered: List<ChapterRef>) =
        apply(EntrySelection.rangeOrToggle(selectionState, chapter, ordered))

    /** A collapsed group is one press over a block of rows, so it sweeps to the block's far edge. */
    fun toggleGroupSelection(group: List<ChapterRef>, ordered: List<ChapterRef>) =
        apply(EntrySelection.rangeOrToggleBlock(selectionState, group, ordered))

    fun selectAll(ordered: List<ChapterRef>) = apply(EntrySelection.selectAll(selectionState, ordered))

    fun invertSelection(ordered: List<ChapterRef>) = apply(EntrySelection.invert(selectionState, ordered))

    /**
     * Drop selected chapters the surface no longer draws, so the toolbar count cannot promise more
     * than the verbs will touch. Against the drawn rows rather than the assembled ones: a mode caps
     * its sections and a filter hides rows, and both used to leave a selection acting on what nobody
     * could see. What navigation hides is not pruned, because a collapsed group's members are still
     * drawn as far as this list is concerned.
     */
    private fun pruneSelection(present: List<ChapterRef>) {
        mutableSelection.update { selection ->
            if (selection.isEmpty()) return@update selection
            val drawn = present.toHashSet()
            val pruned = selection.filterTo(HashSet()) { it in drawn }
            if (pruned.size == selection.size) selection else pruned
        }
    }

    // Dialogs: one slot, so a prompt about a mixed selection is asked once.

    private val mutableDialog = MutableStateFlow<RecentsDialog?>(null)
    val dialog: StateFlow<RecentsDialog?> = mutableDialog.asStateFlow()

    fun openDialog(dialog: RecentsDialog) {
        mutableDialog.value = dialog
    }

    fun dismissDialog() {
        mutableDialog.value = null
    }

    // The verbs. Each is handed to every provider in view, which narrows it to its own rows, so one
    // call covers a selection spanning both content types. Each takes the chapters to act on rather
    // than reading the selection, because a continue-reading row acts on the chapter it names and only
    // a suspend pass can resolve that; see [actingChapters], which is where the mapping happens.

    fun markReadSelection(chapters: Set<ChapterRef>, read: Boolean) =
        dispatchAndClear { it.markRead(chapters, read) }

    fun setBookmarkSelection(chapters: Set<ChapterRef>, bookmarked: Boolean) =
        dispatchAndClear { it.setBookmark(chapters, bookmarked) }

    fun downloadSelection(chapters: Set<ChapterRef>) =
        dispatchAndClear { it.download(chapters, ChapterDownloadAction.START) }

    /**
     * One row's own download control, which does not touch the selection: it is not a bulk action and
     * the indicator is only reachable while nothing is selected.
     */
    fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction) =
        dispatch { it.download(chapters, action) }

    /**
     * One row's own read and bookmark toggles, for a swipe. Separate from the selection verbs above
     * because a swipe acts on the row under the finger and must leave a running selection standing.
     */
    fun markRead(chapters: Set<ChapterRef>, read: Boolean) = dispatch { it.markRead(chapters, read) }

    fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) =
        dispatch { it.setBookmark(chapters, bookmarked) }

    fun deleteDownloads(chapters: Set<ChapterRef>) = dispatchAndClear { it.deleteDownloads(chapters) }

    /** Drops every read record of these entries, which is the row action's "all" answer. */
    fun removeFromHistory(entries: Set<EntryId>) {
        providers.forEach { it.removeFromHistory(entries) }
    }

    /** Drops the one record a row stands for, which is the same action's other answer. */
    fun removeHistoryRecord(item: RecentsItem) {
        providersByType[item.entryId.contentType]?.removeHistoryRecord(item)
    }

    // The add flow, owned here rather than by either content type's model, so one shell renders one
    // dialog channel. Each verb below is the UI entry point; the suspend half beside it is the
    // operation, which is also what the tests drive, since nothing can await a launched coroutine.

    /** Adds [entry], asking about a possible duplicate or a category first when the decision needs it. */
    fun addToLibrary(entry: EntryId) {
        viewModelScope.launchIO { startAdd(entry) }
    }

    /** Adds anyway, from the duplicate prompt's confirm. */
    fun addAnyway(entry: EntryId) {
        dismissDialog()
        viewModelScope.launchIO { runAdd(entry) }
    }

    /** Adds [entry] and merges it into the group of the duplicates the user picked in the prompt. */
    fun addToGroup(entry: EntryId, duplicates: List<EntryId>) {
        dismissDialog()
        viewModelScope.launchIO { groupAdd(entry, duplicates) }
    }

    /** The category picker's confirm, which owes both writes the add deferred. */
    fun applyAddCategories(entry: EntryId, categoryIds: List<Long>) {
        dismissDialog()
        viewModelScope.launchIO { fileAddCategories(entry, categoryIds) }
    }

    /** Migrates a duplicate already in the library onto the entry being added, from the prompt. */
    fun migrateOntoEntry(entry: EntryId, duplicate: EntryId) {
        openDialog(RecentsDialog.Migrate(current = duplicate, target = entry))
    }

    /**
     * An entry already in the library is left alone rather than added again: the provider would
     * otherwise refile it, and on the manga side toggle the favorite back off.
     */
    internal suspend fun startAdd(entry: EntryId) {
        val provider = providersByType[entry.contentType] ?: return
        when (val decision = provider.addDecision(entry)) {
            null, AddDecision.Remove -> Unit
            is AddDecision.ConfirmDuplicate -> openDialog(RecentsDialog.Duplicate(entry, decision.duplicates))
            AddDecision.Add -> runAdd(entry)
        }
    }

    internal suspend fun runAdd(entry: EntryId) {
        val provider = providersByType[entry.contentType] ?: return
        promptForCategories(entry, provider.addToLibrary(entry))
    }

    internal suspend fun groupAdd(entry: EntryId, duplicates: List<EntryId>) {
        val provider = providersByType[entry.contentType] ?: return
        promptForCategories(entry, provider.addToGroup(entry, duplicates))
    }

    internal suspend fun fileAddCategories(entry: EntryId, categoryIds: List<Long>) {
        providersByType[entry.contentType]?.applyAddCategories(entry, categoryIds)
    }

    private fun promptForCategories(entry: EntryId, result: AddFavoriteResult) {
        if (result is AddFavoriteResult.NeedsCategoryChoice) {
            openDialog(RecentsDialog.ChangeCategory(entry, result.initialSelection))
        }
    }

    /**
     * Clears the history of every content type on screen, which is why it is one confirmation, and
     * answers whether anything was actually cleared. Mapped before it is reduced, like [refresh], so
     * one type failing cannot skip the other's wipe.
     */
    suspend fun clearHistory(): Boolean = activeProviders().map { it.clearHistory() }.any { it }

    /**
     * Updates every library on screen, answering whether anything actually started. Mapped before it is
     * reduced, so one type already running cannot short-circuit the other type's start.
     */
    fun refresh(): Boolean = activeProviders().map { it.refresh() }.any { it }

    /** The details screen for a row, resolved by the provider that owns the entry. */
    suspend fun detailsScreen(entry: EntryId): Screen? =
        providersByType[entry.contentType]?.detailsScreen(entry)

    /** How a tap on [item] opens its chapter, or null when there is nothing left to open. */
    suspend fun open(item: RecentsItem): RecentsOpen? =
        providersByType[item.entryId.contentType]?.open(item)

    /**
     * The newest read across the content types the chip is showing, opened the way its row would be.
     * Only the active providers are asked, which is what keeps a Novels chip from resuming a manga:
     * a check after the fact would have to be repeated by every caller, and was missing from the one
     * that existed. Ordering is the feed's own, so the tie-break is not a second opinion.
     */
    suspend fun resumeLatest(): RecentsOpen? {
        val latest = orderRecents(activeProviders().mapNotNull { it.latestRead() }).firstOrNull()
        return latest?.let { open(it) }
    }

    // The render projection, forwarded because the providers themselves stay private: a renderer asks
    // the engine about a row and never learns which content type answered.

    fun rowUi(item: RecentsItem): RecentsRowUi =
        providersByType[item.entryId.contentType]?.rowUi(item) ?: EMPTY_RECENTS_ROW

    fun downloadUi(item: RecentsItem): RecentsDownloadUi? =
        providersByType[item.entryId.contentType]?.downloadUi(item)

    // The resolved continue-reading rows, keyed by the chapter each row was recorded from. That key is
    // unique among the rows using this memo: only read-lane rows resolve, and every mode that draws
    // one collapses its read lane to a row per entry before anything is drawn.

    private val mutableTargets = MutableStateFlow<Map<ChapterRef, RecentsTargetRow>>(emptyMap())
    val targets: StateFlow<Map<ChapterRef, RecentsTargetRow>> = mutableTargets.asStateFlow()

    /**
     * Whether [item] can name a chapter other than the one it was recorded from, which is the only
     * reason to pay a resolution: both providers load the entry's whole chapter list before any rule
     * runs, one list per member for a merged entry. A record that reads as unread resumes itself,
     * unless the entry is merged, where a chapter another source of the group has read counts as read
     * to the rule and not to this row's own flag. An unmerged entry cannot be in that state.
     */
    fun resolvesTarget(item: RecentsItem, mode: RecentsMode, membership: Map<EntryId, Long>): Boolean =
        mode.isCombined &&
            item.lane is RecentsLane.Read &&
            (rowUi(item).state?.read == true || item.entryId in membership)

    /** The resolved row for [item], from the memo where it is warm and by resolving where it is not. */
    suspend fun targetRow(item: RecentsItem): RecentsTargetRow? {
        val recorded = item.lane.chapterRef ?: return null
        mutableTargets.value[recorded]?.let { return it }
        val resolved = providersByType[item.entryId.contentType]?.targetRow(item) ?: return null
        mutableTargets.update { it + (recorded to resolved) }
        return resolved
    }

    /**
     * The chapters the bulk verbs act on: a continue-reading row answers for the chapter it names,
     * which in the combined modes is its target, and every other row for its own. Resolved in one
     * place so the four verbs cannot disagree about what a row is, and taken off the drawn [items]
     * rather than the selection, which holds refs and not rows.
     */
    suspend fun actingChapters(
        items: List<RecentsItem>,
        mode: RecentsMode,
        membership: Map<EntryId, Long>,
    ): Set<ChapterRef> = items.mapNotNullTo(mutableSetOf()) { item ->
        val recorded = item.lane.chapterRef ?: return@mapNotNullTo null
        if (!resolvesTarget(item, mode, membership)) return@mapNotNullTo recorded
        targetRow(item)?.ref ?: recorded
    }

    /**
     * Whether [item] survives [filters]. Only the read lane is judged: the updated lane is filtered by
     * the query behind it, and the newly added lane names no chapter, so filtering it by chapter state
     * would hide rows for failing a question they were never asked.
     */
    fun showsRow(item: RecentsItem, gate: RecentsRowGate, mode: RecentsMode): Boolean {
        if (!gate.keeps(item, mode)) return false
        val filters = gate.filters
        if (!filters.isActive || item.lane !is RecentsLane.Read) return true
        val state = rowUi(item).state ?: return true
        return filters.matches(state) { downloadUi(item)?.state?.invoke() == Download.State.DOWNLOADED }
    }

    /**
     * Every entry with an unread chapter, both types at once. Type-tagged ids, so the two providers'
     * sets union without collision, and independent of the chip: narrowing it per chip would rebuild
     * the set on every chip flip to answer a question that does not change with one.
     */
    private val unreadEntries: Flow<Set<EntryId>> by lazy {
        combine(providers.map { it.unreadEntries }) { sets -> sets.flatMapTo(HashSet()) { it } }
    }

    // Lazy like the flows it draws from: built eagerly it would force `chapterFilters`, whose own
    // source is declared below this and is still null while the constructor is running.

    /**
     * Seeded open, like [chapterFilters] beside it: until the unread set has actually been answered,
     * an unseeded gate would either stall the render or hide every row on an empty set. Hiding on
     * incomplete data is the wrong way to be wrong here, so the seed shows everything.
     */
    private val rowGate: StateFlow<RecentsRowGate> by lazy {
        combine(
            chapterFilters,
            sourcePreferences.recentsShowRead.changes(),
            unreadEntries,
        ) { filters, showRead, unread ->
            RecentsRowGate(filters = filters, showRead = showRead, unread = unread)
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RecentsRowGate.NONE)
    }

    /** The verbs are suspend because a merged row's action has to read the group's stitch first; the
     *  selection still clears at once, so the bar closes on the tap rather than on the write. That read
     *  is what makes the write outlive the screen: leaving it during the stitch read would otherwise
     *  drop a write the bar has already reported as done. Each provider moves its own work off the main
     *  thread, so this starts where the tap did rather than costing a dispatch. */
    private fun dispatch(action: suspend (RecentsChapterActions) -> Unit) {
        val targets = activeProviders().mapNotNull { it.chapterActions }
        viewModelScope.launch { withContext(NonCancellable) { targets.forEach { action(it) } } }
    }

    private fun dispatchAndClear(action: suspend (RecentsChapterActions) -> Unit) {
        dispatch(action)
        clearSelection()
    }

    private fun activeProviders(): List<RecentsProvider> =
        activeIndices(contentType.value).map { providers[it] }

    private val rawChapterFilters: Flow<RecentsChapterFilters> = combine(
        updatesPreferences.filterUnread.changes(),
        updatesPreferences.filterStarted.changes(),
        updatesPreferences.filterBookmarked.changes(),
        updatesPreferences.filterDownloaded.changes(),
    ) { unread, started, bookmarked, downloaded ->
        RecentsChapterFilters(unread, started, bookmarked, downloaded)
    }

    /** Every lane this surface renders, from one provider. Always collected, whatever the chip is. */
    private fun collectedLanes(provider: RecentsProvider): Flow<List<RecentsLaneRows>> =
        combine(lanes.map(provider::lane)) { it.toList() }

    private fun activeIndices(chip: ContentType): List<Int> =
        providers.indices.filter { chip == ContentType.ALL || providers[it].contentType == chip }
}

/**
 * The chapter-state filters count only where the view offers them, which is the same question
 * [RecentsEngine.showsRow] is judged under: asking it a second way (whether the updated lane renders)
 * gives the same four answers today and would drift the moment a view's lanes and its controls stop
 * lining up. Without the gate, History reports itself filtered by a filter set on Updates.
 * Scanlator exclusion takes a second gate: a novel chapter has no scanlator to exclude.
 */
internal fun recentsFilterActive(
    byCategory: Boolean,
    byChapterState: Boolean,
    byScanlator: Boolean,
    chipShowsManga: Boolean,
    mode: RecentsMode,
): Boolean = byCategory ||
    ((byChapterState || (byScanlator && chipShowsManga)) && mode.can(RecentsCapability.CHAPTER_FILTER))

/**
 * One assembly pass: the ordered rows and what the surface can say about them. [chip] is what the rows
 * were selected by, which the renderer compares against the live chip before drawing them.
 * [membership] rides along rather than being read separately, so a policy collapsing merged series can
 * never pair one emission's rows with another's groups.
 */
@Immutable
data class RecentsAssembled(
    val chip: ContentType,
    val items: List<RecentsItem>,
    val loading: Boolean,
    val membership: Map<EntryId, Long> = emptyMap(),
)

/**
 * One pass of what the surface draws. [loading] rides along rather than being read separately: the
 * rows and the reason there are none have to describe the same emission, or a feed announces itself
 * empty while its own query is still running.
 */
@Immutable
data class RecentsRendered(
    val rows: List<RecentsRow>,
    val loading: Boolean,
    /** Rides along for the same reason [loading] does: the gate deciding which rows resolve a target
     *  has to ask about the emission it is drawing, not whichever one the assembly is on now. */
    val membership: Map<EntryId, Long> = emptyMap(),
)
