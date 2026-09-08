package eu.kanade.tachiyomi.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import exh.search.SearchEngine
import exh.source.getMainSource
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.update
import mihon.core.common.utils.mutate
import mihon.domain.library.model.search.QueryNode
import reikai.domain.category.categoryFilterActive
import reikai.domain.category.isHidden
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.librarySortComparator
import reikai.domain.library.sortForCategory
import reikai.domain.library.toSortMode
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.presentation.library.LibraryFilterPrefs
import reikai.presentation.library.LibraryGroup
import reikai.presentation.library.MangaMergeCollapse
import reikai.presentation.library.ReikaiLibraryState
import reikai.presentation.library.chapterSearchTerms
import reikai.presentation.library.libraryFilterMatches
import reikai.presentation.library.libraryItemFilterFields
import reikai.presentation.library.libraryItemQueryFields
import reikai.presentation.library.libraryItemSortFields
import reikai.presentation.library.libraryQueryMatches
import reikai.presentation.library.libraryStateFlow
import reikai.presentation.library.mangaTrackerMeans
import reikai.presentation.library.toQueryOverlay
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.withCustomInfo
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.source.model.Source as DomainSource

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LibraryViewModel(
    private val getLibraryManga: GetLibraryManga,
    // RK: per-entry custom title/cover overrides, overlaid on the displayed rows (display-only)
    private val getCustomMangaInfo: GetCustomMangaInfo,
    // RK: resolve merged-away group members by id, so a bulk action reaches every source of a merge
    //     group and not just the collapsed primary
    private val getManga: GetManga,
    private val getCategories: GetCategories,
    // RK: gallery tags + alt-titles for the library tag-search engine
    private val getSearchTags: GetSearchTags,
    private val getSearchTitles: GetSearchTitles,
    private val getTracksPerManga: GetTracksPerManga,
    private val getNextChapters: GetNextChapters,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId,
    private val setReadStatus: SetReadStatus,
    private val updateManga: UpdateManga,
    private val setMangaCategories: SetMangaCategories,
    private val preferences: BasePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: CoverCache,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val trackerManager: TrackerManager,
    // RK -->
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val mergeManager: MangaMergeManager,
    private val mergeGroupRepository: MergeGroupRepository,
    // RK: backs the `chapter:` search term's id-set lookup.
    private val chapterRepository: ChapterRepository,
    private val mergedChapterProvider: MergedChapterProvider,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
    private val reconcileMergedChapters: ReconcileMergedChapters,
    // RK <--
) : ViewModel() {

    // RK: parses a typed query into structured tag components (cached); used by the library
    // tag-search for adult/metadata sources.
    private val searchEngine = SearchEngine()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val activeCategoryIndex = MutableStateFlow(libraryPreferences.lastUsedCategory.get())

    private val displayPreferences = combine(
        libraryPreferences.categoryTabs.changes(),
        libraryPreferences.categoryNumberOfItems.changes(),
        libraryPreferences.showContinueReadingButton.changes(),
        ::DisplayPreferences,
    )

    private val hasActiveFilters = combine(
        getLibraryItemPreferencesFlow(),
        getTrackingFiltersFlow(),
    ) { prefs, trackFilters ->
        listOf(
            prefs.filterDownloaded,
            prefs.filterUnread,
            prefs.filterStarted,
            prefs.filterBookmarked,
            prefs.filterCompleted,
            prefs.filterIntervalCustom,
            // RK --> lewd counts as an active filter dim
            prefs.filterLewd,
            // RK <--
            *trackFilters.values.toTypedArray(),
        )
            .any { it != TriState.DISABLED } ||
            // RK --> include/exclude category filter is a Boolean dim, not a TriState
            categoryFilterActive(
                prefs.filterCategories,
                prefs.filterCategoriesInclude,
                prefs.filterCategoriesExclude,
            )
        // RK <--
    }
        .distinctUntilChanged()

    // RK: upstream's second pipeline (bucket favorites into categories, then sort each) is gone, so this
    // is the whole query. The model is a row provider now: LibraryEngine assembles and sorts the list,
    // because only it sees both content types.
    private val libraryData: StateFlow<LibraryData?> =
        combine(
            // RK: the query slot carries its resolved `chapter:` id sets alongside it, so a chapter
            //     lookup runs once per query change rather than on every favorites tick (this combine
            //     is at its 5-source cap, so it rides here rather than taking a slot of its own).
            searchQuery.debounce(0.25.seconds)
                .map { query -> query to resolveChapterMatches(query) },
            getCategories.subscribe(),
            // RK: the custom-info overlay rides with favorites (combine caps at 5 sources) but is
            //     NOT applied here: search/filter/sort below all read the raw favorites. It is
            //     carried into LibraryData and applied only at the display read (see State).
            combine(getFavoritesFlow(), getCustomMangaInfo.subscribeAll(), ::Pair),
            combine(getTracksPerManga.subscribe(), getTrackingFiltersFlow(), ::Pair),
            getLibraryItemPreferencesFlow(),
        ) {
                (searchQuery, chapterMatches),
                categories,
                (favorites, customInfo),
                (tracksMap, trackingFilters),
                itemPreferences,
            ->
            val showSystemCategory = favorites.any { it.libraryManga.categories.contains(0) }
            val filteredFavorites = favorites
                .applyFilters(tracksMap, trackingFilters, itemPreferences)
                // RK: parse once, then filter through the shared query kernel, the same one the novel
                //     library runs, so one typed query means one thing on every row of the All list.
                //     A gallery entry ALSO gets the EXH tag grammar, which is a manga-only capability
                //     the AST has no equivalent for; the two are ORed rather than routed between, so a
                //     row appears if either grammar it supports matches.
                .let { items ->
                    if (searchQuery == null) {
                        items
                    } else {
                        val parsedQuery = searchEngine.parseQuery(searchQuery)
                        val queryNode = QueryNode.from(searchQuery)
                        val queryFields = mangaQueryFields(chapterMatches, customInfo)
                        // An excluded component the tag grammar cannot resolve (no such namespace,
                        // no text hit) passes vacuously, so ORing it into an exclusion-only query
                        // would resurrect every gallery row the kernel excluded. Positive queries
                        // OR (either grammar can find a row); exclusion-only queries AND (each
                        // grammar removes what it understands).
                        val hasPositive = parsedQuery.any { !it.excluded }
                        items.filter { m ->
                            val kernel = libraryQueryMatches(queryNode, m, queryFields)
                            when {
                                m.metadataSourceName == null -> kernel
                                hasPositive -> kernel || m.matchesMetadataQuery(parsedQuery)
                                else -> kernel && m.matchesMetadataQuery(parsedQuery)
                            }
                        }
                    }
                }

            LibraryData(
                isInitialized = true,
                showSystemCategory = showSystemCategory,
                categories = categories,
                favorites = filteredFavorites,
                tracksMap = tracksMap,
                loggedInTrackerIds = trackingFilters.keys,
                // RK: display-only overrides, keyed by real manga id; applied at the display read.
                customInfo = customInfo.associateBy { it.mangaId },
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            // RK: seeded null, which the derived state reads as still loading. The shared assembly emits
            //     a tick after this one, and that one empty frame renders the list branch with no
            //     categories rather than the empty-library screen, so loading must not end before it.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = combine(
        libraryData,
        searchQuery,
        activeCategoryIndex,
        displayPreferences,
        hasActiveFilters,
    ) { libraryData, searchQuery, activeCategoryIndex, display, hasActiveFilters ->
        State(
            isLoading = libraryData == null,
            searchQuery = searchQuery,
            hasActiveFilters = hasActiveFilters,
            showCategoryTabs = display.showCategoryTabs,
            showMangaCount = display.showMangaCount,
            showMangaContinueButton = display.showMangaContinueButton,
            libraryData = libraryData ?: LibraryData(),
            activeCategoryIndex = activeCategoryIndex,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    private data class DisplayPreferences(
        val showCategoryTabs: Boolean,
        val showMangaCount: Boolean,
        val showMangaContinueButton: Boolean,
    )

    init {
        // RK: a newly grouped entry's chapters have no cross-source identities yet, so the deduplicated
        //     unread count would be wrong until something wrote them. Reconciling off the membership
        //     flow covers every merge and unmerge from one place, instead of hooking each action, and
        //     costs one indexed query when nothing changed. Stays always-on rather than riding the
        //     shared state: a restore can regroup entries while the library renders nothing.
        viewModelScope.launchIO {
            mergeGroupRepository.getAllMembershipsAsFlow(ContentType.MANGA)
                .distinctUntilChanged()
                .collectLatest {
                    reconcileMergedChapters.await()
                }
        }
    }

    // RK -->
    fun setHopperGravity(value: Int) {
        reikaiLibraryPreferences.hopperGravity.set(value)
    }

    fun setCategorySortOrder(value: Int) {
        reikaiLibraryPreferences.categorySortOrder.set(value)
    }

    // RK: category collapse moved to LibraryEngine, which writes the same preferences. It is library-wide
    // rather than per content type, so it belongs beside the selection and not on a per-type model.

    // RK: dynamic grouping moved to LibraryEngine, which runs the shared kernel once over both content
    // types' feeds. This model contributes its half through LibraryProvider.dynamicGroupingFeed.
    // RK <--

    // RK -->
    // Manga library filter, routed through the shared reikai.presentation.library.libraryFilterMatches so
    // a filter behaviour change is written once for manga and novels. The per-type seams (downloaded's
    // local-source concept, lewd's source-name check, the merge-group tracker union) live in the accessors.
    private suspend fun List<LibraryItem>.applyFilters(
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        preferences: ItemPreferences,
    ): List<LibraryItem> {
        val includeCategories = preferences.filterCategoriesInclude
        val excludeCategories = preferences.filterCategoriesExclude
        val prefs = LibraryFilterPrefs(
            // Fold the global Downloaded-only mode in, and disable the interval axis when the
            // release-period gate is off, so the shared predicate stays a plain applyFilter.
            downloaded = if (preferences.globalFilterDownloaded) TriState.ENABLED_IS else preferences.filterDownloaded,
            unread = preferences.filterUnread,
            started = preferences.filterStarted,
            bookmarked = preferences.filterBookmarked,
            completed = preferences.filterCompleted,
            intervalCustom = if (preferences.skipOutsideReleasePeriod) {
                preferences.filterIntervalCustom
            } else {
                TriState.DISABLED
            },
            lewd = preferences.filterLewd,
            includedTracks = trackingFilter.filterValues { it == TriState.ENABLED_IS }.keys,
            excludedTracks = trackingFilter.filterValues { it == TriState.ENABLED_NOT }.keys,
            categoriesActive = categoryFilterActive(preferences.filterCategories, includeCategories, excludeCategories),
            categoriesInclude = includeCategories,
            categoriesExclude = excludeCategories,
        )
        val sourceNames = map { it.libraryManga.manga.source }
            .distinct()
            .associateWith { sourceManager.getOrStub(it).name }
        val fields = libraryItemFilterFields(
            lewdSourceName = { sourceNames[it.libraryManga.manga.source] },
            // Union tracks across the merged group (relatedMangaIds), so a tracker on any grouped source
            // counts; empty relatedMangaIds falls back to the entry's own id.
            trackerIds = { item ->
                item.relatedMangaIds.ifEmpty { listOf(item.id) }
                    .flatMap { trackMap[it].orEmpty() }
                    .map { it.trackerId }
            },
        )
        return fastFilter { libraryFilterMatches(it, prefs, fields) }
    }
    // RK <--

    // RK: the manga binding of the shared query kernel. The source key is the numeric source id as a
    // string (novels supply a plugin slug), and manga answer both time comparisons, so neither is gated.
    private fun mangaQueryFields(
        chapterMatches: Map<String, Set<Long>>,
        customInfo: List<CustomMangaInfo>,
    ) = libraryItemQueryFields(
        sourceKey = { it.libraryManga.manga.source.toString() },
        fetchInterval = { it.libraryManga.manga.fetchInterval },
        nextUpdate = { it.libraryManga.manga.nextUpdate },
        chapterMatches = chapterMatches,
        // Search matches what the card shows, so a renamed entry is findable by the name you gave it.
        // The rows stay override-free: filter, sort and grouping deliberately read the source values.
        overlay = customInfo.associate { it.mangaId to it.toQueryOverlay() },
    )

    // RK: one lookup per distinct `chapter:` term the user actually typed. Runs off the query slot, so a
    // plain search never touches the chapter table and a chapter search costs one scan, not one per row.
    private suspend fun resolveChapterMatches(query: String?): Map<String, Set<Long>> {
        val terms = query?.takeUnless { it.isBlank() }?.let { QueryNode.from(it).chapterSearchTerms() }
        return terms.orEmpty().associateWith { chapterRepository.getMangaIdsWithChapterNameLike(it) }
    }

    // RK: upstream's applyGrouping (bucket rows into categories) and Reikai's applySort are both gone.
    // LibraryEngine's assembleLibrary buckets and sorts, over rows from both content types at once, so
    // the category order, the per-category sort override and the empty-category rule live there now.

    private fun getLibraryItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge.changes(),
            libraryPreferences.unreadBadge.changes(),
            libraryPreferences.localBadge.changes(),
            libraryPreferences.languageBadge.changes(),
            libraryPreferences.autoUpdateMangaRestrictions.changes(),

            preferences.downloadedOnly.changes(),
            libraryPreferences.filterDownloaded.changes(),
            libraryPreferences.filterUnread.changes(),
            libraryPreferences.filterStarted.changes(),
            libraryPreferences.filterBookmarked.changes(),
            libraryPreferences.filterCompleted.changes(),
            libraryPreferences.filterIntervalCustom.changes(),
            // RK --> net-new Reikai filter dims + badge data
            reikaiLibraryPreferences.filterLewd.changes(),
            reikaiLibraryPreferences.filterCategories.changes(),
            reikaiLibraryPreferences.filterCategoriesInclude.changes(),
            reikaiLibraryPreferences.filterCategoriesExclude.changes(),
            reikaiLibraryPreferences.sourceBadge.changes(),
            // RK <--
        ) {
            ItemPreferences(
                downloadBadge = it[0] as Boolean,
                unreadBadge = it[1] as Boolean,
                localBadge = it[2] as Boolean,
                languageBadge = it[3] as Boolean,
                skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (it[4] as Set<*>),
                globalFilterDownloaded = it[5] as Boolean,
                filterDownloaded = it[6] as TriState,
                filterUnread = it[7] as TriState,
                filterStarted = it[8] as TriState,
                filterBookmarked = it[9] as TriState,
                filterCompleted = it[10] as TriState,
                filterIntervalCustom = it[11] as TriState,
                // RK -->
                filterLewd = it[12] as TriState,
                filterCategories = it[13] as Boolean,
                filterCategoriesInclude = (it[14] as Set<*>).mapNotNull { id ->
                    (id as? String)?.toLongOrNull()
                }.toSet(),
                filterCategoriesExclude = (it[15] as Set<*>).mapNotNull { id ->
                    (id as? String)?.toLongOrNull()
                }.toSet(),
                sourceBadge = it[16] as Boolean,
                // RK <--
            )
        }
    }

    private fun getFavoritesFlow(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes,
            // RK: re-collapse when the merge prefs change
            mergePrefsFlow(),
        ) { libraryManga, preferences, _, mergePrefs ->
            // RK: one batch query each for every gallery's EXH tags + alt-titles (empty for
            //     libraries without adult metadata), keyed by manga id for LibraryItem.matches.
            val tagsByManga = getSearchTags.awaitAll().groupBy { it.mangaId }
            val titlesByManga = getSearchTitles.awaitAll().groupBy { it.mangaId }
            val items = libraryManga.map { manga ->
                // RK: resolve the download count once (it walks the download-cache tree); reused for the
                //     field and the badge instead of two identical traversals per manga per emit.
                val downloadCount = downloadManager.getDownloadCount(manga.manga)
                val source = sourceManager.getOrStub(manga.manga.source)
                LibraryItem(
                    libraryManga = manga,
                    downloadCount = downloadCount,
                    unreadCount = manga.unreadCount,
                    searchTags = tagsByManga[manga.id],
                    searchTitles = titlesByManga[manga.id],
                    isLocal = manga.manga.isLocal(),
                    sourceName = source.name.lowercase(),
                    sourceLanguage = source.lang,
                    // RK: non-null only for gallery/metadata sources, which selects the tag-search path.
                    metadataSourceName = source.getMainSource<MetadataSource<*, *>>()
                        ?.let { source.getNameForMangaInfo() },
                    badges = LibraryItem.Badges(
                        downloadCount = if (preferences.downloadBadge) {
                            downloadCount
                        } else {
                            0
                        },
                        unreadCount = if (preferences.unreadBadge) {
                            manga.unreadCount
                        } else {
                            0
                        },
                        isLocal = if (preferences.localBadge) {
                            manga.manga.isLocal()
                        } else {
                            false
                        },
                        sourceLanguage = if (preferences.languageBadge) {
                            sourceManager.getOrStub(manga.manga.source).lang
                        } else {
                            ""
                        },
                        // RK: source/extension icon badge data (null when the source badge is off)
                        source = if (preferences.sourceBadge) {
                            sourceManager.getOrStub(manga.manga.source).let { s ->
                                DomainSource(s.id, s.lang, s.name, supportsLatest = false, isStub = s is StubSource)
                            }
                        } else {
                            null
                        },
                    ),
                )
            }
            // RK: collapse persisted merge groups into one entry per group. Returns the RAW items:
            //     search, filter and sort in the outer combine all read these, so the display-only
            //     custom-info overlay is applied later, at the per-category display read (see State).
            MangaMergeCollapse.collapse(
                items = items,
                membership = mergePrefs.membership,
                mergingEnabled = mergePrefs.mergingEnabled,
                showMergeSourceIcons = mergePrefs.showMergeSourceIcons,
                resolveSource = ::resolveMergeSource,
                mergedUnreadByGroup = if (mergePrefs.mergingEnabled) mergePrefs.mergedUnread else emptyMap(),
                showUnreadBadge = preferences.unreadBadge,
                overrideRankings = mergePrefs.overrideRankings,
                preferredSourceIds = mergePrefs.preferredSources,
                // RK: the same chapter count the details list ranks its trunk on, so both surfaces
                //     lead on one source. Read here for the same reason as the unread counts above.
                distinctChapterCounts = if (mergePrefs.mergingEnabled) {
                    mergedChapterUnitRepository.getCoveredChapterCounts()
                } else {
                    emptyMap()
                },
            )
        }
    }

    // RK -->
    private data class MergePrefs(
        val membership: Map<Long, Long>,
        val mergingEnabled: Boolean,
        val showMergeSourceIcons: Boolean,
        // Per-group source-order overrides and the global preferred-source list, so the collapsed row
        // leads on the user's chosen trunk. A reorder writes these tables/prefs and re-collapses live.
        val overrideRankings: Map<Long, List<Long>>,
        val preferredSources: List<Long>,
        /** Per group, one unit per chapter it covers that no source has read. A group absent from the
         *  map has not been stitched yet, which is not the same as having nothing left to read. */
        val mergedUnread: Map<Long, Long> = emptyMap(),
    )

    private fun mergePrefsFlow(): Flow<MergePrefs> = combine(
        combine(
            mergeGroupRepository.getAllMembershipsAsFlow(ContentType.MANGA),
            reikaiLibraryPreferences.seriesMergingEnabled.changes(),
            reikaiLibraryPreferences.showMergeSourceIcons.changes(),
            mergeGroupRepository.getOverrideRankingsAsFlow(ContentType.MANGA),
            reikaiLibraryPreferences.preferredMangaSources.changes(),
        ) { membership, mergingEnabled, showIcons, overrideRankings, preferredSources ->
            MergePrefs(membership, mergingEnabled, showIcons, overrideRankings, preferredSources)
        },
        // Folded in rather than read in the transform: reconciliation writes the stitch while the
        // library is already on screen, and nothing else makes this flow re-emit when it lands.
        mergedChapterUnitRepository.getUnreadCountsAsFlow(ContentType.MANGA),
    ) { prefs, unread -> prefs.copy(mergedUnread = unread) }

    private suspend fun resolveMergeSource(sourceId: Long): DomainSource {
        val s = sourceManager.getOrStub(sourceId)
        return DomainSource(s.id, s.lang, s.name, supportsLatest = false, isStub = s is StubSource)
    }
    // RK <--

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFiltersFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val filterFlows = loggedInTrackers.map { tracker ->
                    libraryPreferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
                }
                combine(filterFlows) { it.toMap() }
            }
        }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        // RK: resume over the whole merge group, not just the entry's own source. The badge counts a
        //     chapter as read when any source's copy is read, so resolving the next unread from one
        //     source alone could reopen something the badge already considers finished, or find nothing
        //     while the badge still shows unread. The provider returns the same deduplicated list the
        //     details screen shows, and each chapter keeps its own mangaId so the reader opens the right
        //     source. Falls through to the plain per-manga list when the entry is not merged.
        val group = mergedChapterProvider.load(manga)
        return group.chapters.getNextUnread(manga, downloadManager, group.readInOtherSources)
    }

    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    fun performDownloadAction(ids: List<Long>, action: DownloadAction) {
        val mangas = state.value.mangaFor(ids)
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadNextChapters(mangas, 1)
            DownloadAction.NEXT_5_CHAPTERS -> downloadNextChapters(mangas, 5)
            DownloadAction.NEXT_10_CHAPTERS -> downloadNextChapters(mangas, 10)
            DownloadAction.NEXT_25_CHAPTERS -> downloadNextChapters(mangas, 25)
            DownloadAction.UNREAD_CHAPTERS -> downloadNextChapters(mangas, null)
            DownloadAction.BOOKMARKED_CHAPTERS -> downloadBookmarkedChapters(mangas)
        }
    }

    // RK --> a merged cover is one selected row standing for its whole group, so a bulk action has to
    // act on every member, not just the collapsed primary. The members are collapsed out of the
    // library state, so they resolve from the DB by id. Ids are captured by the caller before the
    // selection clears (LibraryTab clears it as soon as the action returns), never inside the
    // coroutine, which would race that and come back empty.
    private suspend fun resolveSelectedGroupManga(memberIds: List<Long>): List<Manga> =
        memberIds.mapNotNull { getManga.await(it) }
    // RK <--

    // RK: downloads deliberately do NOT fan out across a merge group. The grouped sources carry the
    //     same chapters, so downloading every member would fetch each chapter once per source and
    //     waste the storage on near-duplicates. The right target is the group's deduplicated chapter
    //     list (what the details "All" view shows), which the library cannot build without the
    //     aggregation; until it does, this stays on the collapsed primary, which becomes the user's
    //     chosen trunk once the collapse honours the persisted source ranking.
    private fun downloadNextChapters(mangas: List<Manga>, amount: Int?) {
        viewModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getNextChapters.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    private fun downloadBookmarkedChapters(mangas: List<Manga>) {
        viewModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getBookmarkedChaptersByMangaId.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                    }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(ids: List<Long>, read: Boolean) {
        // RK: mark every source of a merge group, so a merged series doesn't stay part-read on the
        //     sources that aren't the collapsed primary.
        val memberIds = state.value.memberIdsFor(ids)
        viewModelScope.launchNonCancellable {
            resolveSelectedGroupManga(memberIds).forEach { manga ->
                setReadStatus.await(
                    manga = manga,
                    read = read,
                )
            }
        }
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(
        mangas: List<Manga>,
        deleteFromLibrary: Boolean,
        deleteChapters: Boolean,
        // RK: expand merged covers to every grouped source, so the whole series leaves the library
        //     instead of just the primary. Scopes both the library removal and the download deletion.
        removeGroupedSources: Boolean = false,
    ) {
        // RK: resolve the group member ids now, on the caller thread, from the manga being removed.
        val memberIds = if (removeGroupedSources) state.value.memberIdsFor(mangas.map { it.id }) else emptyList()
        viewModelScope.launchNonCancellable {
            // RK: the merged-away group members are collapsed out of the library state, so resolve
            //     them from the DB by id; falls back to the passed-in mangas when not expanding.
            val targets = if (removeGroupedSources) {
                memberIds.mapNotNull { getManga.await(it) }
            } else {
                mangas
            }
            if (deleteFromLibrary) {
                // RK: an entry leaving the library keeps its group, so it has to be handed its own
                //     copy of the group's shared tracker first; the hand-out skips non-favorites.
                mergeManager.handOutTrackersBeforeRemoval(targets.map { it.id })
                val toDelete = targets.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                targets.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        // RK: apply to every source of a merge group, so members can't drift into different
        //     categories and make the entry vanish from a category the user moved it to. Works on
        //     ids, so the merged-away members need no DB round-trip.
        val favoritesById = state.value.libraryData.favoritesById
        val memberIds = mangaList.flatMap { manga ->
            favoritesById[manga.id]?.relatedMangaIds?.ifEmpty { listOf(manga.id) } ?: listOf(manga.id)
        }.distinct()
        viewModelScope.launchNonCancellable {
            memberIds.forEach { mangaId ->
                val categoryIds = getCategories.await(mangaId)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setMangaCategories.await(mangaId, categoryIds)
            }
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode.asState(viewModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(viewModelScope)
    }

    // RK: picking a random entry moved to LibraryEngine, which reads the assembled list and so can pick
    // from a dynamic group and from either content type. Upstream's version is gone with the list it read.

    // RK: manually merge the selected manga into one group (covers both library views)
    fun mergeSelection(ids: List<Long>) {
        if (ids.size < 2) return
        viewModelScope.launchIO {
            // RK: each selected card's whole group is absorbed by the merge, so one call coalesces every source
            mergeManager.merge(ids)
        }
    }

    // RK: split the selected manga out of their merge groups (no-op for non-merged selections).
    // The manager hands each member its own tracker copy on the way out.
    fun unmergeSelection(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launchIO { mergeManager.unmerge(ids) }
    }

    fun search(query: String?) {
        searchQuery.update { query }
    }

    fun updateActiveCategoryIndex(index: Int) {
        activeCategoryIndex.update { index }
        // RK: upstream persisted lastUsedCategory here, coercing the index it read back off the derived
        // state; LibraryEngine owns that now, per chip, so a swipe under All can no longer overwrite the
        // Manga chip's restore point, and there is nothing to read back.
    }

    @Immutable
    private data class ItemPreferences(
        val downloadBadge: Boolean,
        val unreadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val skipOutsideReleasePeriod: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
        // RK --> net-new Reikai filter dims (lewd + include/exclude category) + badge data
        val filterLewd: TriState = TriState.DISABLED,
        val filterCategories: Boolean = false,
        val filterCategoriesInclude: Set<Long> = emptySet(),
        val filterCategoriesExclude: Set<Long> = emptySet(),
        val sourceBadge: Boolean = true,
        // RK <--
    )

    @Immutable
    data class LibraryData(
        val isInitialized: Boolean = false,
        val showSystemCategory: Boolean = false,
        val categories: List<Category> = emptyList(),
        val favorites: List<LibraryItem> = emptyList(),
        val tracksMap: Map</* Manga */ Long, List<Track>> = emptyMap(),
        val loggedInTrackerIds: Set<Long> = emptySet(),
        // RK: display-only custom title/cover overrides, keyed by real manga id. Never read by
        //     search/filter/sort/selection (those use the raw favorites); applied only at the
        //     per-category display read in State.getItemsForCategory.
        val customInfo: Map</* Manga */ Long, CustomMangaInfo> = emptyMap(),
    ) {
        val favoritesById by lazy { favorites.associateBy { it.id } }
    }

    @Immutable
    data class State(
        val isInitialized: Boolean = false,
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val libraryData: LibraryData = LibraryData(),
        // RK: exposed (upstream keeps it private) so the adapter can hand the tab the RAW index. The
        // coercion below is against this model's own category list, which is the wrong list under the
        // All chip; the tab coerces against the list it actually renders instead.
        val activeCategoryIndex: Int = 0,
        // RK: upstream's groupedFavorites (the bucketed, sorted list) and everything derived from it are
        // gone. LibraryEngine.assembled owns the list; this State carries rows and per-type status only.
    ) {
        val isLibraryEmpty = libraryData.favorites.isEmpty()

        // RK: these resolve an explicit id set rather than reading the selection, so a bulk action is
        //     driven by the ids its caller passes. That is what lets the shared engine own a selection
        //     spanning both content types and hand each provider only its own ids.
        fun mangaFor(ids: Collection<Long>): List<Manga> =
            ids.mapNotNull { libraryData.favoritesById[it]?.libraryManga?.manga }

        /** Any of [ids] is part of a merge group (drives the bulk Unmerge action). */
        fun containsMerged(ids: Collection<Long>): Boolean =
            ids.any { (libraryData.favoritesById[it]?.relatedMangaIds?.size ?: 0) > 1 }

        // RK: ids of every grouped source-manga behind [ids]. A merged cover is a single id standing
        //     for its whole group (LibraryItem.relatedMangaIds); the merged-away members are collapsed
        //     out of favoritesById, so we keep their ids here and resolve the manga from the DB at
        //     delete time. Equals [ids] when nothing is merged.
        fun memberIdsFor(ids: Collection<Long>): List<Long> =
            ids.flatMap { id ->
                val item = libraryData.favoritesById[id] ?: return@flatMap emptyList<Long>()
                item.relatedMangaIds.ifEmpty { listOf(id) }
            }.distinct()

        // RK: the one place the overlay is applied, reached through the provider seam
        //     (LibraryProvider.overlaid) at the shared assembly's display read. It stays out of the raw
        //     favorites, which is what search, filter, sort and selection read.
        fun withOverlay(item: LibraryItem): LibraryItem {
            val custom = libraryData.customInfo[item.libraryManga.manga.id] ?: return item
            return item.copy(
                libraryManga = item.libraryManga.copy(
                    manga = item.libraryManga.manga.withCustomInfo(custom),
                ),
            )
        }
    }
}
