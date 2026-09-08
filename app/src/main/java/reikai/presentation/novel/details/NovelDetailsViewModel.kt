package reikai.presentation.novel.details

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import reikai.data.coil.NovelCover
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.mergeRefreshedNovel
import reikai.data.novel.refreshNovelFromSource
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.data.novel.toNovel
import reikai.domain.category.GetNovelCategories
import reikai.domain.entry.EntryId
import reikai.domain.entry.vibrantColorKey
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.expandToUnits
import reikai.domain.merge.readOnAnotherSource
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterListEntry
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.buildNovelChapterListEntries
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.SetNovelChapterFlags
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveDownloadedFilter
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveReadFilter
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import reikai.domain.novel.model.sortedAndFiltered
import reikai.domain.novel.novelMissingChapterCount
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.novel.track.toUiTrack
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.toDownloadState
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.addEntry
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.browse.finishAdd
import reikai.presentation.details.EntryAutoTrackOnMarkRead
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.EntryManageSourceInfo
import reikai.presentation.details.EntryMergeActionHost
import reikai.presentation.details.EntryMergeGroupHost
import reikai.presentation.details.EntryMergeSource
import reikai.presentation.details.buildTrackerAutofillCandidates
import reikai.presentation.details.hiddenChapterIdsIn
import reikai.presentation.details.resolveHiddenChapterView
import reikai.presentation.library.reikaiSortCategories
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.presentation.novel.selectChaptersForDownloadAction
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.data.Database
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR

/**
 * Light-novel details state holder, re-typed from Yōkai's `NovelDetailsViewModel` onto the Mihon
 * repos. DB-first: the stored novel + its chapters drive the screen; the source is hit only on first
 * open (no local chapters) or an explicit [refresh]. Owns favorite, categories, edit-info, chapter
 * sort/filter/display, multi-select read/bookmark, and the cover-tint seed. A merged series is
 * surfaced through the [NovelDetailsState.Loaded.displayNovel] seam.
 */
@AssistedInject
class NovelDetailsViewModel(
    /** A novel source id is a plugin string, so this is Reikai's shape rather than upstream's Long. */
    @Assisted private val sourceId: String,
    @Assisted private val novelUrl: String,
    private val novelRepo: NovelRepository,
    private val updateNovel: UpdateNovel,
    // "Reset all" clears the cached custom cover too, not just the custom-info row.
    private val coverCache: CoverCache,
    private val setNovelChapterFlags: SetNovelChapterFlags,
    private val chapterRepo: NovelChapterRepository,
    private val database: Database,
    private val downloadManagerProvider: Provider<NovelDownloadManager>,
    private val novelDownloadCache: NovelDownloadCache,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val getNovelCategories: GetNovelCategories,
    private val setNovelCategories: SetNovelCategories,
    private val novelLibraryAdder: NovelLibraryAdder,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val novelPreferences: NovelPreferences,
    private val uiPreferences: UiPreferences,
    private val mergeManager: NovelMergeManager,
    private val mergedChapterProvider: NovelMergedChapterProvider,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val context: Context,
    // Non-destructive custom-info overlay (edits never touch the novels row, so Reset is clean).
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val setCustomNovelInfo: SetCustomNovelInfo,
    private val getNovelTracks: GetNovelTracks,
    private val refreshNovelTracks: RefreshNovelTracks,
    private val trackNovelChapter: TrackNovelChapter,
    private val trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
) : ViewModel() {

    // Building the manager restores the persisted queue and can start the download worker, so it is
    // resolved on first read rather than at construction: every read sits inside a coroutine, which
    // keeps that work off the thread the screen opens on and skips it when nothing reads it at all.
    private val downloadManager: NovelDownloadManager get() = downloadManagerProvider()

    val state: StateFlow<NovelDetailsState>
        field = MutableStateFlow<NovelDetailsState>(NovelDetailsState.Loading)

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: String, novelUrl: String): NovelDetailsViewModel
    }

    /** Hosts the merge split/remove Undo snackbars; wired into the details Scaffold. */
    val snackbarHostState = SnackbarHostState()

    /** Resolved once the plugin host loads it; source-dependent ops defer until set. */
    @Volatile
    private var source: NovelSource? = null

    /** A first-open fetch (no stored chapters) runs at most once. */
    private var firstFetchTried = false
    private var refreshJob: Job? = null
    private var seedExtracted = false

    /** The opened novel's id (the merge "current" source); set once the anchor resolves. */
    @Volatile
    private var anchorNovelId = -1L

    /** The page (index into [NovelDetailsState.Loaded.pages]) the chapter list is showing. */
    private val pageIndex = MutableStateFlow(0)

    /** novelId -> resolved source for every grouped sibling (unified rank, chips, reader routing). Novel-only
     *  (manga has no analogue), so it stays here and is populated inside the host's source resolver. */
    private val siblingSources = MutableStateFlow<Map<Long, NovelSource>>(emptyMap())

    /**
     * Shared merge read/observe wiring: the group ids, the selected source chip, the membership observer,
     * and the switcher chips. Written once in [EntryMergeGroupHost] (the manga model composes the same host),
     * so the read wiring can't drift between content types the way it did before. The two per-type seams:
     * [EntryMergeGroupHost.observe]'s anchor flow resolves the novel's anchor from url + source (and updates
     * [anchorNovelId]) before recomputing the group, and the source resolver does the async plugin-load,
     * builds [siblingSources], and returns the chips.
     */
    private val mergeGroup = EntryMergeGroupHost(
        mergeManager = mergeManager,
        initialIds = longArrayOf(),
        anchorChanges = combine(
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId),
            mergeManager.membershipChanges(),
        ) { anchor, _ -> anchor }
            .filterNotNull()
            .onEach { anchorNovelId = it.id }
            .map { it.id },
        resolveSources = { ids -> resolveMergeSources(ids) },
    )

    /** User-hidden chapters, keyed `"<source>|<chapterUrl>"` (restore-stable). Filtered out of the
     *  list unless [showHiddenFlow] is on (then shown dimmed). */
    private val hiddenChaptersPref = novelPreferences.hiddenChapters()

    /** Whether hidden chapters are temporarily shown (dimmed) so they can be unhidden. */
    private val showHiddenFlow = MutableStateFlow(false)

    /** Paged keys already lazily fetched, so an empty page doesn't re-fetch on every flow emission. */
    private val triedPages = java.util.Collections.synchronizedSet(HashSet<String>())

    // Source of truth for the chapter multi-select; `Loaded.selection` mirrors it for the UI.
    private var chapterSelection = SelectionState<Long>()

    /** Latest observed bound-tracker count, held outside state so the first [NovelDetailsState.Loaded]
     *  built picks it up even when the observer emitted while the screen was still loading. */
    @Volatile
    private var currentTrackingCount = 0

    /** Latest custom-info overlay for the anchor novel, held outside state so the first
     *  [NovelDetailsState.Loaded] built picks it up (mirrors [currentTrackingCount]). */
    @Volatile
    private var currentCustomInfo: CustomNovelInfo? = null

    init {
        mergeGroup.observe(viewModelScope)
        observeChapters()
        observeDownloadQueue()
        observeTrackingCount()
        observeCustomInfo()
        resolveSource()
    }

    /** Mirror the bound-tracker count (on logged-in services) into [NovelDetailsState.Loaded.trackingCount],
     *  so the action-row Tracking button shows the count + flips its icon, like the manga header. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTrackingCount() {
        viewModelScope.launchIO {
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId)
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { novelId ->
                    if (novelId == null) {
                        flowOf(0)
                    } else {
                        // subscribeGroup spans the merge group, so a track bound on a sibling source counts.
                        combine(
                            getNovelTracks.subscribeGroup(novelId),
                            trackerManager.loggedInTrackersFlow(),
                        ) { tracks, loggedIn ->
                            val loggedInIds = loggedIn.mapTo(HashSet()) { it.id }
                            tracks.count { it.trackerId in loggedInIds }
                        }
                    }
                }
                .collectLatest { count ->
                    currentTrackingCount = count
                    state.update { (it as? NovelDetailsState.Loaded)?.copy(trackingCount = count) ?: it }
                }
        }
    }

    /** Mirror the novel's custom-info overlay into [NovelDetailsState.Loaded.customInfo], so the header,
     *  description, tags, and cover show the user's edits (the raw novel stays source-accurate). Keyed on
     *  the anchor id; a write to custom_novel_info re-emits and the display updates on its own. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCustomInfo() {
        viewModelScope.launchIO {
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId)
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { novelId ->
                    if (novelId == null) flowOf(null) else getCustomNovelInfo.subscribe(novelId)
                }
                .collectLatest { info ->
                    currentCustomInfo = info
                    state.update { (it as? NovelDetailsState.Loaded)?.copy(customInfo = info) ?: it }
                }
        }
    }

    /** Mirror the live download queue into [NovelDetailsState.Loaded.downloadStates]. Only the active
     *  queue states (queued/downloading/error) live here; a finished download is read from
     *  [NovelDetailsState.Loaded.downloadedChapterIds] (disk-derived) instead. */
    private fun observeDownloadQueue() {
        viewModelScope.launchIO {
            downloadManager.queueState.collectLatest { queue ->
                val map = queue.associate { it.chapterId to it.state.toDownloadState() }
                state.update { (it as? NovelDetailsState.Loaded)?.copy(downloadStates = map) ?: it }
            }
        }
    }

    private fun resolveSource() {
        viewModelScope.launchIO {
            try {
                installer.ensureLoaded()
            } catch (_: Throwable) {}
            val resolved = sourceManager.get(sourceId)
            if (resolved == null) {
                if (state.value !is NovelDetailsState.Loaded) {
                    state.value = NovelDetailsState.Failed("Source not installed: $sourceId")
                }
            } else {
                source = resolved
                state.update {
                    (it as? NovelDetailsState.Loaded)?.let { l ->
                        l.copy(
                            sourceName = resolved.name,
                            sourceUrl = resolved.site,
                            novelWebUrl = resolved.webUrl(l.displayNovel.url),
                        )
                    } ?: it
                }
                val loaded = state.value as? NovelDetailsState.Loaded
                if (loaded == null || loaded.chapters.isEmpty()) maybeFirstFetch(loaded?.novel)
            }
        }
    }

    private data class ChapterInputs(
        val anchor: Novel?,
        val related: LongArray,
        val selected: Long?,
        val pageIndex: Int,
    )

    // DB-first: the stored anchor novel + the resolved merge group drive the chapter list. The unified
    // ("All") view pools every grouped source's chapters into one list (no page bar); a selected source
    // chip (or a non-merged novel) keeps its own per-page lazy list. A change to
    // chapterFlags / page / group / selection re-runs this via the combine.
    private fun observeChapters() {
        viewModelScope.launchIO {
            combine(
                combine(
                    novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId),
                    mergeGroup.state,
                    pageIndex,
                    // In the combine only to re-emit (re-running rebuildLoaded with the chips) once they resolve.
                    mergeGroup.chips,
                ) { anchor, group, idx, _ -> ChapterInputs(anchor, group.ids, group.selected, idx) },
                // Re-emit so a hide/unhide or the show-hidden toggle rebuilds the chapter list.
                hiddenChaptersPref.changes(),
                showHiddenFlow,
            ) { inputs, _, _ -> inputs }
                .collectLatest { (anchor, related, selected, idx) ->
                    if (anchor == null) {
                        maybeFirstFetch(null)
                        return@collectLatest
                    }
                    if (related.size > 1 && selected == null) {
                        observeUnifiedChapters(anchor, related)
                    } else {
                        observeSingleChapters(anchor, selected, idx)
                    }
                }
        }
    }

    /**
     * Resolve each grouped source + build the switcher chips (the host's per-type source resolver). Async:
     * the plugin host must be loaded before a source resolves. Also populates [siblingSources] (the map the
     * unified ranking + reader routing read), which has no manga analogue, before returning the chips, so the
     * chips-change that re-emits the chapter combine already sees the up-to-date map. Empty (and clears the
     * sibling map) when not merged.
     */
    private suspend fun resolveMergeSources(ids: LongArray): List<EntryMergeSource> {
        if (ids.size <= 1) {
            siblingSources.value = emptyMap()
            return emptyList()
        }
        runCatching { installer.ensureLoaded() }
        val resolved = HashMap<Long, NovelSource>()
        val chips = mutableListOf<EntryMergeSource>()
        for (id in ids) {
            val novel = novelRepo.getById(id) ?: continue
            val src = sourceManager.get(novel.source)
            if (src != null) resolved[id] = src
            chips += EntryMergeSource(id, src?.name ?: novel.source)
        }
        siblingSources.value = resolved
        return chips
    }

    /** Unified ("All") view: pool every grouped source's chapters into one aggregated, reading-ordered
     *  list (no pagination, pages don't align across sources). Each chapter keeps its own novelId. */
    private suspend fun observeUnifiedChapters(anchor: Novel, related: LongArray) {
        val flows = related.map { id -> chapterRepo.getByNovelIdAsFlow(id).map { id to it } }
        // Fold the download cache's change signal in so a download/delete rebuilds the list (the
        // downloaded state is disk-derived now, not a chapter-row flow).
        combine(
            combine(flows) { pairs -> pairs.toMap() },
            novelDownloadCache.changes,
        ) { byNovel, _ -> byNovel }.collectLatest { byNovel ->
            // Read off the stored stitch, the same rows the library badge counts, rather than
            // stitching again here where the two could come to different answers.
            val pooled = byNovel.values.flatten()
            val stitch = mergedChapterProvider.stitchOf(anchor.id)
            val ordered = mergedChapterProvider.merged(pooled, stitch)
            val readElsewhere = readOnAnotherSource(pooled, ordered, stitch, { it.id }, { it.read })
            rebuildLoaded(anchor, anchor, ordered, emptyList(), 0, downloadedIdsFor(ordered), readElsewhere)
        }
    }

    /** Disk-download membership (from NovelDownloadCache) for [chapters], resolving each chapter's
     *  owning novel (a unified merged list spans several sources). Replaces the old is_downloaded flag. */
    private suspend fun downloadedIdsFor(chapters: List<NovelChapter>): Set<Long> {
        if (chapters.isEmpty()) return emptySet()
        val novelsById = chapters.map { it.novelId }.distinct()
            .mapNotNull { id -> novelRepo.getById(id)?.let { id to it } }
            .toMap()
        // Group by novel so the cache resolves each novel's download folder once, not per chapter.
        return chapters
            .groupBy { it.novelId }
            .flatMapTo(HashSet()) { (novelId, chs) ->
                novelsById[novelId]?.let { novelDownloadCache.downloadedChapterIds(it, chs) }.orEmpty()
            }
    }

    /** Single-source view: the anchor (non-merged or its own chip) or a selected sibling, with that
     *  novel's own per-page lazy list. Auto-fetch only runs for the anchor (its [source] is resolved);
     *  a selected sibling shows what's stored until a refresh-all fills it. */
    private suspend fun observeSingleChapters(anchor: Novel, selected: Long?, idx: Int) {
        val isAnchorView = selected == null || selected == anchor.id
        val viewNovel = if (isAnchorView) anchor else (novelRepo.getById(selected!!) ?: anchor)
        val pages = computePages(viewNovel)
        if (pages.isNotEmpty() && idx >= pages.size) {
            pageIndex.value = 0
            return
        }
        val pageKey = pages.getOrNull(idx)
        val chapterFlow = if (pageKey == null) {
            chapterRepo.getByNovelIdAsFlow(viewNovel.id)
        } else {
            chapterRepo.getByNovelIdAndPageAsFlow(viewNovel.id, pageKey)
        }
        // Fold the download cache's change signal in so a download/delete rebuilds the list.
        combine(chapterFlow, novelDownloadCache.changes) { chapters, _ -> chapters }.collectLatest { chapters ->
            rebuildLoaded(anchor, viewNovel, chapters, pages, idx, downloadedIdsFor(chapters))
            if (chapters.isEmpty() && isAnchorView) {
                if (pageKey == null) maybeFirstFetch(viewNovel) else maybeFetchPage(viewNovel, pageKey)
            }
        }
    }

    /** Page keys for the selector: "1".."N" for a `parsePage` source, distinct volume labels for a
     *  label-grouped one, or empty (single unpaged list, no selector). */
    private suspend fun computePages(novel: Novel): List<String> = when {
        novel.totalPages > 1L -> (1..novel.totalPages).map { it.toString() }
        else -> chapterRepo.getDistinctPages(novel.id).takeIf { it.size > 1 } ?: emptyList()
    }

    /** Build [NovelDetailsState.Loaded] from the [anchor] (identity, favorite, chapter-view flags) and
     *  the [viewNovel] whose metadata + source the header shows (== anchor for the unified view, the
     *  selected sibling otherwise). Sort/filter always follow the anchor's flags. */
    private fun rebuildLoaded(
        anchor: Novel,
        viewNovel: Novel,
        chapters: List<NovelChapter>,
        pages: List<String>,
        pageIndex: Int,
        downloadedChapterIds: Set<Long>,
        readInOtherSources: Set<Long> = emptySet(),
    ) {
        val hidden = hiddenChaptersPref.get()
        val view = resolveHiddenChapterView(chapters, hidden, showHiddenFlow.value, ::hiddenKey)
        val hasHiddenChapters = view.hasHidden
        val showHidden = view.showHidden
        // Hidden chapters are always excluded from the resume target (and downloads); showing hidden only
        // reveals them (dimmed) in the list so they can be unhidden.
        val nonHidden = if (hidden.isEmpty()) chapters else chapters.filterNot { hiddenKey(it) in hidden }
        val display = view.visible.sortedAndFiltered(anchor, novelPreferences, downloadedChapterIds)
        val sortDescending = anchor.effectiveSortDescending(novelPreferences)
        // The header total is the sum of the gaps the list itself would mark, so the two can never
        // disagree: counting the pooled numbers instead claimed gaps between chapters of different
        // sources, which count differently. Always shown when > 0; the inline rows are pref-gated.
        val missingChapterCount = novelMissingChapterCount(display, sortDescending)
        val chapterListEntries = if (novelPreferences.hideMissingChapters().get()) {
            display.map { NovelChapterListEntry.Item(it) }
        } else {
            buildNovelChapterListEntries(display, sortDescending)
        }
        val resume = nonHidden.sortedBy { it.sourceOrder }
            .firstOrNull { !it.read && it.id !in readInOtherSources }
        // When showing hidden, mark which displayed rows are hidden (dimmed + drives Hide/Unhide).
        val hiddenChapterIds = hiddenChapterIdsIn(display, hidden, showHidden, ::hiddenKey) { it.id }
        val viewSource = siblingSources.value[viewNovel.id]
        state.update { prev ->
            val loaded = prev as? NovelDetailsState.Loaded
            NovelDetailsState.Loaded(
                novel = anchor,
                displayNovel = viewNovel,
                chapters = display,
                chapterListEntries = chapterListEntries,
                missingChapterCount = missingChapterCount,
                showHidden = showHidden,
                hiddenChapterIds = hiddenChapterIds,
                hasHiddenChapters = hasHiddenChapters,
                pages = pages,
                pageIndex = if (pages.isEmpty()) 0 else pageIndex.coerceIn(0, pages.lastIndex),
                isPageLoading = loaded?.isPageLoading ?: false,
                isRefreshing = loaded?.isRefreshing ?: false,
                downloadStates = loaded?.downloadStates.orEmpty(),
                downloadedChapterIds = downloadedChapterIds,
                readInOtherSources = readInOtherSources,
                trackingCount = currentTrackingCount,
                customInfo = currentCustomInfo,
                dialog = loaded?.dialog,
                selection = retainChapterSelection(chapters),
                resumeChapter = resume,
                hasStarted = chapters.any { it.read || it.id in readInOtherSources },
                seedColor = loaded?.seedColor,
                sourceName = viewSource?.name ?: source?.name ?: loaded?.sourceName ?: sourceId,
                sourceUrl = viewSource?.site ?: source?.site ?: loaded?.sourceUrl,
                novelWebUrl = (viewSource ?: source)?.webUrl(viewNovel.url) ?: loaded?.novelWebUrl,
                sorting = anchor.effectiveSorting(novelPreferences),
                sortDescending = sortDescending,
                readFilter = anchor.effectiveReadFilter(novelPreferences),
                bookmarkedFilter = anchor.effectiveBookmarkedFilter(novelPreferences),
                downloadedFilter = anchor.effectiveDownloadedFilter(novelPreferences),
                hideChapterTitles = anchor.effectiveHideChapterTitles(novelPreferences),
                mergeSources = mergeGroup.chips.value,
                selectedSourceNovelId = mergeGroup.selectedSource,
                // Match manga's swipe mapping (MangaViewModel): the start/end action fields cross
                // the swipeToEnd/swipeToStart prefs, so a right-swipe reads the same on both content types.
                chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get(),
                chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get(),
            )
        }
        updateSeedColor(viewNovel)
    }

    /** Extract the cover's vibrant color once and seed the header tint. */
    private fun updateSeedColor(novel: Novel) {
        if (seedExtracted || !uiPreferences.themeCoverBased.get()) return
        val url = novel.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return
        if (novel.id <= 0L) return
        seedExtracted = true
        val cover = MangaCover(
            mangaId = EntryId.Novel(novel.id).vibrantColorKey(),
            sourceId = 0L,
            isMangaFavorite = false,
            url = url,
            lastModified = novel.coverLastModified,
        )
        cover.vibrantCoverColor?.let { color ->
            state.update { (it as? NovelDetailsState.Loaded)?.copy(seedColor = Color(color)) ?: it }
            return
        }
        viewModelScope.launchIO {
            // Load through NovelCoverFetcher (it sends the site Referer some LN cover hosts require) so a
            // non-library novel opened from browsing still tints on first open. Mirrors the manga re-extract.
            val request = ImageRequest.Builder(context)
                .data(
                    NovelCover(
                        url = url,
                        site = source?.site,
                        isNovelFavorite = novel.favorite,
                        lastModified = novel.coverLastModified,
                        novelId = novel.id,
                    ),
                )
                .allowHardware(false) // Palette can't read hardware bitmaps
                .build()
            val bitmap = context.imageLoader.execute(request).image
                ?.asDrawable(context.resources)
                ?.getBitmapOrNull() ?: return@launchIO
            val color = Palette.from(bitmap).generate().getBestColor() ?: return@launchIO
            cover.vibrantCoverColor = color
            state.update { (it as? NovelDetailsState.Loaded)?.copy(seedColor = Color(color)) ?: it }
        }
    }

    private fun maybeFirstFetch(existing: Novel?) {
        if (firstFetchTried) return
        val src = source ?: return // defer until resolveSource sets it
        firstFetchTried = true
        viewModelScope.launchIO {
            runCatching { fetchAndSync(src, existing) }.onFailure { e ->
                if (state.value !is NovelDetailsState.Loaded) {
                    state.value = NovelDetailsState.Failed(e.message ?: "Failed to load novel")
                }
            }
        }
    }

    /** parseNovel + persist metadata (edit-lock + blank safe) + sync the first page's chapters. The
     *  reactive flow then re-emits the updated novel/chapter list. A novel opened from Browse is
     *  inserted non-favorite. Returns the persisted novel (carries the refreshed `totalPages`). */
    private suspend fun fetchAndSync(src: NovelSource, existing: Novel?): Novel? {
        val sourceNovel = src.parseNovel(existing?.url ?: novelUrl)
        val target = if (existing != null) {
            val parsed = sourceNovel.toNovel(sourceId = src.id, favorite = existing.favorite)
            val merged = mergeRefreshedNovel(existing, parsed)
            if (merged != existing) novelRepo.update(merged)
            merged
        } else {
            // Non-favorite shadow row so a browse-opened novel is viewable without being silently
            // added; insertOrGet reuses a concurrently-created row instead of duplicating.
            novelRepo.insertOrGet(sourceNovel.toNovel(sourceId = src.id, favorite = false)) ?: return null
        }
        val chapters = sourceNovel.chapters.orEmpty()
        if (chapters.isNotEmpty()) {
            // A paged source's first page is page "1"; tag it so the page-"1" query finds these rows.
            val pageTag = if (sourceNovel.totalPages > 1) "1" else null
            syncChaptersWithNovelSource(
                chapters,
                target,
                chapterRepo,
                novelRepo,
                database,
                page = pageTag,
                novelDownloadManager = downloadManager,
            )
        }
        return target
    }

    /** Lazily fetch a paged source's page when it has no stored rows yet. Skipped while a filter is
     *  active (0 rows may just mean the filter hid them, not that the page is unfetched) and once a
     *  page has been tried (an empty page must not re-fetch on every emission). */
    private fun maybeFetchPage(novel: Novel, pageKey: String) {
        val src = source ?: return
        val loaded = state.value as? NovelDetailsState.Loaded
        if (loaded != null &&
            (loaded.readFilter != 0L || loaded.bookmarkedFilter != 0L || loaded.downloadedFilter != 0L)
        ) {
            return
        }
        if (!triedPages.add(pageKey)) return
        viewModelScope.launchIO {
            state.update { (it as? NovelDetailsState.Loaded)?.copy(isPageLoading = true) ?: it }
            try {
                src.parsePage(novel.url, pageKey)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(
                        it,
                        novel,
                        chapterRepo,
                        novelRepo,
                        database,
                        page = pageKey,
                        novelDownloadManager = downloadManager,
                    )
                }
            } catch (_: Throwable) {
            } finally {
                state.update { (it as? NovelDetailsState.Loaded)?.copy(isPageLoading = false) ?: it }
            }
        }
    }

    fun selectPage(index: Int) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        if (index < 0 || index >= loaded.pages.size || index == loaded.pageIndex) return
        clearSelection()
        pageIndex.value = index
        dismissDialog()
    }

    fun showPageSelectorDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.PageSelector) }

    // Shared split / remove / reorder actions. Novels write favorite-only (so the merge-undo keeps the
    // original dateAdded) and propagate tracker links onto each member before a split. selectSource +
    // showManageSourcesDialog stay below: their bodies genuinely diverge.
    private val mergeActions = EntryMergeActionHost(
        scope = viewModelScope,
        snackbarHostState = snackbarHostState,
        context = context,
        group = mergeGroup,
        anchorId = { anchorNovelId },
        mergeManager = mergeManager,
        dismissDialog = ::dismissDialog,
        setFavorite = { ids, favorite -> ids.forEach { updateNovel.await(NovelUpdate(id = it, favorite = favorite)) } },
    )

    /** Switch the chapter view between the unified list (null) and a single grouped source's list. */
    fun selectSource(novelId: Long?) {
        if (mergeGroup.selectedSource == novelId) return
        clearSelection()
        pageIndex.value = 0
        mergeGroup.selectSource(novelId)
    }

    /** Header source label: the localized unified ("All") label for the merged all-view, else the source
     *  name. Resolved here (the model has the context) so the neutral-state mapping needs no composable. */
    fun headerSourceName(loaded: NovelDetailsState.Loaded): String =
        if (loaded.mergeSources.size > 1 && loaded.selectedSourceNovelId == null) {
            context.stringResource(MR.strings.merge_unified)
        } else {
            loaded.sourceName
        }

    fun showManageSourcesDialog() {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            if (loaded.mergeSources.size <= 1) return@launchIO
            // Per-source chapters, resolved on open for both the coverage-hint counts and the trunk rank.
            val chaptersByNovel = loaded.mergeSources.associate { it.id to chapterRepo.getByNovelId(it.id) }
            val withCounts = loaded.mergeSources.associateBy({ it.id }) {
                EntryManageSourceInfo(it.id, it.sourceName, chaptersByNovel[it.id]?.size ?: 0)
            }
            // Order the rows by the same ranking aggregation uses, so the primary source opens on top even
            // under the global order (no override). memberRanking non-empty == override on.
            val memberRanking = mergeManager.overrideRankingMemberIds(anchorNovelId)
            val siblings = siblingSources.value
            val sourceIdByNovel = chaptersByNovel.keys.associateWith { siblings[it]?.id.orEmpty() }
            val ranked = NovelChapterAggregation.rankedMemberIds(
                chaptersByNovel,
                sourceIdByNovel,
                reikaiLibraryPreferences.preferredNovelSources.get(),
                memberRanking,
            )
            val orderedSources = ranked.mapNotNull { withCounts[it] }
            updateLoaded {
                it.copy(dialog = NovelDetailsDialog.ManageSources(orderedSources, memberRanking.isNotEmpty()))
            }
        }
    }

    fun reorderSources(orderedIds: List<Long>) = mergeActions.reorderSources(orderedIds)

    fun resetSourceOrder() = mergeActions.resetSourceOrder()

    fun splitSources(targetIds: List<Long>) = mergeActions.splitSources(targetIds)

    fun removeSourcesFromLibrary(targetIds: List<Long>) = mergeActions.removeSourcesFromLibrary(targetIds)

    fun removeAllSourcesFromLibrary() = mergeActions.removeAllSourcesFromLibrary()

    /**
     * Renumber sourceOrder over the list the aggregation stitched, so a "by source order" sort does
     * not interleave sources. The list arrives in reading order and keeps it: sorting by chapter
     * number here was the interleave, because a number is whatever its own source counted and two
     * sources of one novel routinely disagree. Copies; each source's own order is untouched.
     */
    private fun restampReadingOrder(chapters: List<NovelChapter>): List<NovelChapter> =
        chapters.mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }

    /** Stale-then-fresh: the cached list stays under a spinner while the sync runs, then the flow
     *  swaps in fresh rows. Read/bookmark are preserved by the sync. Deduped against concurrent runs.
     *
     *  For a paged source: [refreshNovelFromSource] refreshes page 1 + the page count and re-fetches
     *  the previously-last page through any newly-opened ones, and the page the user is viewing is
     *  re-fetched too if the walk didn't already cover it. Bounded, never a full fetch-all. */
    fun refresh() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val anchorSrc = source ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launchIO {
            state.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = true) ?: it }
            try {
                // Refresh the anchor first (its refreshed novel drives the viewed-page fix below), then
                // every other grouped source so the unified list picks up new chapters everywhere.
                val anchorUpdated = refreshNovel(anchorSrc, loaded.novel)
                for (id in mergeGroup.relatedIds) {
                    if (id == loaded.novel.id) continue
                    val novel = novelRepo.getById(id) ?: continue
                    val src = siblingSources.value[id] ?: continue
                    refreshNovel(src, novel)
                }
                // Force-refresh the viewed page when viewing the anchor's own (paged) list and the walk
                // skipped it (a middle page); the unified view has no pages so this is a no-op there.
                if (loaded.selectedSourceNovelId == null || loaded.selectedSourceNovelId == loaded.novel.id) {
                    forceRefreshViewedPage(loaded, anchorUpdated, anchorSrc)
                }
            } finally {
                state.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = false) ?: it }
            }
        }
    }

    /** Shared favorite-refresh: parseNovel + merge + sync page 1 + walk newly-opened pages. Bounded,
     *  never a full fetch-all. Keeps the current novel on failure; returns the refreshed novel. */
    private suspend fun refreshNovel(src: NovelSource, novel: Novel): Novel =
        runCatching {
            refreshNovelFromSource(novel, src, chapterRepo, novelRepo, database, novelDownloadManager = downloadManager)
        }.getOrNull()
            ?: novel

    private suspend fun forceRefreshViewedPage(loaded: NovelDetailsState.Loaded, updated: Novel, src: NovelSource) {
        val newTotalPages = updated.totalPages
        if (newTotalPages <= 1L) return
        val walkFrom = maxOf(2L, loaded.novel.totalPages)
        val curPage = loaded.pages.getOrNull(loaded.pageIndex)?.toLongOrNull() ?: return
        if (curPage > 1L && curPage !in walkFrom..newTotalPages) {
            val key = curPage.toString()
            runCatching {
                src.parsePage(updated.url, key)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(
                        it,
                        updated,
                        chapterRepo,
                        novelRepo,
                        database,
                        page = key,
                        novelDownloadManager = downloadManager,
                    )
                }
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            if (!novel.favorite) {
                // Warn on a similarly-named library novel before adding (mirrors MangaViewModel).
                novelLibraryAdder.findDuplicates(novel.id, novel.title)?.let { dup ->
                    val groupIdByNovelId = mergeManager.groupIdsFor(dup.duplicates.map { it.novel.id })
                    updateLoaded {
                        it.copy(
                            dialog = NovelDetailsDialog.DuplicateNovel(
                                dup.duplicates,
                                dup.sourceLabels,
                                dup.sourceSites,
                                mergeManager.suggestGroupingOnAdd,
                                groupIdByNovelId,
                            ),
                        )
                    }
                    return@launchIO
                }
                addToLibrary(novel)
            } else {
                // Its own copy of the group's shared tracker, before it leaves: the hand-out skips
                // non-favorites, so after the write it would miss exactly this entry.
                mergeManager.handOutTrackersBeforeRemoval(listOf(novel.id))
                updateNovel.awaitUpdateFavorite(novel.id, favorite = false)
            }
        }
    }

    /** Proceed with the add after the possible-duplicate dialog's "Add anyway". */
    fun addFavoriteAnyway() {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            addToLibrary(novel)
        }
    }

    /**
     * Add-time grouping. Only the picks the user chose: the duplicate list is fuzzy, so merging every
     * match would fuse distinct series. The atomic pair lives in NovelLibraryAdder.addToGroup; null
     * means it wrote nothing. The group's categories win, so the picker opens only for a group that
     * has none, matching every other add path.
     */
    fun addToExistingGroup(selectedIds: List<Long>) {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            val seeded = novelLibraryAdder.addToGroup(novel.id, selectedIds) ?: return@launchIO
            if (seeded) return@launchIO
            novelLibraryAdder.applyDefaultCategoryOrPrompt(novel.id)?.let { selection ->
                updateLoaded { it.copy(dialog = NovelDetailsDialog.ChangeCategory(selection)) }
            }
        }
    }

    private suspend fun addToLibrary(novel: Novel) {
        // The shared add sequence: decide, favorite, file, and abandon the whole add if the favorite
        // write fails. A picker defers both writes to applyCategories, so backing out adds nothing.
        val outcome = addEntry(
            resolveCategories = { novelLibraryAdder.resolveDefaultCategories() },
            favorite = { novelLibraryAdder.favoriteForAdd(novel.id) },
            fileCategories = { id, categoryIds -> setNovelCategories.await(id, categoryIds) },
        )
        if (outcome == AddOutcome.NeedsCategoryChoice) showChangeCategoryDialog()
    }

    fun showChangeCategoryDialog() {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            // Order the picker by the category sort-order pref, matching the library and its pickers.
            val categories = reikaiSortCategories(
                categories = getNovelCategories.await().filterNot { it.isSystemCategory },
                sortOrder = reikaiLibraryPreferences.categorySortOrder.get(),
                isSystem = { it.isSystemCategory },
                displayName = { it.name },
            )
            // No early return on an empty list: the shared picker answers that case with the prompt to
            // go and make one, where bailing here left the action doing nothing at all.
            val current = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.toSet()
            val selection = categories.mapAsCheckboxState { it.id in current }
            updateLoaded { it.copy(dialog = NovelDetailsDialog.ChangeCategory(selection)) }
        }
    }

    /**
     * The picker's confirm. It owes the favorite when the add deferred it here, and the same dialog
     * also serves an in-library novel changing its categories, which [NovelLibraryAdder.favoriteForAdd]
     * leaves alone rather than re-writing.
     */
    fun applyCategories(categoryIds: List<Long>) {
        viewModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            finishAdd(
                categoryIds = categoryIds,
                favorite = { novelLibraryAdder.favoriteForAdd(novel.id) },
                fileCategories = { id, ids -> setNovelCategories.await(id, ids) },
            )
            dismissDialog()
        }
    }

    fun showEditNovelInfoDialog() {
        updateLoaded { it.copy(dialog = NovelDetailsDialog.EditInfo) }
    }

    /** Apply Edit-info as a non-destructive overlay: store a value only when it differs from the source
     *  row (a blank field, or an Unknown status, stores nothing, so that field tracks the source again).
     *  The novels row is never touched, so Reset restores the source cleanly. Takes the neutral
     *  [EntryEditInfoUi] (as the manga side already does) and runs non-cancellable, so a mid-write screen
     *  close does not drop the edit (mirrors MangaViewModel.saveMangaInfo). */
    fun saveNovelInfo(edited: EntryEditInfoUi) {
        val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return
        viewModelScope.launchNonCancellable {
            setCustomNovelInfo.set(edited.toCustomNovelInfo(n))
        }
        dismissDialog()
    }

    /** Clear every override; the source row shows through again (no re-fetch needed, it was never overwritten). */
    fun resetNovelInfo() {
        viewModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            setCustomNovelInfo.set(CustomNovelInfo(novelId = n.id))
            // A cover set from the picker is a cached file, not a row field, so clearing the row
            // alone leaves it in place and winning (NovelCoverKeyer).
            coverCache.deleteCustomCover(EntryId.Novel(n.id))
            updateNovel.awaitUpdateCoverLastModified(n.id)
            dismissDialog()
        }
    }

    /** Bound trackers eligible for "Fill from tracker", spanning the merge group (mirrors RefreshNovelTracks). */
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>> {
        val novelId = (state.value as? NovelDetailsState.Loaded)?.novel?.id ?: return emptyList()
        return buildTrackerAutofillCandidates(getNovelTracks.awaitGroup(novelId).map { it.toUiTrack() }, trackerManager)
    }

    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata =
        tracker.getMangaMetadata(track)

    fun setSortOrder(sort: Long, descending: Boolean) =
        withLoadedNovel { setNovelChapterFlags.awaitSetSortOrder(it, sort, descending) }

    fun setFilters(read: Long, bookmarked: Long, downloaded: Long) =
        withLoadedNovel { setNovelChapterFlags.awaitSetFilters(it, read, bookmarked, downloaded) }

    fun setHideChapterTitles(hide: Boolean) =
        withLoadedNovel { setNovelChapterFlags.awaitSetHideTitles(it, hide) }

    /** Write the current view as the global chapter-settings default and drop this novel's overrides. */
    fun setChapterSettingsAsDefault() {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            novelPreferences.defaultChapterSortOrder().set(loaded.sorting)
            novelPreferences.defaultChapterSortDescending().set(loaded.sortDescending)
            novelPreferences.defaultChapterHideTitles().set(loaded.hideChapterTitles)
            novelPreferences.defaultChapterFilterUnread().set(loaded.readFilter)
            novelPreferences.defaultChapterFilterBookmarked().set(loaded.bookmarkedFilter)
            novelPreferences.defaultChapterFilterDownloaded().set(loaded.downloadedFilter)
            setNovelChapterFlags.awaitClearLocalOverrides(loaded.novel)
        }
    }

    fun resetChapterSettings() =
        withLoadedNovel { setNovelChapterFlags.awaitClearLocalOverrides(it) }

    private fun withLoadedNovel(block: suspend (Novel) -> Unit) {
        viewModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            block(n)
        }
    }

    fun showChapterSettingsDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.ChapterSettings) }

    fun showCoverDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.FullCover) }

    /** A rebuilt chapter list drops whatever it no longer holds, the range anchor included. */
    private fun retainChapterSelection(chapters: List<NovelChapter>): Set<Long> {
        chapterSelection = EntrySelection.retain(chapterSelection, chapters.map { it.id })
        return chapterSelection.selection
    }

    fun toggleSelection(chapterId: Long, fromLongPress: Boolean) = updateLoaded { loaded ->
        chapterSelection = if (fromLongPress) {
            EntrySelection.rangeOrToggle(chapterSelection, chapterId, loaded.chapters.map { it.id })
        } else {
            EntrySelection.toggle(chapterSelection, chapterId)
        }
        loaded.copy(selection = chapterSelection.selection)
    }

    fun selectAll() = updateLoaded { loaded ->
        chapterSelection = EntrySelection.selectAll(chapterSelection, loaded.chapters.map { it.id })
        loaded.copy(selection = chapterSelection.selection)
    }

    fun invertSelection() = updateLoaded { loaded ->
        chapterSelection = EntrySelection.invert(chapterSelection, loaded.chapters.map { it.id })
        loaded.copy(selection = chapterSelection.selection)
    }

    fun clearSelection() {
        chapterSelection = EntrySelection.clear()
        updateLoaded { it.copy(selection = chapterSelection.selection) }
    }

    /** Restore-stable hidden-chapter key: source + chapter url, no local novel id (so it survives a
     *  backup restore). Source resolved per the chapter's own novelId for a merged group, else the
     *  anchor's source. */
    private fun hiddenKey(chapter: NovelChapter): String =
        "${siblingSources.value[chapter.novelId]?.id ?: sourceId}|${chapter.url}"

    fun hideSelected() = withSelection { chapters ->
        hiddenChaptersPref.set(hiddenChaptersPref.get() + chapters.map { hiddenKey(it) })
    }

    fun unhideSelected() = withSelection { chapters ->
        val keys = chapters.mapTo(HashSet()) { hiddenKey(it) }
        hiddenChaptersPref.set(hiddenChaptersPref.get().filterNotTo(HashSet()) { it in keys })
    }

    fun toggleShowHidden() {
        showHiddenFlow.value = !showHiddenFlow.value
    }

    fun markSelectedRead(read: Boolean) = withSelection { chapters ->
        val expanded = expandToGroup(chapters)
        setNovelReadStatus.await(read, expanded)
        if (read) autoTrackOnMarkRead(expanded)
    }

    fun bookmarkSelected(bookmark: Boolean) = withSelection { chapters ->
        expandToGroup(chapters).forEach { chapterRepo.setBookmark(it.id, bookmark) }
    }

    /** Mark every chapter before the earliest selected one (in source order) read/unread. Spans all
     *  fetched pages (operates on stored rows), not just the page on screen. */
    fun markPreviousRead(read: Boolean) {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            // In the unified ("All") view the selection can be a sibling-source chapter that the anchor's
            // own rows don't contain, so operate over the pooled display list (which spans every grouped
            // source, unpaginated). A single source (or a selected chip) uses its own stored rows, which
            // span all fetched pages, not just what's on screen. expandToGroup folds across the group.
            val unifiedView = loaded.mergeSources.size > 1 && loaded.selectedSourceNovelId == null
            val ascending = if (unifiedView) {
                loaded.chapters.sortedBy { it.sourceOrder }
            } else {
                chapterRepo.getByNovelId(loaded.displayNovel.id).sortedBy { it.sourceOrder }
            }
            val earliest = ascending.indexOfFirst { it.id in loaded.selection }
            if (earliest > 0) {
                val previous = expandToGroup(ascending.subList(0, earliest))
                setNovelReadStatus.await(read, previous)
                if (read) autoTrackOnMarkRead(previous)
            }
            clearSelection()
        }
    }

    fun markAllRead(read: Boolean) {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val all = expandToGroup(chapterRepo.getByNovelId(loaded.displayNovel.id))
            setNovelReadStatus.await(read, all)
            if (read) autoTrackOnMarkRead(all)
        }
    }

    fun toggleChapterBookmark(chapter: NovelChapter) {
        viewModelScope.launchIO {
            val target = !chapter.bookmark
            expandToGroup(listOf(chapter)).forEach { chapterRepo.setBookmark(it.id, target) }
        }
    }

    fun markChapterRead(chapter: NovelChapter, read: Boolean) {
        viewModelScope.launchIO {
            val expanded = expandToGroup(listOf(chapter))
            setNovelReadStatus.await(read, expanded)
            if (read) autoTrackOnMarkRead(expanded)
        }
    }

    /** Expand [chapters] to every grouped source's copy of the same merged chapters, so read /
     *  bookmark applies across the whole group. No-op when not merged. Mirrors
     *  MangaViewModel.expandToGroup, off the same stored stitch. */
    private suspend fun expandToGroup(chapters: List<NovelChapter>): List<NovelChapter> {
        val ids = mergeGroup.relatedIds
        if (ids.size <= 1) return chapters
        val held = chapters.mapTo(HashSet()) { it.id }
        val wanted = expandToUnits(held, mergedChapterProvider.stitchOf(ids.first())) - held
        if (wanted.isEmpty()) return chapters
        return chapters + ids.flatMap { chapterRepo.getByNovelId(it) }.filter { it.id in wanted }
    }

    /** True if any tracker is logged in; gates the toolbar action (sheet vs Settings > Tracking). */
    fun hasLoggedInTrackers(): Boolean = trackerManager.loggedInTrackers().isNotEmpty()

    fun showTrackDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.TrackSheet) }

    /**
     * Hook 2: after chapters are marked read from the details list, push progress to bound trackers.
     * The step itself is [EntryAutoTrackOnMarkRead], shared with the manga details model.
     */
    private fun autoTrackOnMarkRead(chapters: List<NovelChapter>) {
        val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return
        viewModelScope.launchIO {
            autoTrackOnMarkRead.await(novel.id, chapters.map { it.chapterNumber })
        }
    }

    private val autoTrackOnMarkRead = EntryAutoTrackOnMarkRead(
        context = context,
        snackbarHostState = snackbarHostState,
        trackerManager = trackerManager,
        trackPreferences = trackPreferences,
        refresh = { refreshNovelTracks.await(it) },
        lastReadPerTracker = { getNovelTracks.awaitGroup(it).map(NovelTrack::lastChapterRead) },
        pushProgress = { id, chapterNumber -> trackNovelChapter.await(context, id, chapterNumber) },
    )

    /** Row swipe, dispatched by the configured [LibraryPreferences.ChapterSwipeAction] (mirrors the
     *  manga path's `executeChapterSwipeAction`, with the same download-state to action mapping). */
    fun chapterSwipe(chapter: NovelChapter, action: LibraryPreferences.ChapterSwipeAction) {
        when (action) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> markChapterRead(chapter, !chapter.read)
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> toggleChapterBookmark(chapter)
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val loaded = state.value as? NovelDetailsState.Loaded
                val downloadState = loaded?.downloadStateOf(chapter.id) ?: Download.State.NOT_DOWNLOADED
                val downloadAction = when (downloadState) {
                    Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                onChapterDownloadAction(chapter, downloadAction)
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> {}
        }
    }

    private inline fun withSelection(crossinline block: suspend (List<NovelChapter>) -> Unit) {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val chapters = loaded.chapters.filter { it.id in loaded.selection }
            if (chapters.isNotEmpty()) block(chapters)
            clearSelection()
        }
    }

    fun onChapterDownloadAction(chapter: NovelChapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.START -> {
                downloadManager.downloadChapters(listOf(chapter))
                promptAddToLibraryOnFirstDownload()
            }
            ChapterDownloadAction.START_NOW -> {
                downloadManager.downloadChapters(listOf(chapter))
                downloadManager.startDownloadNow(chapter.id)
                promptAddToLibraryOnFirstDownload()
            }
            ChapterDownloadAction.CANCEL -> downloadManager.cancelDownloads(listOf(chapter.id))
            ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(listOf(chapter))
        }
    }

    fun downloadSelected() = withSelection {
        downloadManager.downloadChapters(it)
        promptAddToLibraryOnFirstDownload()
    }

    /** After the first download of a not-yet-favorited novel (typically opened from browse), offer to
     *  add it to the library, once per screen. Mirrors MangaViewModel.startDownload's prompt. */
    private fun promptAddToLibraryOnFirstDownload() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        if (loaded.novel.favorite || loaded.hasPromptedToAddBefore) return
        updateLoaded { it.copy(hasPromptedToAddBefore = true) }
        viewModelScope.launchIO {
            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.snack_add_to_library),
                actionLabel = context.stringResource(MR.strings.action_add),
                withDismissAction = true,
            )
            val stillNotFavorite = (state.value as? NovelDetailsState.Loaded)?.novel?.favorite == false
            if (result == SnackbarResult.ActionPerformed && stillNotFavorite) toggleFavorite()
        }
    }

    /** Toolbar download dropdown. Operates on the full stored chapter list (all fetched pages), not the
     *  page on screen, the same way [markAllRead] does. Selection logic is shared with the library. */
    fun runDownloadAction(action: DownloadAction) {
        viewModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            // The rows on screen, not the anchor's own: on a merged entry the All chip lists whichever
            // source won the dedup, so downloading the anchor's chapters fetched ones the user was not
            // looking at and left every visible row undownloaded. The manga side already works this way.
            val hidden = hiddenChaptersPref.get()
            val available = loaded.chapters.filterNot { hiddenKey(it) in hidden }
            // Already resolved per owning novel, which a merged list needs.
            val downloadedIds = loaded.downloadedChapterIds
            val queuedIds = downloadManager.queueState.value.mapTo(HashSet()) { it.chapterId }
            val targets = selectChaptersForDownloadAction(available, action, downloadedIds + queuedIds)
            if (targets.isNotEmpty()) {
                downloadManager.downloadChapters(targets)
                promptAddToLibraryOnFirstDownload()
            }
        }
    }

    /** Confirm before bulk-deleting the selected downloads (parity with manga); confirm calls
     *  [deleteChapters]. */
    fun deleteSelected() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val chapters = loaded.chapters.filter { it.id in loaded.selection }
        if (chapters.isNotEmpty()) updateLoaded { it.copy(dialog = NovelDetailsDialog.DeleteChapters(chapters)) }
    }

    fun deleteChapters(chapters: List<NovelChapter>) {
        viewModelScope.launchIO { downloadManager.deleteChapters(chapters) }
        clearSelection()
        dismissDialog()
    }

    fun dismissDialog() = updateLoaded { it.copy(dialog = null) }

    /** Raise the migrate dialog for the duplicate the user picked, onto this novel. Both rows already
     *  exist, so there is nothing to materialize first. */
    fun startMigrate(duplicateId: Long) = updateLoaded {
        it.copy(dialog = NovelDetailsDialog.Migrate(currentId = duplicateId, targetId = it.novel.id))
    }

    private inline fun updateLoaded(crossinline transform: (NovelDetailsState.Loaded) -> NovelDetailsState.Loaded) {
        state.update { (it as? NovelDetailsState.Loaded)?.let(transform) ?: it }
    }
}

/**
 * Per-field override, store a value only when it differs from the current source value; a blank field
 * (or "Unknown" status) stores nothing, so that field tracks the source again. The novel twin of
 * MangaViewModel's EntryEditInfoUi.toCustomMangaInfo (blanks preserve the source, drop empty genres).
 */
private fun EntryEditInfoUi.toCustomNovelInfo(source: Novel) = CustomNovelInfo(
    novelId = source.id,
    title = title.trim().takeIf { it.isNotEmpty() && it != source.title },
    author = author.trim().takeIf { it.isNotEmpty() && it != source.author.orEmpty() },
    artist = artist.trim().takeIf { it.isNotEmpty() && it != source.artist.orEmpty() },
    description = description.takeIf { it.isNotBlank() && it != source.description.orEmpty() },
    genre = genre.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() && it != source.genre.orEmpty() },
    status = status.takeIf { it != source.status && it != NovelStatusCode.UNKNOWN.toLong() },
    thumbnailUrl = thumbnailUrl.trim().takeIf { it.isNotEmpty() && it != source.thumbnailUrl.orEmpty() },
)

sealed interface NovelDetailsState {
    data object Loading : NovelDetailsState
    data class Failed(val message: String) : NovelDetailsState

    @Immutable
    data class Loaded(
        /** Identity + favorite key. */
        val novel: Novel,
        /** Metadata shown in the header. Equals [novel] for a single source; the merge seam
         *  repoints it at the selected source. */
        val displayNovel: Novel,
        val chapters: List<NovelChapter>,
        /** The rendered chapter list: chapters interleaved with "N missing chapters" separators.
         *  When the hide-missing pref is on, holds only the chapters. */
        val chapterListEntries: List<NovelChapterListEntry> = emptyList(),
        /** Total missing chapters across the whole visible list; drives the header warning (> 0). */
        val missingChapterCount: Int = 0,
        /** True while hidden chapters are temporarily shown (dimmed). */
        val showHidden: Boolean = false,
        /** Ids of the displayed rows that are hidden (only non-empty when [showHidden]); drives dimming
         *  and whether the selection offers Hide vs Unhide. */
        val hiddenChapterIds: Set<Long> = emptySet(),
        /** Any chapter in this novel is hidden; gates the "Show hidden chapters" toolbar toggle. */
        val hasHiddenChapters: Boolean = false,
        /** Page keys for the selector; empty when the source is single/unpaged (selector hidden). */
        val pages: List<String> = emptyList(),
        val pageIndex: Int = 0,
        val isPageLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        /** Live download-queue states by chapter id (queued/downloading/error only; a finished
         *  download is read from [downloadedChapterIds]). */
        val downloadStates: Map<Long, Download.State> = emptyMap(),
        /** Chapter ids downloaded on disk, from NovelDownloadCache (replaces the old is_downloaded flag).
         *  A finished download shows DOWNLOADED via membership here, not a queue state. */
        val downloadedChapterIds: Set<Long> = emptySet(),
        /** Chapters unread on their own row but already read on another source of the merge group. */
        val readInOtherSources: Set<Long> = emptySet(),
        /** Bound trackers on a logged-in service; drives the details action-row Tracking button. */
        val trackingCount: Int = 0,
        /** Non-destructive edit-info overlay; the display applies it over [displayNovel] (the raw novel
         *  stays source-accurate). Null when the novel has no edits. */
        val customInfo: CustomNovelInfo? = null,
        val dialog: NovelDetailsDialog? = null,
        val selection: Set<Long> = emptySet(),
        val resumeChapter: NovelChapter? = null,
        val hasStarted: Boolean = false,
        /** True once the first-download "add to library?" prompt has shown this session, so a
         *  non-favorite novel is asked only once (mirrors manga's hasPromptedToAddBefore). */
        val hasPromptedToAddBefore: Boolean = false,
        /** Cover-derived header tint; null when off or not yet extracted. */
        val seedColor: Color? = null,
        /** Resolved source name + homepage. [sourceUrl] is the source SITE (used as the cover-load
         *  Referer); [novelWebUrl] is this novel's own page (site + path), for WebView and Share. */
        val sourceName: String = "",
        val sourceUrl: String? = null,
        val novelWebUrl: String? = null,
        // Resolved (per-novel or global-default) chapter view settings.
        val sorting: Long = NovelChapterFlags.SORTING_SOURCE,
        val sortDescending: Boolean = true,
        val readFilter: Long = 0L,
        val bookmarkedFilter: Long = 0L,
        val downloadedFilter: Long = 0L,
        val hideChapterTitles: Boolean = false,
        /** Source-switcher chips for a merged group (empty/single = not merged, chips hidden). */
        val mergeSources: List<EntryMergeSource> = emptyList(),
        /** The selected source chip's novelId; null = the unified ("All") view. */
        val selectedSourceNovelId: Long? = null,
        /** Chapter swipe actions, read from the shared (manga) library prefs so novels match manga. */
        val chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction =
            LibraryPreferences.ChapterSwipeAction.Disabled,
        val chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction =
            LibraryPreferences.ChapterSwipeAction.Disabled,
    ) : NovelDetailsState {
        val selectionMode: Boolean get() = selection.isNotEmpty()

        /** A chapter's download state: a live queue state if present, else DOWNLOADED / NOT_DOWNLOADED
         *  from the on-disk cache. */
        fun downloadStateOf(chapterId: Long): Download.State =
            downloadStates[chapterId]
                ?: if (chapterId in downloadedChapterIds) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED
    }
}

sealed interface NovelDetailsDialog {
    data class ChangeCategory(
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : NovelDetailsDialog

    data object EditInfo : NovelDetailsDialog

    data class DuplicateNovel(
        val duplicates: List<NovelWithChapterCount>,
        val sourceLabels: Map<String, EntrySourceLabel>,
        val sourceSites: Map<String, String?>,
        /** Whether to offer add-time grouping (the same-title suggestion pref plus the master switch). */
        val suggestGroup: Boolean,
        /** Novel id -> group id, so same-group duplicates collapse into one card. */
        val groupIdByNovelId: Map<Long, Long>,
    ) : NovelDetailsDialog

    data class DeleteChapters(val chapters: List<NovelChapter>) : NovelDetailsDialog

    data object ChapterSettings : NovelDetailsDialog
    data object PageSelector : NovelDetailsDialog
    data object FullCover : NovelDetailsDialog
    data class ManageSources(
        val sources: List<EntryManageSourceInfo>,
        val isOverridden: Boolean,
    ) : NovelDetailsDialog

    // Rendered as a NavigatorAdaptiveSheet, mirroring Mihon's manga sheet.
    data object TrackSheet : NovelDetailsDialog

    /** Migrating the library's copy onto this one, both already stored by id. Replaces
     *  [DuplicateNovel] in the same slot, as the manga twin does. */
    data class Migrate(val currentId: Long, val targetId: Long) : NovelDetailsDialog
}
