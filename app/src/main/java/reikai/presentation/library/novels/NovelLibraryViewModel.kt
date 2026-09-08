package reikai.presentation.library.novels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.domain.library.model.search.QueryNode
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.category.GetNovelCategories
import reikai.domain.category.categoryFilterActive
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.librarySortComparator
import reikai.domain.library.sortForCategory
import reikai.domain.library.toSortMode
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.downloadedUnitsByGroup
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.NovelGroupChapters
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.model.withCustomInfo
import reikai.domain.novel.track.toUiTrack
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.presentation.category.toLongIdSet
import reikai.presentation.library.LibraryFilterPrefs
import reikai.presentation.library.LibraryGroup
import reikai.presentation.library.chapterSearchTerms
import reikai.presentation.library.libraryFilterMatches
import reikai.presentation.library.libraryItemFilterFields
import reikai.presentation.library.libraryItemQueryFields
import reikai.presentation.library.libraryItemSortFields
import reikai.presentation.library.libraryQueryMatches
import reikai.presentation.library.reikaiSortCategories
import reikai.presentation.library.toQueryOverlay
import reikai.presentation.novel.selectChaptersForDownloadAction
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the novel half of the Library tab: reads favorited novels and categories reactively, shapes
 * each into the shared [LibraryItem], filters and per-category-sorts them, and exposes the same
 * accessor surface the manga model does so `LibraryTab` can feed either. Mihon's library core is
 * untouched. Selection lives in the shared LibraryEngine, which hands this model the novel ids to act
 * on; display settings are shared with manga, and tracker filter/sort/group reuse the shared tracker
 * machinery via [getNovelTracks].
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelLibraryViewModel(
    private val novelRepository: NovelRepository,
    private val updateNovel: UpdateNovel,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val novelChapterRepository: NovelChapterRepository,
    // Deferred on purpose: building the manager restores the persisted queue and resumes the drain,
    // so taking it directly would start novel downloads merely because the library was opened.
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val novelDownloadCache: NovelDownloadCache,
    private val getNovelCategories: GetNovelCategories,
    // Per-entry custom title/cover overrides, overlaid on the displayed rows (display-only).
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val setNovelCategories: SetNovelCategories,
    private val libraryPreferences: LibraryPreferences,
    private val basePreferences: BasePreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val sourceManager: NovelSourceManager,
    private val mergeManager: NovelMergeManager,
    private val mergeGroupRepository: MergeGroupRepository,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
    private val reconcileMergedChapters: ReconcileMergedChapters,
    private val installer: LnPluginInstaller,
    private val trackerManager: TrackerManager,
    private val getNovelTracks: GetNovelTracks,
    private val getNextNovelChapter: GetNextNovelChapter,
) : ViewModel() {

    private val searchQuery = MutableStateFlow<String?>(null)

    private val activeCategoryIndex = MutableStateFlow(0)

    /** Null until the first build answers, which the derived state reads as still loading. */
    private val built: StateFlow<State?> =
        combine(
            getNovelCategories.subscribe(),
            // Re-emit when sources (un)register so `sourceManager.get(...)` resolves once loaded.
            // The custom-info overlay rides with the library so a title/cover edit re-emits too.
            combine(
                novelRepository.getLibraryNovelAsFlow()
                    .combine(sourceManager.sources) { library, _ -> library }
                    // Re-emit when a download/delete changes the disk index so the badge + filter refresh.
                    .combine(novelDownloadCache.changes) { library, _ -> library },
                getCustomNovelInfo.subscribeAll(),
                // Whole-library novel tracks (novelId -> tracks) ride with the library so a bind/unbind
                // re-sinks the tracker filter/sort/group; folded here to keep the main combine at 5 args.
                getNovelTracks.subscribeAll(),
                ::Triple,
            ),
            // Debounced so a burst of keystrokes rebuilds the list once, matching the manga library.
            // No distinctUntilChanged: a StateFlow already conflates equal values. The resolved
            // `chapter:` id sets ride the slot so the chapter-table scan runs once per query
            // change, not on every library, download-cache or track tick (mirrors the manga side).
            searchQuery.debounce(0.25.seconds).map { query -> query to resolveChapterMatches(query) },
            // The collapse preferences no longer reach this pipeline. They only ever fed grouping,
            // which LibraryEngine owns now, and leaving them in meant every collapse tap rebuilt the
            // whole filtered novel list (merge collapse, tracker scores, filtering) for nothing.
            settingsFlow(),
        ) { categories, (library, customInfo, tracks), search, settings ->
            buildState(categories, library, customInfo, tracks, search, settings)
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = combine(
        built,
        searchQuery,
        activeCategoryIndex,
    ) { built, searchQuery, activeCategoryIndex ->
        // The query and the active page come from their own holders, never from [built]: that lags the
        // user by a debounce plus a query, so taking its copy resets the search field to a stale value
        // mid-input and scrambles fast keystrokes. The selection is not here at all; the engine owns it.
        (built ?: State()).copy(
            searchQuery = searchQuery,
            activeCategoryIndex = activeCategoryIndex,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    init {
        // Load the plugin host so the library can resolve each novel's source (lang + source-icon
        // badges); the source flow above re-emits the build once the sources register.
        viewModelScope.launchIO { runCatching { installer.ensureLoaded() } }
        // A newly grouped entry's chapters have no cross-source identities yet, so the deduplicated
        // unread count would be wrong until something wrote them. Reconciling off the membership flow
        // covers every merge and unmerge from one place, and costs one indexed query when nothing
        // changed. Stays always-on: a restore can regroup entries while the library renders nothing.
        viewModelScope.launchIO {
            mergeGroupRepository.getAllMembershipsAsFlow(ContentType.NOVELS)
                .distinctUntilChanged()
                .collectLatest { reconcileMergedChapters.await() }
        }
    }

    private fun badgePrefsFlow(): Flow<BadgePrefs> = combine(
        libraryPreferences.downloadBadge.changes(),
        libraryPreferences.unreadBadge.changes(),
        libraryPreferences.languageBadge.changes(),
        reikaiLibraryPreferences.sourceBadge.changes(),
    ) { download, unread, language, source -> BadgePrefs(download, unread, language, source) }

    /** Folds the badge, sort, and filter prefs into one flow so the main combine stays at its 5-arg max. */
    private fun settingsFlow(): Flow<LibrarySettings> {
        val miscFlow = combine(
            // The library-wide global sort and Random seed, shared with the manga library (the retired
            // novel keys were dropped, not migrated; per-category overrides live in category flags).
            libraryPreferences.sortingMode.changes(),
            libraryPreferences.randomSortSeed.changes(),
            libraryPreferences.showContinueReadingButton.changes(),
            reikaiLibraryPreferences.showHiddenCategories.changes(),
            reikaiLibraryPreferences.categorySortOrder.changes(),
        ) { sort, seed, cont, showHidden, catSort -> Misc(sort.flag, seed.toLong(), cont, showHidden, catSort) }
        // The library-wide filter preferences, shared with the manga library since the filter
        // unification: a filter describes the list, not a content type.
        val triStateFilterFlow = combine(
            libraryPreferences.filterDownloaded.changes(),
            libraryPreferences.filterUnread.changes(),
            libraryPreferences.filterStarted.changes(),
            libraryPreferences.filterCompleted.changes(),
            libraryPreferences.filterBookmarked.changes(),
        ) { d, u, s, c, b -> NovelFilters(d, u, s, c, b) }
        // Category include/exclude rides in its own sub-flow so the tri-state combine stays at its 5-arg max.
        val categoryFilterFlow = combine(
            reikaiLibraryPreferences.filterCategories.changes(),
            reikaiLibraryPreferences.filterCategoriesInclude.changes(),
            reikaiLibraryPreferences.filterCategoriesExclude.changes(),
        ) { enabled, include, exclude ->
            val inc = include.toLongIdSet()
            val exc = exclude.toLongIdSet()
            Triple(categoryFilterActive(enabled, inc, exc), inc, exc)
        }
        val filterFlow = combine(
            triStateFilterFlow,
            categoryFilterFlow,
            basePreferences.downloadedOnly.changes(),
            trackingFilterFlow(),
            reikaiLibraryPreferences.filterLewd.changes(),
        ) { base, (active, inc, exc), downloadedOnly, trackingFilter, lewd ->
            FilterSettings(
                base.copy(
                    lewd = lewd,
                    categoriesActive = active,
                    categoriesInclude = inc,
                    categoriesExclude = exc,
                ),
                downloadedOnly,
                trackingFilter,
            )
        }
        val mergeFlow = combine(
            combine(
                mergeGroupRepository.getAllMembershipsAsFlow(ContentType.NOVELS),
                reikaiLibraryPreferences.seriesMergingEnabled.changes(),
                reikaiLibraryPreferences.showMergeSourceIcons.changes(),
                mergeGroupRepository.getOverrideRankingsAsFlow(ContentType.NOVELS),
                reikaiLibraryPreferences.preferredNovelSources.changes(),
            ) { membership, mergingEnabled, showIcons, overrideRankings, preferredSources ->
                MergeSettings(membership, mergingEnabled, showIcons, overrideRankings, preferredSources)
            },
            // Folded in rather than read while collapsing: reconciliation writes the stitch while the
            // library is already on screen, and nothing else makes this flow re-emit when it lands.
            mergedChapterUnitRepository.getUnreadCountsAsFlow(ContentType.NOVELS),
        ) { merge, unread -> merge.copy(mergedUnread = unread) }
        // No group-by input: grouping is LibraryEngine's, and re-running this whole pipeline on a
        // group-mode change would rebuild the filtered list for a decision it no longer makes.
        return combine(
            badgePrefsFlow(),
            miscFlow,
            filterFlow,
            mergeFlow,
        ) { badges, misc, filterSettings, merge ->
            LibrarySettings(
                badges, misc.defaultSort, misc.randomSeed, misc.showContinue, misc.showHidden,
                filterSettings.filters, filterSettings.downloadedOnly, merge, misc.categorySortOrder,
                filterSettings.trackingFilter,
            )
        }
    }

    /**
     * Per-logged-in-tracker filter state (trackerId -> tri-state), mirroring the manga library's
     * [eu.kanade.tachiyomi.ui.library.LibraryViewModel.getTrackingFiltersFlow]. The map's keys double
     * as the logged-in tracker id set (used to score/status-resolve only logged-in trackers below).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun trackingFilterFlow(): Flow<Map<Long, TriState>> =
        trackerManager.loggedInTrackersFlow().flatMapLatest { trackers ->
            if (trackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    trackers.map { tracker ->
                        libraryPreferences.filterTracking(tracker.id.toInt()).changes()
                            .map { tracker.id to it }
                    },
                ) { it.toMap() }
            }
        }

    /** One chapter-table LIKE scan per distinct `chapter:` term, resolved once per query change. */
    private suspend fun resolveChapterMatches(query: String?): Map<String, Set<Long>> {
        val node = query?.takeUnless { it.isBlank() }?.let(QueryNode::from) ?: return emptyMap()
        return node.chapterSearchTerms().associateWith { novelChapterRepository.getNovelIdsWithChapterNameLike(it) }
    }

    /**
     * Merged chapters with a copy on disk, per group. Members that have downloaded nothing are never
     * probed, so a library whose merged novels hold no downloads pays one indexed query. Twin of the
     * manga library's, over the same kernel.
     */
    private suspend fun mergedDownloadCounts(library: List<LibraryNovel>): Map<Long, Int> {
        val withDownloads = library.filter { it.downloadCount > 0 }
        if (withDownloads.isEmpty()) return emptyMap()
        val novelById = withDownloads.associate { it.novel.id to it.novel }
        return downloadedUnitsByGroup(
            rowsByGroup = mergedChapterUnitRepository.getDownloadUnits(ContentType.NOVELS),
            ownersWithDownloads = novelById.keys,
        ) { row ->
            val owner = novelById.getValue(row.ownerId)
            novelDownloadCache.isChapterDownloaded(owner.source, owner.title, row.chapterName, row.chapterUrl)
        }
    }

    private suspend fun buildState(
        categories: List<Category>,
        library: List<LibraryNovel>,
        customInfo: List<CustomNovelInfo>,
        tracks: Map<Long, List<NovelTrack>>,
        search: Pair<String?, Map<String, Set<Long>>>,
        settings: LibrarySettings,
    ): State {
        val (query, chapterMatches) = search
        // Downloaded state is disk-derived (NovelDownloadCache), not a DB column, so fill each novel's
        // download count from the cache before it feeds the filter, sort, collapse, and badge.
        val withCounts = library.map {
            it.copy(downloadCount = novelDownloadCache.getDownloadCount(it.novel).toLong())
        }
        // Collapse merged groups into one representative entry (the most-chapters novel) BEFORE
        // filtering, matching the manga library. Filtering first would test each source separately, so a
        // group could survive on a member the user never sees, and the representative would be picked
        // from whichever members happened to pass, changing the cover as filters change.
        val collapsedAll = NovelMergeCollapse.collapse(
            withCounts,
            settings.merge.membership,
            settings.merge.mergingEnabled,
            settings.merge.overrideRankings,
            settings.merge.preferredSources,
        )
        // Replace each group's unread with the deduplicated cross-source count: one unit per chapter the
        // group covers, unread only when no source's copy is read. A group absent from the map has not
        // been stitched yet, so it keeps the representative's own count: reading that as zero badged a
        // freshly stitched library as fully read until something made the list rebuild.
        val mergedUnread = if (settings.merge.mergingEnabled) settings.merge.mergedUnread else emptyMap()
        // Downloads take the same treatment, which summing the members double-counted for every chapter
        // two of them hold. Absent means the group holds none; an empty map means nothing probed it.
        val mergedDownloads = if (settings.merge.mergingEnabled) mergedDownloadCounts(withCounts) else emptyMap()
        val groups = collapsedAll.map { group ->
            val groupId = settings.merge.membership[group.representative.novel.id]
                ?.takeIf { group.memberIds.size > 1 }
            val merged = groupId?.let { mergedUnread[it] }
            val downloads = groupId?.takeIf { mergedDownloads.isNotEmpty() }?.let { mergedDownloads[it] ?: 0 }
            group.copy(
                unreadCount = merged ?: group.unreadCount,
                totalDownloadCount = downloads?.toLong() ?: group.totalDownloadCount,
            )
        }
        // Union each merge group's member tracks (deduped per tracker), keyed by the rep's real novel id,
        // so the shared filter's tracker axis and the sort's mean score both read a track bound on ANY
        // grouped source. Synchronous: reads the in-memory group members, never the suspend awaitGroup.
        val loggedInTrackerIds = settings.trackingFilter.keys
        val tracksByRep: Map<Long, List<NovelTrack>> = groups.associate { group ->
            group.representative.novel.id to group.memberIds
                .flatMap { tracks[it].orEmpty() }
                .distinctBy { it.trackerId }
        }
        // Per-rep mean tracker score (0-10, logged-in trackers only; unscored reps omitted), for the sort.
        val trackerMeanScores: Map<Long, Double> = buildMap {
            tracksByRep.forEach { (repId, repTracks) ->
                val scores = repTracks
                    .filter { it.trackerId in loggedInTrackerIds }
                    .mapNotNull {
                        trackerManager.get(it.trackerId)?.get10PointScore(it.toUiTrack())?.takeIf { s ->
                            s >
                                0.0
                        }
                    }
                if (scores.isNotEmpty()) put(repId, scores.average())
            }
        }
        // The one shared library filter (tracker axis folded in), so a filter change reaches manga and
        // novels at once. The per-type seams live in the accessors: novels have no local-source or
        // fetch-interval concept, and their lewd check is genre-only.
        val f = settings.filters
        val filterPrefs = LibraryFilterPrefs(
            downloaded = if (settings.downloadedOnly) TriState.ENABLED_IS else f.downloaded,
            unread = f.unread,
            started = f.started,
            bookmarked = f.bookmarked,
            completed = f.completed,
            intervalCustom = TriState.DISABLED,
            lewd = f.lewd,
            includedTracks = settings.trackingFilter.filterValues { it == TriState.ENABLED_IS }.keys,
            excludedTracks = settings.trackingFilter.filterValues { it == TriState.ENABLED_NOT }.keys,
            categoriesActive = f.categoriesActive,
            categoriesInclude = f.categoriesInclude,
            categoriesExclude = f.categoriesExclude,
        )
        // novelId -> source id, to resolve each grouped source's icon for the merge badge.
        val sourceByNovelId = library.associate { it.novel.id to it.novel.source }
        // Keyed by the representative's novel id (== the LibraryItem id). The dynamic grouping resolves
        // per-novel metadata (genre / author / source / status) the row cannot carry, and the search
        // needs the source name and slug, since a novel row has no Mihon Source to read either off.
        val novelById = groups.associate { it.representative.novel.id to it.representative }
        // Display-only custom-info overlay, keyed by the real novel id. Carried into the state and
        // applied at the display read (see State.getItemsForCategory), never here, so collapse, filter,
        // sort, grouping and search all keep reading the source values. Mirrors the manga library.
        val overlay = customInfo.associateBy { it.novelId }
        // Build the shared library row BEFORE filtering and sorting, so both content types reach the
        // shared kernels at the same point in the type chain (the manga library already builds first).
        val allItems = groups.map { group ->
            val rep = group.representative
            // lnreader plugins mostly declare lang as a full English name ("English"); the badge wants a
            // 2-char code like the manga side, so reduce it (codes pass through unchanged).
            val source = sourceManager.get(rep.novel.source)
            val lang = languageCodeOf(source?.lang.orEmpty())
            val item = rep.toLibraryItem(
                settings.badges.download,
                settings.badges.unread,
                settings.badges.language,
                lang,
                sourceBadge = settings.badges.source,
                sourceSite = source?.site,
                sourceIconUrl = source?.iconUrl,
                sourceName = novelSourceName(rep.novel.source),
            )
            if (group.memberIds.size > 1) {
                // Stamp the merge badge (group member ids) + summed downloads onto the rep.
                // When the merge-icon setting is on, also resolve each grouped source's icon URL.
                val iconUrls = if (settings.merge.showSourceIcons) {
                    group.memberIds
                        .mapNotNull { id -> sourceByNovelId[id]?.let { sourceManager.get(it)?.iconUrl } }
                        .distinct()
                } else {
                    emptyList()
                }
                item.copy(
                    downloadCount = group.totalDownloadCount.toInt(),
                    // The group's deduplicated unread, so the badge, the continue button, the filter and
                    // the sort all report the same number for a merged entry.
                    unreadCount = group.unreadCount,
                    relatedMangaIds = group.memberIds,
                    badges = item.badges.copy(
                        downloadCount = if (settings.badges.download) group.totalDownloadCount.toInt() else 0,
                        unreadCount = if (settings.badges.unread) group.unreadCount else 0,
                        mergedSourceIconUrls = iconUrls,
                    ),
                )
            } else {
                item
            }
        }
        // The one filter binding both libraries use. The lewd heuristic's source-name half is manga-only,
        // so novels pass null and fall through to its genre half, which is their whole check.
        val filterFields = libraryItemFilterFields(
            lewdSourceName = { null },
            trackerIds = { item -> tracksByRep[item.id].orEmpty().map { it.trackerId } },
        )
        // The search twin of the filter binding above, and the same kernel the manga library runs, so one
        // typed query means one thing on every row of the All list. The seams: a novel's source key is its
        // plugin slug (manga supply a numeric id), and neither time comparison applies, so both are gated
        // null rather than answered from the synthetic row's zero defaults.
        val queryNode = query?.takeUnless { it.isBlank() }?.let(QueryNode::from)
        val queryFields = libraryItemQueryFields(
            sourceKey = { item -> novelById[item.id]?.novel?.source.orEmpty() },
            fetchInterval = { null },
            nextUpdate = { null },
            chapterMatches = chapterMatches,
            // Search matches what the card shows, so a renamed novel is findable by the name you gave it.
            // The rows stay override-free: filter, sort and grouping deliberately read the source values.
            overlay = overlay.mapValues { (_, custom) -> custom.toQueryOverlay() },
        )
        val items = allItems.filter { item ->
            val matchesSearch = queryNode == null || libraryQueryMatches(queryNode, item, queryFields)
            matchesSearch && libraryFilterMatches(item, filterPrefs, filterFields)
        }
        val byId = items.associateBy { it.id }
        // Bucketing, category order and sorting all moved to LibraryEngine's shared assembly, which sees
        // both content types. This model stops at the filtered rows, its split point.

        // Item id -> (source, url) so LibraryTab can open the (representative) novel. Over the displayed
        // rows, so the hopper's random actions pick from what is actually on screen.
        val routes = items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            item.id to NovelRoute(novel.source, novel.url)
        }.toMap()

        return State(
            isLoading = false,
            searchQuery = query,
            favorites = items,
            trackerMeans = trackerMeanScores,
            novelById = novelById,
            tracksByRep = tracksByRep,
            favoritesById = byId,
            customInfo = overlay,
            novelRoutes = routes,
            hasActiveFilters = settings.filters.hasActive ||
                settings.trackingFilter.values.any { it != TriState.DISABLED },
            showContinueButton = settings.showContinue,
        )
    }

    // --- search / selection / collapse mutators (read by LibraryTab) ---

    fun search(query: String?) {
        searchQuery.value = query
    }

    fun updateActiveCategoryIndex(index: Int) {
        activeCategoryIndex.value = index
    }

    // --- multi-select actions ---

    /** Manually merge the selected novels into one group (covers both library views). */
    fun mergeSelection(ids: List<Long>) {
        if (ids.size < 2) return
        viewModelScope.launchIO {
            // each selected card's whole group is absorbed by the merge, so one call coalesces every source
            mergeManager.merge(ids)
        }
    }

    /** Split the selected novels out of their merge groups (no-op for non-merged selections).
     *  The manager hands each member its own tracker copy on the way out. */
    fun unmergeSelection(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launchIO { mergeManager.unmerge(ids) }
    }

    fun markReadSelection(ids: List<Long>, read: Boolean) {
        // Mark every source of a merge group, so a merged series doesn't stay part-read on the
        // sources that aren't the representative.
        val novelIds = state.value.memberIdsFor(ids)
        // Non-cancellable like the manga twins: a bulk write must not half-apply because the
        // screen was left mid-loop.
        viewModelScope.launchNonCancellable {
            // The interactor groups by novel for delete-after-read, so pass every selected novel's chapters.
            val chapters = novelIds.flatMap { novelChapterRepository.getByNovelId(it) }
            setNovelReadStatus.await(read, chapters)
        }
    }

    fun performDownloadAction(ids: List<Long>, action: DownloadAction) {
        // NOT expanded over the group's members: they carry the same chapters, so downloading every
        // member would fetch each chapter once per source and waste the storage on near-duplicates.
        // The target is the group's deduplicated list, the one the details "All" view shows, with each
        // chapter fetched from the source that carries it.
        viewModelScope.launchNonCancellable {
            ids.forEach { id ->
                val group = getNextNovelChapter.groupChapters(id)
                val downloadManager = novelDownloadManager()
                // Probed over every member's chapters: a chapter downloaded on any of them is on disk,
                // whichever copy the stitch shows.
                val novelsById = group.pooledChapters.map { it.novelId }.distinct()
                    .mapNotNull { novelId -> novelRepository.getById(novelId)?.let { novelId to it } }
                    .toMap()
                val downloadedIds = group.pooledChapters.mapNotNullTo(HashSet()) { chapter ->
                    val novel = novelsById[chapter.novelId] ?: return@mapNotNullTo null
                    chapter.id.takeIf { downloadManager.isChapterDownloaded(novel, chapter) }
                }
                val onDisk = downloadedIds + group.flaggedElsewhere { it.id in downloadedIds }
                val queuedIds = downloadManager.queueState.value.mapTo(HashSet()) { it.chapterId }
                val targets = selectChaptersForDownloadAction(
                    group.chapters,
                    action,
                    onDisk + queuedIds,
                    group.readInOtherSources,
                    group.flaggedElsewhere { it.bookmark },
                )
                if (targets.isNotEmpty()) downloadManager.downloadChapters(targets)
            }
        }
    }

    private fun NovelGroupChapters.flaggedElsewhere(flag: (NovelChapter) -> Boolean): Set<Long> =
        flaggedOnAnotherSource(pooledChapters, chapters, stitch, { it.id }, flag)

    /** Writes exactly the ids it is handed; the caller expands the merge group. */
    fun setNovelCategories(novelIds: List<Long>, addCategories: List<Long>, removeCategories: List<Long>) {
        viewModelScope.launchNonCancellable {
            novelIds.forEach { novelId ->
                val current = getNovelCategories.awaitByNovelId(novelId).map { it.id }
                val new = (current - removeCategories.toSet() + addCategories).distinct()
                setNovelCategories.await(novelId, new)
            }
        }
    }

    fun removeNovels(
        novelIds: List<Long>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        // Expand merged covers to every grouped source, so the whole series leaves the library.
        removeGroupedSources: Boolean = false,
    ) {
        viewModelScope.launchNonCancellable {
            val targets = if (removeGroupedSources) state.value.memberIdsFor(novelIds) else novelIds
            // An entry leaving the library keeps its group, so it has to be handed its own copy of the
            // group's shared tracker first; the hand-out skips non-favorites.
            if (deleteFromLibrary) mergeManager.handOutTrackersBeforeRemoval(targets)
            targets.forEach { novelId ->
                if (deleteFromLibrary) {
                    updateNovel.awaitUpdateFavorite(novelId, favorite = false)
                }
                if (deleteDownloads) {
                    val novel = novelRepository.getById(novelId)
                    val downloadManager = novelDownloadManager()
                    val downloaded = if (novel == null) {
                        emptyList()
                    } else {
                        novelChapterRepository.getByNovelId(novelId)
                            .filter { downloadManager.isChapterDownloaded(novel, it) }
                    }
                    if (downloaded.isNotEmpty()) downloadManager.deleteChapters(downloaded)
                }
            }
        }
    }

    /** The next-unread chapter to resume. For a merged novel this pools the whole group (the unified
     *  cross-source list the details "All" view shows) to find the first unread; the reader itself
     *  resolves the group order for prev/next, so only the chapter is returned. */
    suspend fun getResume(repNovelId: Long): NovelChapter? =
        getNextNovelChapter.awaitFirstUnreadInGroup(repNovelId)

    // --- settings sheet (sort / filter), rendered from the engine's dialog ---
    // Sort writes live in NovelLibraryAdapter, routed through the shared SetSortModeForCategory exactly
    // like the manga side, since the global sort and Random seed are one library-wide preference pair.

    // The filter axes read the library-wide preferences directly in NovelLibraryAdapter's
    // LibrarySettingsBinding since the filter unification; only the genuinely per-type members remain.

    /** Dynamic grouping mode, for the settings sheet's Group tab. Library-wide since the grouping
     *  unification, so setting it under either chip groups the whole library. */
    val groupLibraryBy: Preference<Int> get() = reikaiLibraryPreferences.groupLibraryBy

    /** Full novel category list (the Default row 0 + user categories, sorted) for the filter picker.
     *  Not [State.displayedCategories]: that drops empty categories and is replaced by dynamic groups
     *  when grouping is on, neither of which suits a category filter. */
    val filterPickerCategories: StateFlow<List<Category>> = combine(
        getNovelCategories.subscribe(),
        reikaiLibraryPreferences.categorySortOrder.changes(),
    ) { categories, sortOrder ->
        reikaiSortCategories(categories.sortedBy { it.order }, sortOrder)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /** A novel's human-readable source name for search, or the raw slug when the plugin isn't installed. */
    private suspend fun novelSourceName(source: String): String = sourceManager.get(source)?.name ?: source

    private data class BadgePrefs(
        val download: Boolean,
        val unread: Boolean,
        val language: Boolean,
        val source: Boolean,
    )

    private data class NovelFilters(
        val downloaded: TriState,
        val unread: TriState,
        val started: TriState,
        val completed: TriState,
        val bookmarked: TriState,
        val lewd: TriState = TriState.DISABLED,
        val categoriesActive: Boolean = false,
        val categoriesInclude: Set<Long> = emptySet(),
        val categoriesExclude: Set<Long> = emptySet(),
    )

    private data class Misc(
        val defaultSort: Long,
        val randomSeed: Long,
        val showContinue: Boolean,
        val showHidden: Boolean,
        val categorySortOrder: Int,
    )

    private data class MergeSettings(
        val membership: Map<Long, Long>,
        val mergingEnabled: Boolean,
        val showSourceIcons: Boolean,
        // Per-group source-order overrides and the global preferred novel-source list, so the collapsed
        // row leads on the user's chosen trunk. A reorder writes these and re-collapses the library live.
        val overrideRankings: Map<Long, List<Long>>,
        val preferredSources: List<String>,
        /** Per group, one unit per chapter it covers that no source has read. A group absent from the
         *  map has not been stitched yet, which is not the same as having nothing left to read. */
        val mergedUnread: Map<Long, Long> = emptyMap(),
    )

    /** Carries the per-session filters plus the global Downloaded-only mode out of the filter sub-flow. */
    private data class FilterSettings(
        val filters: NovelFilters,
        val downloadedOnly: Boolean,
        val trackingFilter: Map<Long, TriState>,
    )

    private data class LibrarySettings(
        val badges: BadgePrefs,
        val defaultSort: Long,
        val randomSeed: Long,
        val showContinue: Boolean,
        val showHidden: Boolean,
        val filters: NovelFilters,
        val downloadedOnly: Boolean,
        val merge: MergeSettings,
        val categorySortOrder: Int,
        // Per-logged-in-tracker filter (trackerId -> tri-state); keys are the logged-in tracker ids.
        val trackingFilter: Map<Long, TriState>,
    )

    private val NovelFilters.hasActive: Boolean
        get() = categoriesActive ||
            listOf(downloaded, unread, started, completed, bookmarked, lewd).any { it != TriState.DISABLED }

    data class State(
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val activeCategoryIndex: Int = 0,
        val hasActiveFilters: Boolean = false,
        val showContinueButton: Boolean = false,
        /** The filtered, merge-collapsed rows before bucketing and sort, in pipeline order; the novel
         *  split point the provider's row flow reads (the twin of manga's LibraryData.favorites). The
         *  custom-info overlay is NOT applied here, matching the manga contract. */
        val favorites: List<LibraryItem> = emptyList(),
        /** Per-rep mean tracker score (0-10, unscored reps absent), for the sort and the provider seam
         *  (LibraryProvider.trackerMeans). */
        val trackerMeans: Map<Long, Double> = emptyMap(),
        /** Rep id -> its LibraryNovel and unioned merge-group tracks, carried for the dynamic-grouping
         *  feed seam (LibraryProvider.dynamicGroupingFeed), which resolves metadata the row cannot carry. */
        val novelById: Map<Long, LibraryNovel> = emptyMap(),
        val tracksByRep: Map<Long, List<NovelTrack>> = emptyMap(),
        private val favoritesById: Map<Long, LibraryItem> = emptyMap(),
        /** Display-only overrides, keyed by real novel id; applied at the display read only. */
        private val customInfo: Map<Long, CustomNovelInfo> = emptyMap(),
        private val novelRoutes: Map<Long, NovelRoute> = emptyMap(),
    ) {
        /** Identity of [customInfo], so a display-overlay edit is not conflated away downstream. */
        val overlayKey: Any get() = customInfo

        val isLibraryEmpty = favoritesById.isEmpty()

        // These resolve an explicit id set rather than reading the selection, so a bulk action is driven
        // by the ids its caller passes. That is what lets the shared engine own a selection spanning both
        // content types and hand each provider only its own ids. Mirrors the manga library.

        /** Any of [ids] is a merge group (drives the bulk Unmerge action). */
        fun containsMerged(ids: Collection<Long>): Boolean =
            ids.any { (favoritesById[it]?.relatedMangaIds?.size ?: 0) > 1 }

        /** Every grouped source-novel behind [ids]. A merged cover is one id standing for its whole
         *  group (relatedMangaIds); this expands each to all members. Equals [ids] when none is merged. */
        fun memberIdsFor(ids: Collection<Long>): List<Long> =
            ids.flatMap { id ->
                val item = favoritesById[id] ?: return@flatMap emptyList<Long>()
                item.relatedMangaIds.ifEmpty { listOf(id) }
            }.distinct()

        /**
         * The one place the overlay is applied, reached through the provider seam
         * (LibraryProvider.overlaid) at the shared assembly's display read, so the overrides never reach
         * the raw rows that filter, sort and search read. Mirrors the manga library.
         */
        fun withOverlay(item: LibraryItem): LibraryItem {
            val custom = customInfo[item.id] ?: return item
            return item.copy(
                libraryManga = item.libraryManga.copy(
                    manga = item.libraryManga.manga.withCustomInfo(custom),
                ),
            )
        }

        /** (source, url) for the item id, to open the novel details screen. */
        fun routeFor(itemId: Long): NovelRoute? = novelRoutes[itemId]
    }

    data class NovelRoute(val source: String, val url: String)
}
