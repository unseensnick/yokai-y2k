package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.fastAny
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
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.GetPagePreviews
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.PagePreview
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.source.ExhPreferences
import exh.source.getMainSource
import exh.source.isEhBasedManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.domain.category.resolveDefaultCategoryIds
import reikai.domain.entry.EntryId
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.manga.downloadedChapterIds
import reikai.domain.merge.ChapterGap
import reikai.domain.merge.expandToUnits
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.recommendation.BuildRecommendationHideFilter
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RecommendationHideFilter
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.domain.recommendation.RelatedMangasLoader
import reikai.domain.recommendation.RelatedPlacement
import reikai.domain.recommendation.taste.GetTasteProfile
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.domain.recommendation.taste.TasteProfile
import reikai.domain.track.supportingContent
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.addEntry
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.browse.finishAdd
import reikai.presentation.components.pageProgressLabel
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
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import kotlin.math.floor

// RK: max related candidates shown in the details carousel; the full pool is kept in the cache for
// the "See all" browse grid.
private const val CAROUSEL_CAP = 30

@AssistedInject
class MangaViewModel(
    private val context: Context,
    @Assisted private val mangaId: Long,
    @Assisted private val isFromSource: Boolean,
    private val libraryPreferences: LibraryPreferences,
    trackPreferences: TrackPreferences,
    readerPreferences: ReaderPreferences,
    private val trackerManager: TrackerManager,
    private val trackChapter: TrackChapter,
    private val refreshTracks: RefreshTracks,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val getMangaAndChapters: GetMangaWithChapters,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val updateManga: UpdateManga,
    // RK: "Reset all" clears the cached custom cover too, not just the custom-info row.
    private val coverCache: CoverCache,
    private val getCategories: GetCategories,
    // RK: orders the change-category picker by the category sort-order pref, like the library.
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    // RK --> a tracker bound on one source of a merged series counts for the whole group, so every read
    // here goes through GetTracksInGroup instead of Mihon's per-manga GetTracks.
    private val getTracksInGroup: GetTracksInGroup,
    // RK <--
    private val addTracks: AddTracks,
    private val setMangaCategories: SetMangaCategories,
    private val mangaRepository: MangaRepository,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    // RK -->
    private val mergeManager: MangaMergeManager,
    private val mangaLibraryAdder: MangaLibraryAdder,
    private val mergedChapterProvider: MergedChapterProvider,
    private val mangaPreferences: MangaPreferences,
    private val relatedMangasLoader: RelatedMangasLoader,
    private val recommendationPreferences: ReikaiRecommendationPreferences,
    private val relatedMangaCache: RelatedMangaCache,
    private val getTasteProfile: GetTasteProfile,
    private val refreshTrackerLibrary: RefreshTrackerLibrary,
    private val buildRecommendationHideFilter: BuildRecommendationHideFilter,
    private val getFavorites: GetFavorites,
    private val networkToLocalManga: NetworkToLocalManga,
    private val uiPreferences: UiPreferences,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val getPagePreviews: GetPagePreviews,
    // RK: manga custom-info overlay. getCustomMangaInfo drives the non-destructive display overlay;
    // setCustomMangaInfo persists edits from the shared edit-info dialog.
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val sourceManager: SourceManager,
    private val exhPreferences: ExhPreferences,
    // RK <--
) : ViewModel() {

    val snackbarHostState = SnackbarHostState()

    val state: StateFlow<State>
        field = MutableStateFlow<State>(State.Loading)

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, isFromSource: Boolean): MangaViewModel
    }

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()

    // RK: unread by us since the mark-read tracker push moved into EntryAutoTrackOnMarkRead, which reads
    // the preference itself. Kept as upstream wrote it so the next sync of this file stays a clean merge.
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    // RK: chapter multi-select, shared with novels through the kernel. It replaces upstream's
    // first/last index window, which could disagree with the selection it described.
    private var chapterSelection = SelectionState<Long>()

    // RK --> shared merge read/observe wiring: the group ids (just this manga when ungrouped), the selected
    // source chip, the membership observer, and the switcher chips. Written once in EntryMergeGroupHost so a
    // manga/novel drift like the old missing-refresh bug can't recur; the novel model composes the same host.
    // Manga's anchor is constant, so anchorChanges is just membershipChanges re-emitting mangaId; source
    // resolution is the synchronous getOrStub in buildMergeSources.
    private val mergeGroup = EntryMergeGroupHost(
        mergeManager = mergeManager,
        initialIds = longArrayOf(mangaId),
        anchorChanges = mergeManager.membershipChanges().map { mangaId },
        resolveSources = { ids -> buildMergeSources(ids) },
    )

    // Hide/unhide chapters (twin of the novel mechanism). The pref is the persisted/backed-up set of
    // hidden chapter keys; showHiddenFlow is the transient "temporarily reveal hidden chapters" toggle.
    private val hiddenChaptersPref = mangaPreferences.hiddenChapters()
    private val showHiddenFlow = MutableStateFlow(false)
    // RK <--

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        state.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    // RK --> cover-based theming (Y11)
    val themeCoverBased = uiPreferences.themeCoverBased.get()

    // RK: recommendations are enabled but placed in the three-dot menu, so the screen shows a
    // "Recommendations" overflow action instead of the inline carousel. Read once, like themeCoverBased.
    val recommendationsInMenu = recommendationPreferences.enableRelatedMangas.get() &&
        recommendationPreferences.relatedPlacement.get() == RelatedPlacement.MENU

    /**
     * Seed the details theme from the cover's vibrant color. Reuses the color a prior Library/Browse
     * load already cached; otherwise loads the cover through Coil and extracts it, so a non-library
     * manga opened straight from browsing still tints on first open (mirrors Komikku setPaletteColor).
     */
    fun updateSeedColor() {
        // Computed regardless of the themeCoverBased pref: the page only applies it when the pref is on
        // (MangaScreen), but the shared edit-info dialog always tints from the cover, so the seed must be
        // available either way.
        val cover = manga?.asMangaCover() ?: return
        cover.vibrantCoverColor?.let { color ->
            updateSuccessState { it.copy(seedColor = Color(color)) }
            return
        }
        viewModelScope.launchIO {
            val request = ImageRequest.Builder(context)
                .data(cover)
                .allowHardware(false) // Palette can't read hardware bitmaps
                .build()
            val bitmap = context.imageLoader.execute(request).image
                ?.asDrawable(context.resources)
                ?.getBitmapOrNull() ?: return@launchIO
            val color = Palette.from(bitmap).generate().getBestColor() ?: return@launchIO
            cover.vibrantCoverColor = color
            updateSuccessState { it.copy(seedColor = Color(color)) }
        }
    }
    // RK <--

    init {
        viewModelScope.launchIO {
            // RK --> when the manga is part of a merge group, the chapter list is the aggregated
            // union of every grouped source; otherwise it stays the single-source list.
            combine(
                combine(
                    getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true).distinctUntilChanged(),
                    mergeGroup.state,
                    downloadCache.changes,
                    downloadManager.queueState,
                ) { mangaAndChapters, group, _, _ ->
                    ChapterInputs(mangaAndChapters.first, mangaAndChapters.second, group.ids, group.selected)
                },
                // Re-emit so a hide/unhide or the show-hidden toggle rebuilds the chapter list.
                hiddenChaptersPref.changes(),
                showHiddenFlow,
            ) { inputs, _, _ -> inputs }
                .flatMapLatest { (manga, ownChapters, relatedIds, selectedSource) ->
                    when {
                        selectedSource != null && relatedIds.size > 1 ->
                            singleSourceChaptersFlow(manga, selectedSource, relatedIds)
                        relatedIds.size <= 1 ->
                            flowOf(
                                MergedChapters(
                                    manga = manga,
                                    chapters = ownChapters,
                                    mangaBySource = emptyMap(),
                                    downloadedChapterIds = downloadedIdsOf(ownChapters, emptyMap(), manga),
                                ),
                            )
                        else ->
                            mergedChaptersFlow(manga, relatedIds)
                    }
                }
                .collectLatest { mc ->
                    val items = mc.chapters.toChapterListItems(
                        mc.manga,
                        mc.mangaBySource,
                        mc.readInOtherSources,
                        mc.bookmarkedInOtherSources,
                        mc.downloadedInOtherSources,
                        mc.downloadedChapterIds,
                    )
                    val hidden = applyHiddenChapters(items, mc.manga, mc.mangaBySource)
                    updateSuccessState {
                        it.copy(
                            manga = mc.manga,
                            chapters = hidden.chapters,
                            showHidden = hidden.showHidden,
                            hasHiddenChapters = hidden.hasHiddenChapters,
                            hiddenChapterIds = hidden.hiddenChapterIds,
                            mergedMangaById = mc.mangaBySource,
                            mergeDisplayManga = mc.displayManga,
                            mergeDisplaySource = mc.displaySource,
                        )
                    }
                }
            // RK <--
        }

        viewModelScope.launchIO {
            getExcludedScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators)
                    }
                }
        }

        viewModelScope.launchIO {
            getAvailableScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators)
                    }
                }
        }

        // RK: mirror the manga's custom-info overlay into state; a save re-emits and the display layer
        // re-applies it via Manga.withCustomInfo (the raw `manga` field stays source-accurate).
        viewModelScope.launchIO {
            getCustomMangaInfo.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { customInfo ->
                    updateSuccessState { it.copy(customInfo = customInfo) }
                }
        }

        observeDownloads()

        // RK --> start the shared merge read wiring (membership -> relatedIds -> chips), then mirror the
        // host's chips + selection into state. The eager load below seeds the initial chips into
        // State.Success; the host handles every later change (a split, or a source added to the group from
        // global search) with no reopening, and the observer lives in the host so it can't drift per type.
        mergeGroup.observe(viewModelScope)
        viewModelScope.launchIO {
            mergeGroup.chips.collectLatest { chips ->
                updateSuccessState { it.copy(mergeSources = chips) }
            }
        }
        viewModelScope.launchIO {
            mergeGroup.selectedSourceChanges.collectLatest { selected ->
                updateSuccessState { it.copy(selectedSourceMangaId = selected) }
            }
        }
        // Reactively load the active source's gallery metadata (primary when unified) so the tag
        // chips + info box stay in sync. It matters on a first open: the source fetch stores the
        // metadata AFTER State.Success is built, and this upgrades the flat view to the rich one
        // without needing to back out and re-enter. Also refreshes on a source-chip switch or when
        // a gallery-update rewrites the metadata.
        viewModelScope.launchIO {
            mergeGroup.selectedSourceChanges
                .flatMapLatest { selected ->
                    val targetId = selected ?: mangaId
                    getFlatMetadataById.subscribe(targetId).map { flat -> targetId to flat }
                }
                .collectLatest { (targetId, flat) ->
                    updateSuccessState { it.copy(galleryMetadata = raiseMetadata(flat, targetId)) }
                }
        }
        // RK <--

        viewModelScope.launchIO {
            val manga = getMangaAndChapters.awaitManga(mangaId)
            // RK --> resolve the merge group so the combined chapter list builds on open
            val mergeChips = mergeGroup.seed(mangaId)
            // RK <--
            val ownChapters = getMangaAndChapters.awaitChapters(mangaId, applyScanlatorFilter = true)
            val chapterItems = ownChapters.toChapterListItems(
                manga,
                downloadedChapterIds = downloadedIdsOf(ownChapters, emptyMap(), manga),
            )
            // RK: seed the hidden-chapters filter on first render so hidden chapters never flash in.
            val hidden = applyHiddenChapters(chapterItems, manga, emptyMap())

            if (!manga.favorite) {
                setMangaDefaultChapterFlags.await(manga)
            }

            val needRefreshInfo = !manga.initialized
            val needRefreshChapter = chapterItems.isEmpty()

            // Show what we have earlier
            // RK: seed the primary source's gallery metadata too; same first-render race as the chips.
            val galleryMetadata = loadGalleryMetadata(mangaId)
            val source = sourceManager.getOrStub(manga.source)
            // RK: kick off the page-preview fetch for supporting sources before building state.
            val supportsPagePreview = source.getMainSource<PagePreviewSource>() != null
            if (supportsPagePreview) {
                getPagePreviews(manga, source)
            }
            state.update {
                State.Success(
                    manga = manga,
                    source = source,
                    isFromSource = isFromSource,
                    chapters = hidden.chapters,
                    // RK: hide/unhide chapters seed
                    showHidden = hidden.showHidden,
                    hasHiddenChapters = hidden.hasHiddenChapters,
                    hiddenChapterIds = hidden.hiddenChapterIds,
                    availableScanlators = getAvailableScanlators.await(mangaId),
                    excludedScanlators = getExcludedScanlators.await(mangaId),
                    isRefreshingData = needRefreshInfo || needRefreshChapter,
                    dialog = null,
                    hideMissingChapters = libraryPreferences.hideMissingChapters.get(),
                    // RK: seed the merge chips so they show on first render (avoids a race where the
                    // chip collector fired before State.Success existed)
                    mergeSources = mergeChips,
                    galleryMetadata = galleryMetadata,
                    // RK: page-preview thumbnails + row count (0 = off) for adult sources.
                    pagePreviewsState = if (supportsPagePreview) {
                        PagePreviewState.Loading
                    } else {
                        PagePreviewState.Unused
                    },
                    previewsRowCount = uiPreferences.previewsRowCount.get(),
                    // RK: seed the custom-info overlay so it shows on first render (before the reactive
                    // collector fires), same pattern as the scanlator seeds above.
                    customInfo = getCustomMangaInfo.subscribe(mangaId).first(),
                )
            }

            // Start observe tracking since it only needs mangaId
            observeTrackers()

            // Fetch info-chapters when needed
            if ((needRefreshInfo || needRefreshChapter) && viewModelScope.isActive) {
                fetchAllFromSource(
                    manualFetch = false,
                    fetchDetails = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                )
            }

            // Initial loading finished
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    // RK --> load the first page of gallery page previews for sources that support it.
    private fun getPagePreviews(manga: Manga, source: Source) {
        viewModelScope.launchIO {
            when (val result = getPagePreviews.await(manga, source, 1)) {
                is GetPagePreviews.Result.Error -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Error(result.error))
                }
                is GetPagePreviews.Result.Success -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Success(result.pagePreviews))
                }
                GetPagePreviews.Result.Unused -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Unused)
                }
            }
        }
    }
    // RK <--

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            fetchAllFromSource(
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    private suspend fun fetchAllFromSource(
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        val state = successState ?: return
        // RK: refresh every source in a merged group, not just the primary. A source merged in via
        //     long-press "add from another source" never fetched at add time, so without this its
        //     chip stays stale on refresh; each member goes through its own source's fetch (the same
        //     path Browse uses), populating details, chapters and gallery metadata. Just the primary
        //     when not merged, so non-grouped entries behave exactly as before.
        val groupIds = mergeGroup.relatedIds
        try {
            withUIContext {
                val newChapters = mutableListOf<Chapter>()
                var firstError: Exception? = null
                for (id in groupIds) {
                    val result = if (id == state.manga.id) {
                        updateMangaFromRemote(
                            source = state.source,
                            manga = state.manga,
                            fetchDetails = fetchDetails,
                            fetchChapters = fetchChapters,
                            manualFetch = manualFetch,
                        )
                    } else {
                        updateMangaFromRemote(
                            manga = getMangaAndChapters.awaitManga(id),
                            fetchDetails = fetchDetails,
                            fetchChapters = fetchChapters,
                            manualFetch = manualFetch,
                        )
                    }
                    result.fold(
                        onSuccess = { newChapters += it.newChapters },
                        onFailure = { if (firstError == null && it is Exception) firstError = it },
                    )
                }

                if (manualFetch) {
                    downloadNewChapters(newChapters)
                }
                firstError?.let { throw it }
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        // RK: removing a favorited E-Hentai gallery with backup enabled goes through a confirm
        //     dialog (DeletableTracker-style), so the user can opt to also remove it from the account.
        val manga = successState?.manga
        if (manga != null && isFavorited && shouldConfirmEhRemoveFromAccount(manga)) {
            updateSuccessState { it.copy(dialog = Dialog.EhRemoveFavorite(manga)) }
            return
        }
        toggleFavorite(onRemoved = ::promptDeleteDownloadsOnRemoved)
    }

    // RK: extracted so the E-Hentai "remove from account" confirm can reuse the same downloads prompt.
    private fun promptDeleteDownloadsOnRemoved() {
        viewModelScope.launch {
            if (!hasDownloads()) return@launch
            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.delete_downloads_for_manga),
                actionLabel = context.stringResource(MR.strings.action_delete),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                deleteDownloads()
            }
        }
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                // RK: hand this entry its own copy of the group's shared tracker before it goes; the
                //     hand-out skips non-favorites, so after the write it would miss exactly this one.
                mergeManager.handOutTrackersBeforeRemoval(listOf(manga.id))
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers(coverCache) != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        val groupIdByMangaId = mergeManager.groupIdsFor(duplicates.map { it.manga.id })
                        updateSuccessState {
                            it.copy(
                                dialog = Dialog.DuplicateManga(
                                    manga,
                                    duplicates,
                                    mergeManager.suggestGroupingOnAdd,
                                    groupIdByMangaId,
                                    mangaLibraryAdder.duplicateSourceLabels(duplicates),
                                ),
                            )
                        }
                        return@launchIO
                    }
                }

                // RK: the shared add sequence, so no add path can drift from the others: decide,
                // favorite, file, and abandon the whole add if the favorite write fails.
                val outcome = addEntry(
                    resolveCategories = {
                        resolveDefaultCategoryIds(getCategories(), libraryPreferences.defaultCategory.get())
                    },
                    favorite = { manga.id.takeIf { updateManga.awaitUpdateFavorite(manga.id, true) } },
                    fileCategories = { id, categoryIds -> setMangaCategories.await(id, categoryIds) },
                )
                when (outcome) {
                    AddOutcome.Failed -> return@launchIO
                    AddOutcome.NeedsCategoryChoice -> showChangeCategoryDialog()
                    AddOutcome.Added -> {}
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(manga, state.source)
                // RK: back up newly-favorited E-Hentai galleries to the account.
                maybeBackupFavoriteToAccount(manga)
            }
        }
    }

    // RK: add-time grouping. Only the picks the user chose: the duplicate list is fuzzy, so merging
    // every match would fuse distinct series. The favorite-and-merge pair and the reason it has to be
    // atomic live in MangaLibraryAdder.addToGroup; null means it wrote nothing.
    fun addToExistingGroup(selectedIds: List<Long>) {
        val state = successState ?: return
        val manga = state.manga
        viewModelScope.launchIO {
            val seeded = mangaLibraryAdder.addToGroup(manga, selectedIds) ?: return@launchIO
            addTracks.bindEnhancedTrackers(manga, state.source)
            maybeBackupFavoriteToAccount(manga)

            // The group's categories win: only fall back to the default (or the picker) when the group
            // is uncategorized, so the new source lands where the rest of the series lives.
            if (!seeded) {
                val directIds = resolveDefaultCategoryIds(getCategories(), libraryPreferences.defaultCategory.get())
                if (directIds != null) moveMangaToCategory(directIds) else showChangeCategoryDialog()
            }
        }
    }

    // RK -->

    private fun shouldConfirmEhRemoveFromAccount(manga: Manga): Boolean {
        return manga.isEhBasedManga() &&
            exhPreferences.enableExhentai().get() &&
            exhPreferences.exhBackupFavoritesToAccount().get()
    }

    fun confirmEhRemoveFromLibrary(removeFromAccount: Boolean) {
        val manga = successState?.manga
        dismissDialog()
        if (manga == null) return
        toggleFavorite(onRemoved = ::promptDeleteDownloadsOnRemoved)
        if (removeFromAccount) {
            viewModelScope.launchIO { removeFromEhAccount(manga) }
        }
    }

    private suspend fun removeFromEhAccount(manga: Manga) {
        val source = sourceManager.get(manga.source) as? EHentai ?: return
        runCatching {
            source.removeFavorites(listOf(EHentaiSearchMetadata.galleryId(manga.url)))
        }.onFailure { logcat(LogPriority.ERROR, it) { "Failed to remove E-Hentai favorite remotely" } }
    }

    // NOTE: if the user favorites via the category picker and then cancels it, this still pushes
    //       (the picker commits the favorite later in moveMangaToCategoriesAndAddToLibrary).
    //       Benign: the account is the disposable backstop, so a stray entry is the safe direction.
    private suspend fun maybeBackupFavoriteToAccount(manga: Manga) {
        if (!manga.isEhBasedManga() ||
            !exhPreferences.enableExhentai().get() ||
            !exhPreferences.exhBackupFavoritesToAccount().get()
        ) {
            return
        }
        val source = sourceManager.get(manga.source) as? EHentai ?: return
        runCatching {
            source.addFavorite(
                EHentaiSearchMetadata.galleryId(manga.url),
                EHentaiSearchMetadata.galleryToken(manga.url),
                exhPreferences.exhFavoritesBackupSlot().get(),
            )
        }.onFailure { logcat(LogPriority.ERROR, it) { "Failed to back up E-Hentai favorite to account" } }
    }
    // RK <--

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            // RK: order the picker by the category sort-order pref, matching the library and its pickers.
            val categories = reikaiSortCategories(getCategories(), reikaiLibraryPreferences.categorySortOrder.get())
            val selection = getMangaCategoryIds(manga)
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection },
                    ),
                )
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = mangaRepository.getMangaById(manga.id)
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private suspend fun hasDownloads(): Boolean {
        // RK: every grouped source, since the screen offers one Delete downloads for the whole entry.
        return groupManga().any { downloadManager.getDownloadCount(it) > 0 }
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private suspend fun deleteDownloads() {
        // RK: as above, so the action clears what the merged entry actually holds.
        groupManga().forEach { downloadManager.deleteManga(it, sourceManager.getOrStub(it.source)) }
    }

    /** RK: every source of the merge group, the screen's own manga when it stands alone. Resolved from
     *  the group rather than the screen's map, which a selected source chip narrows to that one chip. */
    private suspend fun groupManga(): List<Manga> {
        val ids = mergeGroup.relatedIds
        val state = successState ?: return emptyList()
        return if (ids.size <= 1) listOf(state.manga) else ids.map { getMangaAndChapters.awaitManga(it) }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    // RK: the picker's confirm owes both writes the add deferred, in the shared order, so backing out
    // of the picker adds nothing and a failed favorite leaves no categories behind.
    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        viewModelScope.launchIO {
            finishAdd(
                categoryIds = categories,
                favorite = { manga.id.takeIf { manga.favorite || updateManga.awaitUpdateFavorite(manga.id, true) } },
                fileCategories = { id, categoryIds -> setMangaCategories.await(id, categoryIds) },
            )
        }
    }

    // RK: upstream's Category and Category? overloads went with the add paths that needed them; the
    // shared default-category rule already hands back the id list this takes.
    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() {
        viewModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        viewModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    private fun List<Chapter>.toChapterListItems(
        manga: Manga,
        // RK: for merged groups, each chapter's own source-manga, so download status resolves
        // against the source it actually came from (key: mangaId). Empty for non-merged manga.
        mangaBySource: Map<Long, Manga> = emptyMap(),
        // RK: chapters another grouped source has already read (see MergedChapters.readInOtherSources).
        readInOtherSources: Set<Long> = emptySet(),
        // RK: the same, for bookmarked and for downloaded. A merged chapter is downloaded when any of
        // the group's copies holds the file, so the row is not offered a download the group already has.
        bookmarkedInOtherSources: Set<Long> = emptySet(),
        downloadedInOtherSources: Set<Long> = emptySet(),
        // RK: ids whose own copy is on disk, resolved by the caller over every copy it holds. Probing
        // here instead would repeat, per row, a probe the cross-source pass has already paid for.
        downloadedChapterIds: Set<Long> = emptySet(),
    ): List<ChapterList.Item> {
        return map { chapter ->
            val owner = mangaBySource[chapter.mangaId] ?: manga
            val activeDownload = if (owner.isLocal()) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = chapter.id in downloadedChapterIds || chapter.id in downloadedInOtherSources
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapter.id in chapterSelection, // RK: was selectedChapterIds
                readInAnotherSource = chapter.id in readInOtherSources,
                bookmarkedInAnotherSource = chapter.id in bookmarkedInOtherSources,
            )
        }
    }

    // RK -->

    /** Combine inputs for the chapter flow. */
    private data class ChapterInputs(
        val manga: Manga,
        val ownChapters: List<Chapter>,
        val relatedIds: LongArray,
        val selectedSource: Long?,
    )

    /** Display payload for the chapter flow: the screen manga, the (possibly merged) chapter list,
     *  and the per-source manga for merged groups (empty when not merged). */
    private data class MergedChapters(
        val manga: Manga,
        val chapters: List<Chapter>,
        val mangaBySource: Map<Long, Manga>,
        // RK: ids of chapters whose own row is unread but which another grouped source has read. Empty
        // when unmerged or when a single source chip is selected (there is no other source in view).
        val readInOtherSources: Set<Long> = emptySet(),
        // RK: the same set for the bookmark flag and for the file on disk, plus the ids whose own copy
        // is on disk, resolved once here because the cross-source pass has to probe them anyway.
        val bookmarkedInOtherSources: Set<Long> = emptySet(),
        val downloadedInOtherSources: Set<Long> = emptySet(),
        val downloadedChapterIds: Set<Long> = emptySet(),
        // RK: per-source metadata shown in the info box when a source chip is active (null = unified).
        // Kept separate from [manga] so favorite / tracking / chapter-flag actions stay on the primary.
        val displayManga: Manga? = null,
        val displaySource: Source? = null,
    )

    /** Chapters of a single grouped source (chip selection), keyed for download by its own manga.
     *  Subscribes to the whole group even though it only shows one source's chapters, so a chapter
     *  read on a sibling still reads as read here: "have I read this chapter" is a property of the
     *  story, not of the source's own row, and the All view would otherwise disagree with the chip. */
    private suspend fun singleSourceChaptersFlow(
        displayManga: Manga,
        sourceMangaId: Long,
        relatedIds: LongArray,
    ): Flow<MergedChapters> {
        val sourceManager = sourceManager
        val perSibling = relatedIds.map { id ->
            getMangaAndChapters.subscribe(id, applyScanlatorFilter = true)
                .map { (manga, chapters) -> Triple(id, manga, chapters) }
        }
        return combine(perSibling) { siblings ->
            val chaptersBySource = siblings.associate { (id, _, chapters) -> id to chapters }
            val sourceManga = siblings.first { (id, _, _) -> id == sourceMangaId }.second
            val ownChapters = chaptersBySource[sourceMangaId].orEmpty()
            val pooled = chaptersBySource.values.flatten()
            val mangaBySource = siblings.associate { (id, manga, _) -> id to manga }
            val stitch = mergedChapterProvider.stitchOf(sourceMangaId)
            val downloadedIds = downloadedIdsOf(pooled, mangaBySource, sourceManga)
            MergedChapters(
                manga = displayManga,
                chapters = ownChapters,
                mangaBySource = mapOf(sourceManga.id to sourceManga),
                displayManga = sourceManga,
                displaySource = sourceManager.getOrStub(sourceManga.source),
                // The chip shows one source, but a chapter read on a sibling still reads as read.
                readInOtherSources = flaggedOnAnotherSource(pooled, ownChapters, stitch, { it.id }, { it.read }),
                bookmarkedInOtherSources = flaggedOnAnotherSource(
                    pooled,
                    ownChapters,
                    stitch,
                    { it.id },
                    { it.bookmark },
                ),
                downloadedInOtherSources = flaggedOnAnotherSource(pooled, ownChapters, stitch, { it.id }) {
                    it.id in downloadedIds
                },
                downloadedChapterIds = downloadedIds,
            )
        }
    }

    /** Resolved once per emission over every copy, since the same probe answers three questions here. */
    private fun downloadedIdsOf(
        chapters: List<Chapter>,
        mangaBySource: Map<Long, Manga>,
        fallback: Manga,
    ): Set<Long> = downloadManager.downloadedChapterIds(chapters) { mangaBySource[it.mangaId] ?: fallback }

    /** Expand [chapters] to every grouped source's copy of the same merged chapters, so read /
     *  bookmark applies across the whole group. No-op when not merged.
     *
     *  Reads the stored stitch rather than matching chapter numbers, which two sources of one series
     *  count differently: comparing them reached a chapter several along on the sibling source. */
    private suspend fun expandToGroup(chapters: List<Chapter>): List<Chapter> {
        val ids = mergeGroup.relatedIds
        if (ids.size <= 1) return chapters
        val held = chapters.mapTo(HashSet()) { it.id }
        val wanted = expandToUnits(held, mergedChapterProvider.stitchOf(mangaId)) - held
        if (wanted.isEmpty()) return chapters
        return chapters + ids.flatMap { getMangaAndChapters.awaitChapters(it) }.filter { it.id in wanted }
    }

    /** Raise the stored gallery metadata for a source's manga, mirroring MetadataViewScreenModel.
     *  Returns null when the source has no metadata support or nothing is stored. */
    private suspend fun loadGalleryMetadata(targetMangaId: Long): RaisedSearchMetadata? {
        return raiseMetadata(getFlatMetadataById.await(targetMangaId), targetMangaId)
    }

    /** Raise a [FlatMetadata] row into its source's typed metadata; null when the source isn't a
     *  MetadataSource or nothing was stored. Shared by the seed and the reactive metadata flow. */
    private suspend fun raiseMetadata(flatMetadata: FlatMetadata?, targetMangaId: Long): RaisedSearchMetadata? {
        if (flatMetadata == null) return null
        val targetManga = getMangaAndChapters.awaitManga(targetMangaId)
        val metadataSource = sourceManager.get(targetManga.source)
            ?.getMainSource<MetadataSource<*, *>>() ?: return null
        return flatMetadata.raise(metadataSource.metaClass)
    }

    /** Resolve the source-switcher chips for the full group (empty when not merged). */
    private suspend fun buildMergeSources(ids: LongArray): List<EntryMergeSource> {
        if (ids.size <= 1) return emptyList()
        val sourceManager = sourceManager
        return ids.map { id ->
            val sourceManga = getMangaAndChapters.awaitManga(id)
            EntryMergeSource(id, sourceManager.getOrStub(sourceManga.source).name)
        }
    }

    /** Combine every grouped source's chapters into one aggregated, deduped, reading-ordered list.
     *  Suspend because [GetMangaWithChapters.subscribe] is; called from the suspend flatMapLatest. */
    private suspend fun mergedChaptersFlow(displayManga: Manga, relatedIds: LongArray): Flow<MergedChapters> {
        val perSibling = mutableListOf<Flow<Triple<Long, Manga, List<Chapter>>>>()
        for (id in relatedIds) {
            perSibling += getMangaAndChapters.subscribe(id, applyScanlatorFilter = true)
                .map { (manga, chapters) -> Triple(id, manga, chapters) }
        }
        return combine(perSibling) { siblings ->
            val mangaBySource = siblings.associate { (id, manga, _) -> id to manga }
            val chaptersBySource = siblings.associate { (id, _, chapters) -> id to chapters }
            // Read off the stored stitch, the same rows the library badge counts.
            val pooled = chaptersBySource.values.flatten()
            val stitch = mergedChapterProvider.stitchOf(displayManga.id)
            val merged = mergedChapterProvider.merged(pooled, stitch)
            val downloadedIds = downloadedIdsOf(pooled, mangaBySource, displayManga)
            MergedChapters(
                manga = displayManga,
                chapters = merged,
                mangaBySource = mangaBySource,
                readInOtherSources = flaggedOnAnotherSource(pooled, merged, stitch, { it.id }, { it.read }),
                bookmarkedInOtherSources = flaggedOnAnotherSource(pooled, merged, stitch, { it.id }, { it.bookmark }),
                downloadedInOtherSources = flaggedOnAnotherSource(pooled, merged, stitch, { it.id }) {
                    it.id in downloadedIds
                },
                downloadedChapterIds = downloadedIds,
            )
        }
    }

    // Hide/unhide chapters (manga twin of the novel details mechanism). The hidden set is a pref of
    // restore-stable "<source>|<chapterUrl>" keys; it filters Success.chapters at assembly, so hidden
    // chapters also drop from the resume FAB and download-all (which read that list). The in-app manga
    // reader excludes them too (ReaderViewModel.chapterList), so next/prev navigation skips hidden.

    /** Restore-stable hidden-chapter key: the chapter's own source (per-source for a merged group). */
    private fun hiddenKey(chapter: Chapter, manga: Manga, mangaBySource: Map<Long, Manga>): String =
        "${(mangaBySource[chapter.mangaId] ?: manga).source}|${chapter.url}"

    private data class HiddenChapters(
        val chapters: List<ChapterList.Item>,
        val showHidden: Boolean,
        val hasHiddenChapters: Boolean,
        val hiddenChapterIds: Set<Long>,
    )

    /** Drop hidden chapters from [items] unless the user is temporarily showing them, and compute the
     *  hide-related state. "Showing hidden" only holds while hidden chapters still exist, so unhiding
     *  the last one collapses the mode instead of leaving a stale toggle. */
    private fun applyHiddenChapters(
        items: List<ChapterList.Item>,
        manga: Manga,
        mangaBySource: Map<Long, Manga>,
    ): HiddenChapters {
        val hidden = hiddenChaptersPref.get()
        val keyOf = { item: ChapterList.Item -> hiddenKey(item.chapter, manga, mangaBySource) }
        val view = resolveHiddenChapterView(items, hidden, showHiddenFlow.value, keyOf)
        val hiddenChapterIds = hiddenChapterIdsIn(view.visible, hidden, view.showHidden, keyOf) { it.id }
        return HiddenChapters(view.visible, view.showHidden, view.hasHidden, hiddenChapterIds)
    }

    fun hideSelected() {
        val state = successState ?: return
        val keys = state.chapters.filter { it.selected }
            .map { hiddenKey(it.chapter, state.manga, state.mergedMangaById) }
        if (keys.isEmpty()) return
        hiddenChaptersPref.set(hiddenChaptersPref.get() + keys)
        toggleAllSelection(false)
    }

    /** Only reachable while hidden chapters are being shown. */
    fun unhideSelected() {
        val state = successState ?: return
        val keys = state.chapters.filter { it.selected }
            .mapTo(HashSet()) { hiddenKey(it.chapter, state.manga, state.mergedMangaById) }
        if (keys.isEmpty()) return
        hiddenChaptersPref.set(hiddenChaptersPref.get().filterNotTo(HashSet()) { it in keys })
        toggleAllSelection(false)
    }

    fun toggleShowHidden() {
        showHiddenFlow.value = !showHiddenFlow.value
    }
    // RK <--

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            // RK: toggled against what the row shows, which on a merged entry is the group's state.
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapterItem.isRead)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapterItem.isBookmarked)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        // RK: never resume into a hidden chapter, even while temporarily showing hidden ones.
        return successState.chapters
            .filterNot { it.id in successState.hiddenChapterIds }
            .getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        // RK: hidden chapters are never bulk-downloaded (they are in the list only while showing hidden).
        val hidden = successState?.hiddenChapterIds.orEmpty()
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filterNot { it.id in hidden }
            // RK: the any-source flags, so a chapter a grouped source has read or holds is not queued.
            .filter { !it.isRead && it.downloadState == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val hidden = successState?.hiddenChapterIds.orEmpty()
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filterNot { it.id in hidden }
            .filter { it.isBookmarked && it.downloadState == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val successState = successState ?: return

        viewModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launchIO {
            setReadStatus.await(
                read = read,
                // RK: also mark the matching chapter in every grouped source
                chapters = expandToGroup(chapters).toTypedArray(),
            )

            if (!read) return@launchIO
            // RK: the push itself is the shared step both details models run
            autoTrackOnMarkRead.await(mangaId, chapters.map { it.chapterNumber })
        }
    }

    // RK --> shared with the novel details model, so a change to the tracker push reaches both
    private val autoTrackOnMarkRead = EntryAutoTrackOnMarkRead(
        context = context,
        snackbarHostState = snackbarHostState,
        trackerManager = trackerManager,
        trackPreferences = trackPreferences,
        refresh = { refreshTracks.await(it) },
        lastReadPerTracker = { getTracksInGroup.await(it).map(Track::lastChapterRead) },
        pushProgress = { id, chapterNumber -> trackChapter.await(context, id, chapterNumber) },
    )
    // RK <--

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private suspend fun downloadChapters(chapters: List<Chapter>) {
        val state = successState ?: return
        // RK --> in a merged group, download each chapter from its own source-manga
        chapters.groupBy { it.mangaId }.forEach { (mangaId, group) ->
            downloadManager.downloadChapters(ownerOf(mangaId, state), group)
        }
        // RK <--
        toggleAllSelection(false)
    }

    /**
     * RK: the manga a chapter belongs to. Read from the database when the screen's own map cannot
     * answer, which a source chip makes routine: it narrows that map to one source while an expanded
     * action carries the group's other copies, and resolving those to the screen's manga sent a delete
     * into the wrong source's download folder.
     */
    private suspend fun ownerOf(chapterMangaId: Long, state: State.Success): Manga =
        state.mergedMangaById[chapterMangaId]
            ?: state.manga.takeIf { it.id == chapterMangaId }
            ?: getMangaAndChapters.awaitManga(chapterMangaId)

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launchIO {
            // RK: bookmark the matching chapter in every grouped source too
            expandToGroup(chapters)
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    // RK --> in a merged group, delete each chapter's download from its own source.
                    // Expanded first: the row is downloaded when ANY copy holds the file, so deleting
                    // only the shown copy would leave the row still reading as downloaded.
                    val sourceManager = sourceManager
                    expandToGroup(chapters).groupBy { it.mangaId }.forEach { (mangaId, group) ->
                        val owner = ownerOf(mangaId, state)
                        downloadManager.deleteChapters(group, owner, sourceManager.getOrStub(owner.source))
                    }
                    // RK <--
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            val manga = successState?.manga ?: return@launchNonCancellable
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                setMangaDefaultChapterFlags.awaitAll()
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        viewModelScope.launchNonCancellable {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    // RK --> chapter selection routes through the shared kernel, so manga, novels and every other
    // multi-select surface answer a range the same way. A long press ranges from the last row you
    // touched; a tap toggles one row.
    fun toggleSelection(item: ChapterList.Item, fromLongPress: Boolean = false) {
        updateSuccessState { successState ->
            chapterSelection = if (fromLongPress) {
                EntrySelection.rangeOrToggle(chapterSelection, item.id, successState.processedChapters.map { it.id })
            } else {
                EntrySelection.toggle(chapterSelection, item.id)
            }
            successState.withChapterSelection()
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            chapterSelection = if (selected) {
                EntrySelection.selectAll(chapterSelection, successState.chapters.map { it.id })
            } else {
                EntrySelection.clear()
            }
            successState.withChapterSelection()
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            chapterSelection = EntrySelection.invert(chapterSelection, successState.chapters.map { it.id })
            successState.withChapterSelection()
        }
    }

    /** Fan the selection back out onto the rows the list renders. */
    private fun State.Success.withChapterSelection(): State.Success =
        copy(chapters = chapters.map { it.copy(selected = it.id in chapterSelection) })
    // RK <--

    // Chapters list - end

    // Track sheet - start

    private fun observeTrackers() {
        val manga = successState?.manga ?: return

        viewModelScope.launchIO {
            combine(
                getTracksInGroup.subscribe(manga.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                // RK: and catalogues manga at all, through the same kernel the sheet filters with.
                val supportedTrackers = loggedInTrackers
                    .supportingContent(isNovel = false)
                    .filter { (it as? EnhancedTracker)?.accept(source!!) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog

        // RK: suggestGroup gates the "add to existing group" action (the same-title suggestion pref);
        // groupIdByMangaId collapses same-group duplicates into one card.
        data class DuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val suggestGroup: Boolean,
            val groupIdByMangaId: Map<Long, Long>,
            val sourceLabels: Map<Long, EntrySourceLabel>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog

        // RK: manage the grouped sources (reorder / split / remove). Rows arrive trunk-first (primary on
        // top); isOverridden gates the reset action.
        data class ManageSources(
            val sources: List<EntryManageSourceInfo>,
            val isOverridden: Boolean,
        ) : Dialog

        // RK: confirm removing a favorited E-Hentai gallery, with an opt-in "also remove from account".
        data class EhRemoveFavorite(val manga: Manga) : Dialog

        // RK: shared edit-info editor; carries the raw source manga (each field is saved only when it
        // differs from these).
        data class EditMangaInfo(val manga: Manga) : Dialog
    }

    // RK: a related-carousel candidate plus whether it already resolves to a favorited library entry.
    data class RelatedMangaItem(val candidate: RelatedMangaCandidate, val inLibrary: Boolean)

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    // RK -->

    fun showEditMangaInfoDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.EditMangaInfo(manga)) }
    }

    /** Persist edits as a non-destructive per-field override against the raw source [manga]. */
    fun saveMangaInfo(manga: Manga, edited: EntryEditInfoUi) {
        viewModelScope.launchNonCancellable {
            setCustomMangaInfo.set(edited.toCustomMangaInfo(manga))
        }
        dismissDialog()
    }

    /** Clear every override, so all fields track the source again. */
    fun resetMangaInfo(manga: Manga) {
        viewModelScope.launchNonCancellable {
            setCustomMangaInfo.set(CustomMangaInfo(mangaId = manga.id))
            // RK: a cover set from the picker or the reader is a cached file, not a row field, so
            // clearing the row alone leaves it in place and winning (MangaCoverKeyer).
            coverCache.deleteCustomCover(EntryId.Manga(manga.id))
            updateManga.awaitUpdateCoverLastModified(manga.id)
        }
        dismissDialog()
    }

    /** Bound trackers eligible for "Fill from tracker" (self-hosted enhanced trackers can't autofill). */
    suspend fun autofillCandidates(): List<Pair<Track, Tracker>> =
        buildTrackerAutofillCandidates(getTracksInGroup.await(mangaId), trackerManager)

    suspend fun fetchTrackerMetadata(track: Track, tracker: Tracker): TrackMangaMetadata =
        tracker.getMangaMetadata(track)

    // RK: shared source split / remove / reorder actions (the snackbar-with-undo logic both details
    // models run). selectSource + showManageSourcesDialog stay here: their bodies genuinely diverge.
    private val mergeActions = EntryMergeActionHost(
        scope = viewModelScope,
        snackbarHostState = snackbarHostState,
        context = context,
        group = mergeGroup,
        anchorId = { mangaId },
        mergeManager = mergeManager,
        dismissDialog = ::dismissDialog,
        setFavorite = { ids, favorite -> ids.forEach { updateManga.awaitUpdateFavorite(it, favorite) } },
    )

    /** Switch the chapter list to a single grouped source, or null for the unified merged view. */
    fun selectSource(sourceMangaId: Long?) {
        mergeGroup.selectSource(sourceMangaId)
    }

    /** Header source label: the localized unified ("All") label for the merged all-view, else the active
     *  source's display name. Resolved here (the model has the context) so MangaEntryAdapter's neutral-state
     *  mapping needs no composable. Mirrors NovelDetailsViewModel.headerSourceName. */
    fun headerSourceName(state: State.Success): String =
        if (state.mergeSources.size > 1 && state.selectedSourceMangaId == null) {
            context.stringResource(MR.strings.merge_unified)
        } else {
            (state.mergeDisplaySource ?: state.source).getNameForMangaInfo()
        }

    /** The localized "Page N" resume hint for a started-but-unread chapter, else null. Resolved here (needs
     *  the context) so MangaEntryAdapter can pre-format the neutral chapter row's readProgress without a
     *  composable. The rule itself is shared with the recents row, which draws the same line. */
    fun readProgressLabel(chapter: Chapter): String? =
        chapter.takeIf { !it.read }
            ?.let { pageProgressLabel(it.lastPageRead, it.pageCount) }
            ?.let { (resource, args) -> context.stringResource(resource, *args) }

    fun showManageSourcesDialog() {
        val state = successState ?: return
        // Use the full group (stable) so the dialog works even while viewing a single source chip.
        if (state.mergeSources.size <= 1) return
        viewModelScope.launchIO {
            val ids = state.mergeSources.map { it.id }
            // Order the rows by the same ranking aggregation uses, so the primary source opens on top even
            // under the global order (no override). memberRanking non-empty == override on.
            val memberRanking = mergeManager.overrideRankingMemberIds(mangaId)
            val chaptersBySource = ids.associateWith {
                getMangaAndChapters.awaitChapters(it, applyScanlatorFilter = true)
            }
            val sourceIdByManga = ids.associateWith { getMangaAndChapters.awaitManga(it).source }
            val ranked = mergedChapterProvider.rankedMemberIds(chaptersBySource, sourceIdByManga, memberRanking)
            val orderedSources = ranked.mapNotNull { id ->
                state.mergeSources.find { it.id == id }
                    ?.let { EntryManageSourceInfo(it.id, it.sourceName, chaptersBySource[id]?.size ?: 0) }
            }
            updateSuccessState {
                it.copy(dialog = Dialog.ManageSources(orderedSources, memberRanking.isNotEmpty()))
            }
        }
    }

    fun reorderSources(orderedIds: List<Long>) = mergeActions.reorderSources(orderedIds)

    fun resetSourceOrder() = mergeActions.resetSourceOrder()

    fun splitSources(targetIds: List<Long>) = mergeActions.splitSources(targetIds)

    fun removeSourcesFromLibrary(targetIds: List<Long>) = mergeActions.removeSourcesFromLibrary(targetIds)

    fun removeAllSourcesFromLibrary() = mergeActions.removeAllSourcesFromLibrary()
    // RK <--

    // RK --> related-mangas carousel (recommendations)
    private var relatedLoadStarted = false

    /**
     * Suspend until the initial details/chapter fetch settles. Returns at once when nothing is
     * refreshing (a library entry that needed no fetch). `fetchAllFromSource` swallows its own
     * failures, so the flag always clears and this cannot stall the carousel.
     */
    private suspend fun awaitOwnDataLoaded() {
        state.first { it !is State.Success || !it.isRefreshingData }
    }

    /** Load the related carousel once per screen open. Serves a fresh cache hit instantly; otherwise
     *  streams source-native related, marking which candidates are already in the library. */
    fun loadRelatedMangas() {
        if (relatedLoadStarted) return
        // Gate before any work: the carousel self-hides on an empty pool, so an early return both
        // hides the row and spares the source every request the load would have made. In-menu placement
        // still loads (the recommendations screen reads the same cache); only the inline row is hidden.
        if (!recommendationPreferences.enableRelatedMangas.get()) return
        val state = successState ?: return
        val source = state.source as? CatalogueSource ?: return
        relatedLoadStarted = true
        // Bootstrap / refresh the taste cache out of band (never on the carousel's critical path);
        // the profile read below uses whatever is already cached, the pull lands for the next open.
        viewModelScope.launchIO { refreshTrackerLibrary.refreshIfStale() }
        viewModelScope.launchIO {
            val favorites = getFavorites.await()
            val favoriteKeys = favorites.mapTo(HashSet()) { it.url to it.source }
            // Anti-echo: opt-in filter that hides suggestions the user already has/tracks (by id, then
            // title). No-op when no filter is enabled.
            val hideFilter = buildRecommendationHideFilter.await()
            val cached = relatedMangaCache.get(state.manga.id)
            if (cached != null) {
                applyRelated(cached.fullPool, favoriteKeys, hideFilter)
                if (cached.isComplete && relatedMangaCache.isFresh(cached)) return@launchIO
            } else {
                updateSuccessState { it.copy(relatedLoading = true) }
            }
            // The entry's own details and chapters come first: both hit the same host, and a source
            // that paces its requests would otherwise spend them on suggestions while the reader is
            // still waiting for the chapter list. Flagging the load above first means the skeleton
            // holds the row's space meanwhile, so nothing shifts when the results land.
            awaitOwnDataLoaded()
            val mangaId = state.manga.id
            val pool = relatedMangasLoader.load(
                manga = state.manga.toSManga(),
                source = source,
                tracks = getTracksInGroup.await(state.manga.id),
                ranker = recommendationPreferences.buildRanker(),
                // Rerank off -> empty profile, which collapses the ranker to popularity order.
                taste = if (recommendationPreferences.enableRecommendationRerank.get()) {
                    getTasteProfile.await()
                } else {
                    TasteProfile.EMPTY
                },
                currentGenres = state.manga.genre.orEmpty(),
                onUpdate = {
                    // Cache each streamed snapshot (incomplete) so "See all" works before the load
                    // finishes; the final put below marks it complete.
                    relatedMangaCache.put(mangaId, it.take(CAROUSEL_CAP), it, isComplete = false)
                    applyRelated(it, favoriteKeys, hideFilter)
                },
            )
            relatedMangaCache.put(mangaId, pool.take(CAROUSEL_CAP), pool)
            applyRelated(pool, favoriteKeys, hideFilter)
            updateSuccessState { it.copy(relatedLoading = false) }
        }
    }

    private fun applyRelated(
        pool: List<RelatedMangaCandidate>,
        favoriteKeys: Set<Pair<String, Long>>,
        hideFilter: RecommendationHideFilter,
    ) {
        val items = pool
            .filterNot { hideFilter.shouldHide(it) }
            .map { RelatedMangaItem(it, (it.manga.url to it.sourceId) in favoriteKeys) }
        // Cap the carousel; the full pool stays in the cache for the "See all" browse grid.
        updateSuccessState { it.copy(relatedItems = items.take(CAROUSEL_CAP), relatedTotalCount = items.size) }
    }

    /** Resolve a tapped candidate to a local manga id to open, or null for a tracker-origin card
     *  (whose URL belongs to no installed source) so the caller can route it through global search. */
    suspend fun resolveRelatedToLocalId(candidate: RelatedMangaCandidate): Long? {
        if (candidate.sourceId == RECOMMENDS_SOURCE) return null
        return networkToLocalManga(candidate.manga.toDomainManga(candidate.sourceId)).id
    }
    // RK <--

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(target = manga, current = duplicate)) }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launchIO {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            // RK: per-source manga for a merged group (key: mangaId), empty when not merged. Lets
            // chapter actions (download/delete) target each chapter's own source.
            val mergedMangaById: Map<Long, Manga> = emptyMap(),
            // RK: the grouped sources for the switcher chips, and the selected one (null = unified).
            val mergeSources: List<EntryMergeSource> = emptyList(),
            val selectedSourceMangaId: Long? = null,
            // RK: per-source metadata for the info box when a chip is active (null = unified -> primary).
            val mergeDisplayManga: Manga? = null,
            val mergeDisplaySource: Source? = null,
            // RK: the active source's raised gallery metadata (adult/metadata sources), drives the
            // namespaced tag chips + gallery-info block; null when the source has no metadata.
            val galleryMetadata: RaisedSearchMetadata? = null,
            // RK: related-mangas carousel (recommendations), loaded lazily when the screen opens.
            // relatedItems is capped to CAROUSEL_CAP; relatedTotalCount is the full filtered pool size
            // behind the "See all (N)" affordance.
            val relatedItems: List<RelatedMangaItem> = emptyList(),
            val relatedTotalCount: Int = 0,
            val relatedLoading: Boolean = false,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hasPromptedToAddBefore: Boolean = false,
            val hideMissingChapters: Boolean = false,
            // RK: hide/unhide chapters. showHidden is the transient reveal toggle; hiddenChapterIds are
            // the currently-shown hidden rows (for dimming), only populated while showing hidden.
            val showHidden: Boolean = false,
            val hasHiddenChapters: Boolean = false,
            val hiddenChapterIds: Set<Long> = emptySet(),
            // RK: the manga's custom-info overlay (null = none), applied at the display layer via
            // Manga.withCustomInfo. Never folded into the raw `manga` field above, which stays
            // source-accurate for tracker search, refresh, duplicate detection, downloads, etc.
            val customInfo: CustomMangaInfo? = null,
            // RK: cover-derived theming color (Y11), null when off or not yet extracted.
            val seedColor: Color? = null,
            // RK: page-preview thumbnails (adult sources) + how many rows to show (0 = off).
            val pagePreviewsState: PagePreviewState = PagePreviewState.Unused,
            val previewsRowCount: Int = 0,
        ) : State {
            // RK -->
            // EH/EXH galleries are tags-as-content with no description, so default the info box
            // to expanded in the library too (Mihon only auto-expands when arriving from a source).
            val isMetadataSource: Boolean
                get() = source.getMainSource<MetadataSource<*, *>>() != null
            // RK <--

            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    // RK: through the shared rule, which declines a gap whose two sides come from
                    // different sources of a group, or whose number the name does not support.
                    ChapterGap
                        .between(higherChapter.chapter.toGapNeighbour(), lowerChapter?.chapter?.toGapNeighbour())
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                return asSequence()
                    // RK: the any-source flags, so the filters agree with what the rows show.
                    .filter { applyFilter(unreadFilter) { !it.isRead } }
                    .filter { applyFilter(bookmarkedFilter) { it.isBookmarked } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> getChapterSort(manga).invoke(chapter1, chapter2) }
            }
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val selected: Boolean = false,
        // RK: another grouped source's copy of this chapter is read. Kept separate from chapter.read,
        // which stays the row's own DB truth because tracker sync, delete-after-read and mark-unread all
        // act on the real row.
        val readInAnotherSource: Boolean = false,
        // RK: same, for the bookmark flag. Writes already reach every copy, so this only shows through
        // when the copies were never in sync: a bookmark set before the sources were merged, or one a
        // backup restored onto a copy the stitch does not show.
        val bookmarkedInAnotherSource: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED

        // RK: read as the user sees it. The list shows one row per chapter across the group, so a
        // chapter read on any source reads as read here, matching the library's unread count.
        val isRead = chapter.read || readInAnotherSource

        // RK: bookmarked as the user sees it, on the same any-source rule as [isRead].
        val isBookmarked = chapter.bookmark || bookmarkedInAnotherSource
    }
}

// RK: page-preview thumbnail state for the details screen (adult/EXH sources).
sealed interface PagePreviewState {
    data object Unused : PagePreviewState
    data object Loading : PagePreviewState
    data class Success(val pagePreviews: List<PagePreview>) : PagePreviewState
    data class Error(val error: Throwable) : PagePreviewState
}

/**
 * RK: per-field override, store a value only when it differs from the current source value; a blank field
 * (or "Unknown" status) stores nothing, so that field tracks the source again.
 */
private fun EntryEditInfoUi.toCustomMangaInfo(source: Manga) = CustomMangaInfo(
    mangaId = source.id,
    title = title.trim().takeIf { it.isNotEmpty() && it != source.title },
    author = author.trim().takeIf { it.isNotEmpty() && it != source.author.orEmpty() },
    artist = artist.trim().takeIf { it.isNotEmpty() && it != source.artist.orEmpty() },
    description = description.takeIf { it.isNotBlank() && it != source.description.orEmpty() },
    genre = genre.takeIf { it.isNotEmpty() && it != source.genre.orEmpty() },
    status = status.takeIf { it != source.status && it != SManga.UNKNOWN.toLong() },
    thumbnailUrl = thumbnailUrl.trim().takeIf { it.isNotEmpty() && it != source.thumbnailUrl.orEmpty() },
)

// RK: a merged list's neighbours can come from different sources, so the owning manga travels with
// the number the gap is computed from.
private fun Chapter.toGapNeighbour() = ChapterGap.Neighbour(chapterNumber, name, mangaId)
