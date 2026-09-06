package reikai.presentation.novel.reader

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.category.GetNovelCategories
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveDownloadedFilter
import reikai.domain.novel.model.effectiveReadFilter
import reikai.domain.novel.model.readerOrientation
import reikai.domain.novel.model.readingOrderComparator
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.reader.neighbourChapter
import reikai.domain.reader.removeDuplicateChapters
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.reader.ReaderThemePreset
import reikai.presentation.reader.readerDarkPreset
import reikai.presentation.reader.readerLightPreset
import reikai.presentation.reader.readerThemePresets
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Render state for the WebView novel reader. Display settings live in a separate [settings] flow so
 * changing them updates the WebView live (via `reader.readerSettings.val`) without reloading.
 */
sealed interface NovelReaderState {
    data object Loading : NovelReaderState
    data class Loaded(
        val chapterTitle: String,
        val html: String,
        /** Base URL for resolving relative links/images in the chapter HTML. */
        val baseUrl: String?,
        /** Resume position as a whole percent (0..100) for the web layer's initial scroll. */
        val initialProgressPercent: Int,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        /** This chapter's page on the source site (site + chapter path), for the WebView button; null
         *  for a downloaded chapter whose source isn't loaded this session. */
        val webUrl: String? = null,
        /** Whether the current chapter is bookmarked (drives the top-bar bookmark toggle). */
        val bookmarked: Boolean = false,
    ) : NovelReaderState
    data class Failed(val message: String) : NovelReaderState
}

/** Max raw-HTML entries held in the reader's session cache (LRU): current chapter, a prefetched
 *  next, and a little back-flip history. */
private const val MAX_CACHED_CHAPTERS = 5

/**
 * Loads novel chapters for the WebView reader. [sourceScoped] picks what prev/next walks: source scope
 * (Updates, a source chip, notifications) uses just [novelId]'s own chapters; group scope (the default:
 * details All chip, Library resume, History) resolves the merge group from [novelId] and aggregates the
 * unified cross-source order in-reader, so no caller passes a list. Each chapter loads through its own
 * source, resolved per `chapter.novelId`, so a merged session walks across sources. Chapters load live
 * via `parseChapter`, offline ones from disk, and reading past 97% auto-marks the chapter read.
 */
class NovelReaderScreenModel(
    private val novelId: Long,
    initialChapterId: Long,
    private val sourceScoped: Boolean = false,
) : StateScreenModel<NovelReaderState>(NovelReaderState.Loading) {

    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()

    private val downloadManager: NovelDownloadManager by injectLazy()
    private val upsertNovelHistory: UpsertNovelHistory by injectLazy()
    private val setNovelViewerFlags: SetNovelViewerFlags by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelReadStatus: SetNovelReadStatus by injectLazy()

    // Merge-group resolution + the shared "mark duplicate read" pref, for marking same-numbered
    // chapters across a merged novel's sources read on completion (parity with the manga reader).
    private val mergeManager: NovelMergeManager by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()

    // Global novel source ranking, to pick the merge trunk when the reader resolves group scope
    // (matches the details/library aggregation).
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()

    // novel trackers: push read progress on chapter completion
    private val trackNovelChapter: TrackNovelChapter by injectLazy()
    private val trackPreferences: TrackPreferences by injectLazy()

    private val getIncognitoState: GetIncognitoState by injectLazy()

    /** Read-aloud (TTS) controller. Owned here so it survives rotation; the WebView registers its
     *  `evaluateJavascript` sink. Auto-page-advance continues into the next chapter via [next]. */
    val ttsController = NovelTtsController(Injekt.get<Application>(), novelPreferences) { next() }

    // Captured once at reader open (mirrors ReaderViewModel). Global-only: novel sources are
    // String-keyed with no installed extension, so per-source incognito (await(sourceId)) can't apply.
    private var incognitoMode: Boolean = false

    private var currentId: Long = initialChapterId

    /** Chapter ids in reading order, loaded once on first [load]. */
    private var orderedIds: List<Long> = emptyList()

    /** Which of [orderedIds] a forward step may land on, per the skip settings. Back steps ignore it, so
     *  a skipped chapter stays reachable from the one after it. Built with [orderedIds]. */
    private var forwardEligibleIds: Set<Long> = emptySet()

    private val skipDupePref = novelPreferences.readerSkipDuplicateChapters()
    private val skipReadPref = novelPreferences.readerSkipRead()
    private val skipFilteredPref = novelPreferences.readerSkipFiltered()

    /** Per-novel reader orientation override (a [ReaderOrientation] flagValue; 0 = follow the global
     *  default), seeded from the host novel in [init]. Keyed on the opened entry [novelId] (the anchor
     *  for a merged novel), since orientation is a book-level preference like sort/filter, not the
     *  per-source progress that [currentNovelId] tracks. */
    private val orientationOverride = MutableStateFlow(ReaderOrientation.DEFAULT.flagValue)

    /** Owning novel of the current chapter. Defaults to the host (== owner for a standalone novel);
     *  a merged session re-points it per chapter so the last-read stamp lands on the source read. */
    @Volatile
    private var currentNovelId: Long = novelId

    /** When the current chapter began being read, for the novel-history session duration (the analog of
     *  ReaderViewModel.chapterReadStartTime). Reset whenever a chapter loads. */
    @Volatile
    private var chapterReadStartTime: Long? = null

    /** The chapters [next] / [prev] jump to, re-resolved (skip-duplicate aware) whenever the chapter or
     *  the skip-duplicate pref changes, so the buttons stay instant. */
    @Volatile
    private var resolvedPrev: Long? = null

    @Volatile
    private var resolvedNext: Long? = null

    /** Session-scoped, so the source cache inside it lives exactly as long as this reading session. */
    private val textLoader = NovelChapterTextLoader(
        novelRepo = novelRepo,
        sourceManager = sourceManager,
        installer = installer,
        preferences = novelPreferences,
        readDownloaded = { novel, chapter -> downloadManager.getChapterText(novel, chapter) },
    )

    /** Session-scoped LRU of raw chapter HTML + base URL keyed by chapter id (RAM-only, dies with the
     *  screen); a prefetched next chapter opens instantly. Synchronized: the prefetch coroutine and
     *  the reader both touch it. */
    private val htmlCache: MutableMap<Long, Pair<String, String?>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, Pair<String, String?>>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<String, String?>>) =
                size > MAX_CACHED_CHAPTERS
        },
    )

    /** Reactive reader display settings; [NovelReaderScreen] resolves follow-system into colors. */
    val settings: StateFlow<NovelReaderSettings> = combine(
        combine(
            novelPreferences.readerFontSize().changes(),
            novelPreferences.readerLineSpacing().changes(),
            novelPreferences.readerTextAlign().changes(),
            novelPreferences.readerPadding().changes(),
            novelPreferences.readerFontFamily().changes(),
        ) { fontSize, lineHeight, textAlign, padding, fontFamily ->
            DisplayPrefs(fontSize, lineHeight, textAlign, padding, fontFamily)
        },
        combine(
            novelPreferences.readerFollowSystemTheme().changes(),
            novelPreferences.readerBackgroundColor().changes(),
            novelPreferences.readerTextColor().changes(),
        ) { followSystem, bg, text -> ThemePrefs(followSystem, bg, text) },
        novelPreferences.readerKeepScreenOn().changes(),
        combine(
            orientationOverride,
            novelPreferences.readerDefaultOrientation().changes(),
        ) { override, default -> OrientationPrefs(override, default) },
        combine(
            combine(
                novelPreferences.readerTtsEnabled().changes(),
                novelPreferences.readerTtsRate().changes(),
                novelPreferences.readerTtsPitch().changes(),
                novelPreferences.readerTtsAutoPageAdvance().changes(),
                novelPreferences.readerTtsScrollToTop().changes(),
            ) { enabled, rate, pitch, autoAdvance, scrollTop ->
                TtsPrefs(enabled, rate, pitch, autoAdvance, scrollTop)
            },
            combine(
                novelPreferences.readerBionicReading().changes(),
                novelPreferences.readerRemoveExtraSpacing().changes(),
                novelPreferences.readerTapToScroll().changes(),
                novelPreferences.readerSwipeGestures().changes(),
                novelPreferences.readerShowProgressPercentage().changes(),
            ) { bionic, spacing, tapScroll, swipe, showProgress ->
                FlagPrefs(bionic, spacing, tapScroll, swipe, showProgress)
            },
            combine(
                novelPreferences.readerAutoScroll().changes(),
                novelPreferences.readerAutoScrollSpeed().changes(),
                novelPreferences.readerRailHeight().changes(),
                novelPreferences.readerRailOnLeft().changes(),
            ) { autoScroll, speed, railHeight, railOnLeft ->
                ScrollPrefs(autoScroll, speed, railHeight, railOnLeft)
            },
            combine(
                novelPreferences.readerUseVolumeButtons().changes(),
                novelPreferences.readerVolumeButtonsInverted().changes(),
                novelPreferences.readerVolumeButtonsFraction().changes(),
            ) { enabled, inverted, fraction -> VolumePrefs(enabled, inverted, fraction) },
        ) { tts, flags, scroll, volume -> ReaderExtraPrefs(tts, flags, scroll, volume) },
    ) { display, theme, keepScreenOn, orient, extra ->
        NovelReaderSettings(
            fontSize = display.fontSize,
            lineHeight = display.lineHeight,
            textAlign = display.textAlign,
            padding = display.padding,
            fontFamily = display.fontFamily,
            followSystemTheme = theme.followSystem,
            backgroundColor = theme.background,
            textColor = theme.textColor,
            keepScreenOn = keepScreenOn,
            orientation = orient.override,
            resolvedOrientation = orient.resolved,
            ttsEnabled = extra.tts.enabled,
            ttsRate = extra.tts.rate,
            ttsPitch = extra.tts.pitch,
            ttsAutoPageAdvance = extra.tts.autoPageAdvance,
            ttsScrollToTop = extra.tts.scrollToTop,
            bionicReading = extra.flags.bionicReading,
            removeExtraSpacing = extra.flags.removeExtraSpacing,
            tapToScroll = extra.flags.tapToScroll,
            swipeGestures = extra.flags.swipeGestures,
            showProgressPercentage = extra.flags.showProgressPercentage,
            autoScroll = extra.scroll.autoScroll,
            autoScrollSpeed = extra.scroll.autoScrollSpeed,
            railHeightPercent = extra.scroll.railHeight,
            railOnLeft = extra.scroll.railOnLeft,
            useVolumeButtons = extra.volume.enabled,
            volumeButtonsInverted = extra.volume.inverted,
            volumeButtonsFraction = extra.volume.fraction,
        )
    }.stateIn(screenModelScope, SharingStarted.Eagerly, currentSettings())

    private fun currentSettings(): NovelReaderSettings {
        val override = orientationOverride.value
        val default = novelPreferences.readerDefaultOrientation().get()
        return NovelReaderSettings(
            fontSize = novelPreferences.readerFontSize().get(),
            lineHeight = novelPreferences.readerLineSpacing().get(),
            textAlign = novelPreferences.readerTextAlign().get(),
            padding = novelPreferences.readerPadding().get(),
            fontFamily = novelPreferences.readerFontFamily().get(),
            followSystemTheme = novelPreferences.readerFollowSystemTheme().get(),
            backgroundColor = novelPreferences.readerBackgroundColor().get(),
            textColor = novelPreferences.readerTextColor().get(),
            keepScreenOn = novelPreferences.readerKeepScreenOn().get(),
            orientation = override,
            resolvedOrientation = OrientationPrefs(override, default).resolved,
            ttsEnabled = novelPreferences.readerTtsEnabled().get(),
            ttsRate = novelPreferences.readerTtsRate().get(),
            ttsPitch = novelPreferences.readerTtsPitch().get(),
            ttsAutoPageAdvance = novelPreferences.readerTtsAutoPageAdvance().get(),
            ttsScrollToTop = novelPreferences.readerTtsScrollToTop().get(),
            bionicReading = novelPreferences.readerBionicReading().get(),
            removeExtraSpacing = novelPreferences.readerRemoveExtraSpacing().get(),
            tapToScroll = novelPreferences.readerTapToScroll().get(),
            swipeGestures = novelPreferences.readerSwipeGestures().get(),
            showProgressPercentage = novelPreferences.readerShowProgressPercentage().get(),
            autoScroll = novelPreferences.readerAutoScroll().get(),
            autoScrollSpeed = novelPreferences.readerAutoScrollSpeed().get(),
            railHeightPercent = novelPreferences.readerRailHeight().get(),
            railOnLeft = novelPreferences.readerRailOnLeft().get(),
            useVolumeButtons = novelPreferences.readerUseVolumeButtons().get(),
            volumeButtonsInverted = novelPreferences.readerVolumeButtonsInverted().get(),
            volumeButtonsFraction = novelPreferences.readerVolumeButtonsFraction().get(),
        )
    }

    /** Brightness + colour-filter overlay state, separate from [settings] so a change renders natively
     *  over the WebView and never triggers a WebView settings re-push. */
    val overlaySettings: StateFlow<NovelReaderOverlaySettings> = combine(
        novelPreferences.readerCustomBrightness().changes(),
        novelPreferences.readerCustomBrightnessValue().changes(),
        novelPreferences.readerColorFilter().changes(),
        novelPreferences.readerColorFilterValue().changes(),
        novelPreferences.readerColorFilterMode().changes(),
    ) { customBrightness, brightnessValue, colorFilter, colorFilterValue, colorFilterMode ->
        NovelReaderOverlaySettings(customBrightness, brightnessValue, colorFilter, colorFilterValue, colorFilterMode)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, currentOverlaySettings())

    private fun currentOverlaySettings() = NovelReaderOverlaySettings(
        customBrightness = novelPreferences.readerCustomBrightness().get(),
        customBrightnessValue = novelPreferences.readerCustomBrightnessValue().get(),
        colorFilter = novelPreferences.readerColorFilter().get(),
        colorFilterValue = novelPreferences.readerColorFilterValue().get(),
        colorFilterMode = novelPreferences.readerColorFilterMode().get(),
    )

    /** User-selected bottom-bar buttons, kept out of [settings] since it drives only the chrome, not
     *  the WebView. */
    val bottomButtons: StateFlow<Set<String>> = novelPreferences.readerBottomButtons().changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, novelPreferences.readerBottomButtons().get())

    init {
        // Seed the per-novel orientation from the opened entry (the anchor for a merged novel).
        screenModelScope.launchIO {
            novelRepo.getById(novelId)?.let { orientationOverride.value = it.readerOrientation.toInt() }
        }
        load()
    }

    fun retry() = load()

    // Only a forward skip marks the departed chapter read (mirrors manga: loadNextChapter only).
    fun next() = resolvedNext?.let { goTo(it, markDepartedRead = true) } ?: Unit
    fun prev() = resolvedPrev?.let { goTo(it) } ?: Unit

    /** Jump straight to [id] from the chapters sheet (no-op if it is already the current chapter). */
    fun goToChapter(id: Long) {
        if (id != currentId) goTo(id)
    }

    fun currentChapterId(): Long = currentId

    /** Chapters in reading order, for the jump-to-chapter sheet. One query for the anchor novel covers
     *  the non-merged case; a merged novel's cross-source siblings fall back to per-id lookups. */
    suspend fun chapterList(): List<NovelChapter> {
        val anchor = chapterRepo.getByNovelId(novelId).associateBy { it.id }
        return orderedIds.mapNotNull { id -> anchor[id] ?: chapterRepo.getById(id) }
    }

    /** The order the reader pages in: the opened novel's own chapter sort, always ascending, so prev and
     *  next follow the order the user chose on its chapter list rather than a fixed one. The manga reader
     *  resolves the same way, with getChapterSort(manga, sortDescending = false). */
    private suspend fun readingOrder(): Comparator<NovelChapter> {
        val novel = novelRepo.getById(novelId)
        return if (novel == null) compareBy { it.chapterNumber } else readingOrderComparator(novel, novelPreferences)
    }

    /** The merge group's unified chapters in reading order, the novel twin of MergedChapterProvider. A
     *  non-merged novel (or merging disabled) is just its own chapters. The global preferred-source
     *  ranking picks the trunk, matching the details/library aggregation. */
    private suspend fun resolveGroupChapters(): List<NovelChapter> {
        val ids = mergeManager.relatedIdsList(novelId)
        if (ids.size <= 1) return chapterRepo.getByNovelId(novelId).sortedWith(readingOrder())
        val byNovel = ids.associateWith { chapterRepo.getByNovelId(it) }
        val sourceIdByNovel = ids.associateWith { novelRepo.getById(it)?.source.orEmpty() }
        val aggregated = NovelChapterAggregation.aggregate(
            byNovel,
            sourceIdByNovel,
            reikaiLibraryPreferences.preferredNovelSources.get(),
            mergeManager.overrideRankingMemberIds(novelId),
        ).sortedWith(readingOrder())
        return dedupIfEnabled(aggregated, sourceIdByNovel)
    }

    /**
     * Drop same-numbered duplicates from the list rather than stepping over them while navigating, so the
     * chapter sheet, download-ahead and delete-after-read all count what the reader actually shows. Runs
     * for an unmerged novel too, matching the manga reader, since one source can list a chapter twice.
     * A novel has no scanlator, so its source stands in as the origin the current chapter prefers. This is
     * the second net: [NovelChapterAggregation] already collapses cross-source copies by normalized title,
     * so only copies whose titles differ but whose numbers agree ever reach here.
     */
    private fun dedupIfEnabled(
        chapters: List<NovelChapter>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
    ): List<NovelChapter> {
        if (!skipDupePref.get()) return chapters
        val current = chapters.find { it.id == currentId } ?: return chapters
        return chapters.removeDuplicateChapters(
            current,
            numberOf = { it.chapterNumber },
            idOf = { it.id },
            originOf = { sourceIdByNovel[it.novelId] },
        )
    }

    /** Drop user-hidden chapters from a reading-order id list so prev/next skips them, keeping the
     *  currently-open chapter so opening a hidden one directly still resolves. The hidden key mirrors
     *  the details screen: "<sourceId>|<chapterUrl>", the source resolved per the chapter's own
     *  novelId (cheap DB read, no plugin load) so a merged session keys each sibling correctly. */
    private suspend fun filterHiddenChapters(ids: List<Long>): List<Long> {
        val hidden = novelPreferences.hiddenChapters().get()
        if (hidden.isEmpty()) return ids
        val anchor = chapterRepo.getByNovelId(novelId).associateBy { it.id }
        val sourceIdByNovel = HashMap<Long, String>()
        return ids.filter { id ->
            if (id == currentId) return@filter true
            val chapter = anchor[id] ?: chapterRepo.getById(id) ?: return@filter true
            val sourceId = sourceIdByNovel[chapter.novelId] ?: run {
                val resolved = novelRepo.getById(chapter.novelId)?.source.orEmpty()
                sourceIdByNovel[chapter.novelId] = resolved
                resolved
            }
            "$sourceId|${chapter.url}" !in hidden
        }
    }

    /** Toggle the current chapter's bookmark (the top-bar action). */
    // The chapters-sheet swipe actions follow the same prefs as the details and manga-reader lists,
    // with start/end crossed so a given swipe direction does the same thing everywhere.
    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()

    fun toggleBookmark() {
        val loaded = state.value as? NovelReaderState.Loaded ?: return
        setChapterBookmark(currentId, !loaded.bookmarked)
    }

    /** Set the read state of an arbitrary chapter from the chapters sheet's swipe; uses SetNovelReadStatus
     *  so tracker sync + delete-after-read fire like the details "mark as read". */
    fun setChapterReadStatus(chapter: NovelChapter, read: Boolean) {
        screenModelScope.launchIO { setNovelReadStatus.await(read, listOf(chapter)) }
    }

    /** Set [bookmark] on chapter [id] (the chapters sheet's swipe/toggle); reflects in the top bar when
     *  [id] is the current chapter. */
    fun setChapterBookmark(id: Long, bookmark: Boolean) {
        if (id == currentId) {
            (state.value as? NovelReaderState.Loaded)?.let { mutableState.value = it.copy(bookmarked = bookmark) }
        }
        screenModelScope.launchIO { chapterRepo.setBookmark(id, bookmark) }
    }

    /** Live download queue, for the chapters sheet's per-row download indicator. */
    val downloadQueue: StateFlow<List<NovelDownload>> get() = downloadManager.queueState

    /** Start / cancel / delete a chapter download from the chapters sheet (mirrors the details model). */
    fun onChapterDownloadAction(chapter: NovelChapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.START -> downloadManager.downloadChapters(listOf(chapter))
            ChapterDownloadAction.START_NOW -> {
                downloadManager.downloadChapters(listOf(chapter))
                downloadManager.startDownloadNow(chapter.id)
            }
            ChapterDownloadAction.CANCEL -> downloadManager.cancelDownloads(listOf(chapter.id))
            ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(listOf(chapter))
        }
    }

    /** Per-source display names keyed by novelId, for the chapters sheet's source labels on a merged
     *  novel. Empty for a single-source novel (one distinct novelId), so no label is shown. */
    suspend fun chapterSourceNames(chapters: List<NovelChapter>): Map<Long, String> {
        val novelIds = chapters.map { it.novelId }.distinct()
        if (novelIds.size <= 1) return emptyMap()
        return novelIds.associateWith { id ->
            textLoader.cachedSource(id)?.name
                ?: novelRepo.getById(id)?.source?.let { sourceManager.get(it)?.name ?: it }
                ?: ""
        }
    }

    /** Which of [chapters] are downloaded on disk (from NovelDownloadCache, via the manager). Re-queried by
     *  the sheet whenever the download queue changes, so a completed download shows without reopening.
     *  Resolves each chapter's owning novel (a merged read spans several). Replaces the old is_downloaded
     *  flag on the row. */
    suspend fun downloadedChapterIds(chapters: List<NovelChapter>): Set<Long> {
        val novelsById = chapters.map { it.novelId }.distinct()
            .mapNotNull { id -> novelRepo.getById(id)?.let { id to it } }
            .toMap()
        return chapters
            .filter { ch -> novelsById[ch.novelId]?.let { downloadManager.isChapterDownloaded(it, ch) } == true }
            .mapTo(HashSet()) { it.id }
    }

    private fun goTo(id: Long, markDepartedRead: Boolean = false) {
        // Record the outgoing chapter before switching (the analog of Mihon's loadNewChapter ->
        // updateHistory + restartReadTimer), then load the new one (loadCurrent resets the timer).
        mutableState.value = NovelReaderState.Loading
        screenModelScope.launchIO {
            updateHistory()
            // mark-read-on-skip: the departed chapter + its owning novel are still current here
            // (before the reassignment + loadCurrent below re-point them to the incoming chapter).
            if (markDepartedRead) markReadOnSkip(currentId, currentNovelId)
            currentId = id
            loadCurrent()
        }
    }

    // mark-read-on-skip (opt-in): mark the chapter the user skipped away from as read (forward
    // only), the novel twin of ReaderViewModel.markChapterReadOnSkip. Reuses saveProgress's tracker push.
    private suspend fun markReadOnSkip(departedId: Long, departedNovelId: Long) {
        if (incognitoMode || !novelPreferences.readerMarkReadOnSkip().get()) return
        val chapter = chapterRepo.getById(departedId) ?: return
        if (chapter.read) return
        chapterRepo.setReadBulk(listOf(departedId), true)
        if (trackPreferences.autoUpdateTrack.get()) {
            trackNovelChapter.await(Injekt.get<Application>(), departedNovelId, chapter.chapterNumber)
        }
    }

    /** Stamp the current chapter into novel history and accumulate this session's read time. Called on
     *  chapter switch and on leaving the reader (the novel twin of ReaderViewModel.updateHistory). */
    suspend fun updateHistory() {
        if (incognitoMode) return
        val now = System.currentTimeMillis()
        val duration = chapterReadStartTime?.let { now - it } ?: 0L
        upsertNovelHistory.await(NovelHistoryUpdate(currentId, now, duration))
        chapterReadStartTime = null
    }

    /** The id one step from [currentId] in reading order. Duplicates are already gone from [orderedIds],
     *  removed when it was built, so this never has to walk past them. Forward honours the skip settings
     *  and back does not, so the chapter just finished stays reachable; see [neighbourChapter]. */
    private fun resolveNeighbor(forward: Boolean): Long? = orderedIds.neighbourChapter(
        index = orderedIds.indexOf(currentId),
        forward = forward,
        isForwardEligible = { it in forwardEligibleIds },
    )

    /** Re-resolve both neighbors (skip-duplicate aware) and warm the next chapter. */
    private suspend fun resolveBothNeighbors() {
        resolvedPrev = resolveNeighbor(forward = false)
        resolvedNext = resolveNeighbor(forward = true)
        prefetchNext()
        maybeDownloadAhead()
    }

    /** Download-ahead: enqueue the next N un-downloaded chapters in reading order (novel twin of manga's
     *  autoDownloadWhileReading). Skipped in incognito and when off. */
    private suspend fun maybeDownloadAhead() {
        if (incognitoMode) return
        val ahead = novelPreferences.autoDownloadWhileReading().get()
        if (ahead <= 0) return
        val index = orderedIds.indexOf(currentId)
        if (index < 0) return
        val nextIds = orderedIds.drop(index + 1).take(ahead)
        val toDownload = nextIds.mapNotNull { chapterRepo.getById(it) }
            .filterNot { ch ->
                val novel = novelRepo.getById(ch.novelId) ?: return@filterNot false
                downloadManager.isChapterDownloaded(novel, ch)
            }
        if (toDownload.isNotEmpty()) downloadManager.downloadChapters(toDownload)
    }

    fun setFontSize(value: Int) = novelPreferences.readerFontSize().set(value)
    fun setLineHeight(value: Float) = novelPreferences.readerLineSpacing().set(value)
    fun setTextAlign(value: String) = novelPreferences.readerTextAlign().set(value)
    fun setPadding(value: Int) = novelPreferences.readerPadding().set(value)
    fun setFontFamily(value: String) = novelPreferences.readerFontFamily().set(value)

    fun setKeepScreenOn(value: Boolean) = novelPreferences.readerKeepScreenOn().set(value)

    // Text-to-speech setters. Rate/pitch/voice are pushed to the live audio engine too (the WebView's
    // tts block only steers core.js); an engine swap rebuilds the backend.
    fun setTtsEnabled(value: Boolean) {
        novelPreferences.readerTtsEnabled().set(value)
        if (!value) ttsController.stop()
    }
    fun setTtsRate(value: Float) {
        novelPreferences.readerTtsRate().set(value)
        ttsController.refreshSettings(engineChanged = false)
    }
    fun setTtsPitch(value: Float) {
        novelPreferences.readerTtsPitch().set(value)
        ttsController.refreshSettings(engineChanged = false)
    }
    fun setTtsAutoPageAdvance(value: Boolean) = novelPreferences.readerTtsAutoPageAdvance().set(value)
    fun setTtsScrollToTop(value: Boolean) = novelPreferences.readerTtsScrollToTop().set(value)
    fun setTtsEngine(packageName: String) {
        novelPreferences.readerTtsEngine().set(packageName)
        novelPreferences.readerTtsVoice().set("")
        ttsController.refreshSettings(engineChanged = true)
    }
    fun setTtsVoice(name: String) {
        novelPreferences.readerTtsVoice().set(name)
        ttsController.refreshSettings(engineChanged = false)
    }
    fun setTtsLanguages(languages: Set<String>) = novelPreferences.readerTtsLanguages().set(languages)

    fun setBionicReading(value: Boolean) = novelPreferences.readerBionicReading().set(value)
    fun setRemoveExtraSpacing(value: Boolean) = novelPreferences.readerRemoveExtraSpacing().set(value)
    fun setTapToScroll(value: Boolean) = novelPreferences.readerTapToScroll().set(value)
    fun setSwipeGestures(value: Boolean) = novelPreferences.readerSwipeGestures().set(value)
    fun setAutoScroll(value: Boolean) = novelPreferences.readerAutoScroll().set(value)
    fun setAutoScrollSpeed(value: Float) = novelPreferences.readerAutoScrollSpeed().set(value)
    fun setTtsButtonPosition(x: Int, y: Int) {
        novelPreferences.readerTtsButtonX().set(x)
        novelPreferences.readerTtsButtonY().set(y)
    }
    fun ttsButtonPosition(): Pair<Int, Int> =
        novelPreferences.readerTtsButtonX().get() to novelPreferences.readerTtsButtonY().get()
    fun ttsEnginePackage(): String = novelPreferences.readerTtsEngine().get()
    fun ttsVoiceName(): String = novelPreferences.readerTtsVoice().get()
    fun ttsLanguages(): Set<String> = novelPreferences.readerTtsLanguages().get()

    /** Set this novel's reader orientation (a [ReaderOrientation] flagValue; DEFAULT = follow the
     *  global default). Writes only the orientation bits of the anchor's viewer_flags via
     *  [SetNovelViewerFlags]; the override flow updates the live settings + the apply effect immediately. */
    fun setOrientation(flagValue: Int) {
        orientationOverride.value = flagValue
        screenModelScope.launchIO {
            setNovelViewerFlags.awaitSetOrientation(novelId, flagValue.toLong())
        }
    }

    fun setFollowSystemTheme() = novelPreferences.readerFollowSystemTheme().set(true)

    fun setThemePreset(preset: ReaderThemePreset) {
        novelPreferences.readerFollowSystemTheme().set(false)
        novelPreferences.readerBackgroundColor().set(preset.background)
        novelPreferences.readerTextColor().set(preset.textColor)
    }

    fun setCustomBrightness(enabled: Boolean) = novelPreferences.readerCustomBrightness().set(enabled)
    fun setCustomBrightnessValue(value: Int) = novelPreferences.readerCustomBrightnessValue().set(value)
    fun setColorFilter(enabled: Boolean) = novelPreferences.readerColorFilter().set(enabled)
    fun setColorFilterValue(value: Int) = novelPreferences.readerColorFilterValue().set(value)
    fun setColorFilterMode(mode: Int) = novelPreferences.readerColorFilterMode().set(mode)

    /** Persist the reader's scroll position. The web layer reports a whole percent (0..100); store it
     *  as 0..10000 to match [NovelChapter.lastTextProgress]. Reaching the end auto-marks read. */
    fun saveProgress(percent: Int) {
        if (incognitoMode) return
        val id = currentId
        val clamped = percent.coerceIn(0, 100)
        screenModelScope.launchIO {
            chapterRepo.setLastTextProgress(id, clamped * 100L)
            // Stamp the owning novel's last-read time so the LastRead library sort reflects this read.
            novelRepo.setLastReadAt(currentNovelId, System.currentTimeMillis())
            if (clamped >= 97) {
                // Fetch before marking so the shared interactor sees the chapter as still unread; it flips
                // read + honors "delete after marked as read" (the in-RAM htmlCache keeps this view alive).
                val chapter = chapterRepo.getById(id)
                // Mark same-numbered unread chapters across the merged group read too, mirroring the
                // manga reader (ReaderViewModel.updateChapterProgressOnComplete), gated on the shared
                // markDuplicateReadChapterAsRead pref. relatedIdsList returns just this novel when
                // it isn't merged, so a single-source read is unchanged.
                val markDupes = libraryPreferences.markDuplicateReadChapterAsRead.get()
                    .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
                val toMark = if (chapter != null && markDupes) {
                    val siblings = mergeManager.relatedIdsList(novelId)
                        .takeIf { it.size > 1 }
                        ?.flatMap { chapterRepo.getByNovelId(it) }
                        ?.filter {
                            it.id != id && !it.read && it.chapterNumber >= 0.0 &&
                                it.chapterNumber == chapter.chapterNumber
                        }
                        .orEmpty()
                    listOf(chapter) + siblings
                } else {
                    listOfNotNull(chapter)
                }
                setNovelReadStatus.await(true, toMark)
                // push read progress to bound trackers, mirroring ReaderViewModel.updateTrackChapterRead
                if (trackPreferences.autoUpdateTrack.get()) {
                    chapter?.let {
                        trackNovelChapter.await(Injekt.get<Application>(), currentNovelId, it.chapterNumber)
                    }
                }
                maybeDeleteAfterRead(id)
            }
        }
    }

    /** Twin of [reikai.domain.novel.interactor.DeleteNovelChaptersBehindReader], which the shared
     *  reader calls. Collapses into it when this model is deleted by the reader takeover.
     *
     *  Keep the last N read chapters downloaded (the [NovelPreferences.removeAfterReadSlots] buffer):
     *  delete the chapter [slots] positions back in reading order, so sequential reading keeps a rolling
     *  buffer. Skips a bookmarked chapter unless allowed and novels in an excluded category. The separate
     *  "delete after marked as read" pref is handled by [deleteNovelChaptersAfterRead] on the mark itself. */
    private suspend fun maybeDeleteAfterRead(readChapterId: Long) {
        val slots = novelPreferences.removeAfterReadSlots().get()
        if (slots < 0) return
        val index = orderedIds.indexOf(readChapterId)
        if (index < 0) return
        val targetId = orderedIds.getOrNull(index - slots) ?: return
        val target = chapterRepo.getById(targetId) ?: return
        if (!target.read) return
        if (target.bookmark && !novelPreferences.removeBookmarkedChapters().get()) return
        val excluded = novelPreferences.removeExcludeCategories().get().mapNotNull { it.toLongOrNull() }
        if (excluded.isNotEmpty()) {
            val cats = getNovelCategories.awaitByNovelId(currentNovelId).map { it.id }.ifEmpty { listOf(0L) }
            if (cats.intersect(excluded.toSet()).isNotEmpty()) return
        }
        downloadManager.deleteChapters(listOf(target))
    }

    /**
     * Which chapters a forward step may stop on. "Skip read" drops read ones; "skip filtered" drops the
     * ones this novel's own chapter-list filters hide, which is what makes the setting mean the same
     * thing here as on the details screen. The open chapter always stays eligible, so opening a filtered
     * chapter directly does not strand the reader on it.
     */
    private suspend fun resolveForwardEligible(ids: List<Long>): Set<Long> {
        val skipRead = skipReadPref.get()
        val skipFiltered = skipFilteredPref.get()
        if (!skipRead && !skipFiltered) return ids.toSet()
        val novel = novelRepo.getById(novelId) ?: return ids.toSet()
        val chapters = ids.mapNotNull { chapterRepo.getById(it) }
        val downloaded = if (skipFiltered) downloadedChapterIds(chapters) else emptySet()
        val readFilter = novel.effectiveReadFilter(novelPreferences)
        val bookmarkFilter = novel.effectiveBookmarkedFilter(novelPreferences)
        val downloadFilter = novel.effectiveDownloadedFilter(novelPreferences)
        return chapters.filterTo(HashSet()) { ch ->
            when {
                ch.id == currentId -> true
                skipRead && ch.read -> false
                !skipFiltered -> true
                readFilter == NovelChapterFlags.SHOW_UNREAD && ch.read -> false
                readFilter == NovelChapterFlags.SHOW_READ && !ch.read -> false
                bookmarkFilter == NovelChapterFlags.SHOW_BOOKMARKED && !ch.bookmark -> false
                bookmarkFilter == NovelChapterFlags.SHOW_NOT_BOOKMARKED && ch.bookmark -> false
                downloadFilter == NovelChapterFlags.SHOW_DOWNLOADED && ch.id !in downloaded -> false
                downloadFilter == NovelChapterFlags.SHOW_NOT_DOWNLOADED && ch.id in downloaded -> false
                else -> true
            }
        }.mapTo(HashSet()) { it.id }
    }

    private fun load() {
        mutableState.value = NovelReaderState.Loading
        screenModelScope.launchIO { loadCurrent() }
    }

    private suspend fun loadCurrent() {
        incognitoMode = getIncognitoState.await(null)
        mutableState.value = try {
            if (orderedIds.isEmpty()) {
                // Source scope: just this novel's own chapters. Group scope (default): resolve the merge
                // group and aggregate the unified order in-reader, so History/Updates/Library need not
                // pass a list. A group-scoped chapter opened from History can be deduped out of the
                // unified list, so keep it (placed by chapter number) or prev/next would break.
                val resolved = if (sourceScoped) {
                    dedupIfEnabled(chapterRepo.getByNovelId(novelId).sortedWith(readingOrder())).map { it.id }
                } else {
                    val chapters = resolveGroupChapters()
                    val withCurrent = if (chapters.any { it.id == currentId }) {
                        chapters
                    } else {
                        val current = chapterRepo.getById(currentId)
                        if (current == null) chapters else (chapters + current).sortedWith(readingOrder())
                    }
                    withCurrent.map { it.id }
                }
                // Skip user-hidden chapters so prev/next matches the details list; the open chapter is
                // always kept (filterHiddenChapters guards currentId).
                orderedIds = filterHiddenChapters(resolved)
                forwardEligibleIds = resolveForwardEligible(orderedIds)
            }
            val id = currentId
            val chapter = chapterRepo.getById(id) ?: error("Chapter not found")
            currentNovelId = chapter.novelId
            chapterReadStartTime = System.currentTimeMillis()
            ttsController.setNowPlaying(chapter.name)
            val (html, baseUrl) = htmlCache[id] ?: loadChapterHtml(chapter).also { htmlCache[id] = it }
            resolveBothNeighbors()
            NovelReaderState.Loaded(
                chapterTitle = chapter.name,
                html = html,
                baseUrl = baseUrl,
                // Stored as 0..10000 (hundredths of a percent); the web layer wants 0..100.
                initialProgressPercent = (chapter.lastTextProgress / 100).coerceIn(0L, 100L).toInt(),
                hasPrev = resolvedPrev != null,
                hasNext = resolvedNext != null,
                // Only from an already-resolved source (so offline downloaded reading stays instant).
                webUrl = textLoader.cachedSource(chapter.novelId)?.webUrl(chapter.url),
                bookmarked = chapter.bookmark,
            )
        } catch (e: Throwable) {
            NovelReaderState.Failed(e.message ?: "Failed to load chapter")
        }
    }

    private suspend fun loadChapterHtml(chapter: NovelChapter): Pair<String, String?> =
        textLoader.load(chapter)

    /** Warm the resolved next chapter into the cache off-thread (skipped if already cached). One
     *  speculative request per chapter open, so it stays gentle on the source. */
    private fun prefetchNext() {
        val nextId = resolvedNext ?: return
        if (htmlCache.containsKey(nextId)) return
        screenModelScope.launchIO {
            runCatching {
                val next = chapterRepo.getById(nextId) ?: return@launchIO
                htmlCache[nextId] = loadChapterHtml(next)
            }
        }
    }

    private data class DisplayPrefs(
        val fontSize: Int,
        val lineHeight: Float,
        val textAlign: String,
        val padding: Int,
        val fontFamily: String,
    )
    private data class ThemePrefs(val followSystem: Boolean, val background: String, val textColor: String)
    private data class TtsPrefs(
        val enabled: Boolean,
        val rate: Float,
        val pitch: Float,
        val autoPageAdvance: Boolean,
        val scrollToTop: Boolean,
    )
    private data class FlagPrefs(
        val bionicReading: Boolean,
        val removeExtraSpacing: Boolean,
        val tapToScroll: Boolean,
        val swipeGestures: Boolean,
        val showProgressPercentage: Boolean,
    )
    private data class ScrollPrefs(
        val autoScroll: Boolean,
        val autoScrollSpeed: Float,
        val railHeight: Int,
        val railOnLeft: Boolean,
    )
    private data class VolumePrefs(val enabled: Boolean, val inverted: Boolean, val fraction: Float)
    private data class ReaderExtraPrefs(
        val tts: TtsPrefs,
        val flags: FlagPrefs,
        val scroll: ScrollPrefs,
        val volume: VolumePrefs,
    )

    override fun onDispose() {
        super.onDispose()
        ttsController.shutdown()
    }

    /** Per-novel orientation [override] + the global [default]; [resolved] is what the reader applies
     *  (the override, or the default when the override is DEFAULT/unset). */
    private data class OrientationPrefs(val override: Int, val default: Int) {
        val resolved: Int get() = if (override == ReaderOrientation.DEFAULT.flagValue) default else override
    }
}
