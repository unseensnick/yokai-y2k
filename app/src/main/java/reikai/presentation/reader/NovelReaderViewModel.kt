package reikai.presentation.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
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
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.toDownloadState
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.reader.NovelReaderSettings
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.Collections

/**
 * The light-novel provider model under the shared reader host: reader display settings, and the raw
 * chapter HTML the viewport renders. Settings live in their own flow so changing one updates the
 * rendered chapter live instead of reloading it.
 */
@AssistedInject
class NovelReaderViewModel(
    @Assisted val novelId: Long,
    @Assisted val initialChapterId: Long,
    /** Source scope walks only [novelId]'s own chapters; group scope aggregates the merge group. */
    @Assisted val sourceScoped: Boolean,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val novelPreferences: NovelPreferences,
    private val downloadManagerProvider: Provider<NovelDownloadManager>,
    private val upsertNovelHistory: UpsertNovelHistory,
    private val setNovelReadStatus: SetNovelReadStatus,
    // Merge-group resolution + the shared "mark duplicate read" pref, for marking same-numbered
    // chapters across a merged novel's sources read on completion (parity with the manga reader).
    private val mergeManager: NovelMergeManager,
    private val libraryPreferences: LibraryPreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val trackNovelChapter: TrackNovelChapter,
    private val trackPreferences: TrackPreferences,
    private val getIncognitoState: GetIncognitoState,
    private val setNovelViewerFlags: SetNovelViewerFlags,
    private val novelDownloadCache: NovelDownloadCache,
    private val context: Context,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(novelId: Long, initialChapterId: Long, sourceScoped: Boolean): NovelReaderViewModel
    }

    // Building the manager restores the persisted queue and can start the download worker, so it is
    // resolved on first read rather than at construction, which keeps that off the opening thread.
    private val downloadManager: NovelDownloadManager get() = downloadManagerProvider()

    /** Session-scoped, so the source cache inside it lives exactly as long as this reading session. */
    private val textLoader = NovelChapterTextLoader(
        novelRepo = novelRepo,
        sourceManager = sourceManager,
        installer = installer,
        preferences = novelPreferences,
        readDownloaded = { novel, chapter -> downloadManager.getChapterText(novel, chapter) },
    )

    /** Captured whenever a chapter opens (mirrors ReaderViewModel). Global-only: novel sources are
     *  String-keyed with no installed extension, so per-source incognito (await(sourceId)) can't apply. */
    @Volatile
    private var incognitoMode: Boolean = false

    /** Owning novel of the current chapter. Defaults to the host (== owner for a standalone novel);
     *  a merged session re-points it per chapter so the last-read stamp lands on the source read. */
    @Volatile
    private var currentNovelId: Long = novelId

    /** When the current chapter began being read, for the novel-history session duration (the analog of
     *  ReaderViewModel.chapterReadStartTime). Reset whenever a chapter loads. */
    @Volatile
    private var chapterReadStartTime: Long? = null

    /** Loading until the first chapter renders, so the host shows a spinner rather than a blank page.
     *  Declared above the init block that calls load(), which would otherwise write it before it exists. */
    val loadState = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Loading)

    fun retryLoad() = load()

    /** Per-novel reader orientation override (a [ReaderOrientation] flagValue; 0 = follow the global
     *  default). Keyed on the opened entry [novelId] (the anchor for a merged novel), since orientation
     *  is a book-level preference like sort/filter rather than per-source progress. */
    private val orientationOverride = MutableStateFlow(ReaderOrientation.DEFAULT.flagValue)

    fun setOrientation(flagValue: Int) {
        orientationOverride.value = flagValue
        viewModelScope.launchIO { setNovelViewerFlags.awaitSetOrientation(novelId, flagValue.toLong()) }
    }

    fun setKeepScreenOn(enabled: Boolean) = novelPreferences.readerKeepScreenOn().set(enabled)

    fun setFontSize(size: Int) = novelPreferences.readerFontSize().set(size)

    fun setFollowSystemTheme() = novelPreferences.readerFollowSystemTheme().set(true)

    /** Choosing a colour is choosing it over "Auto", so the follow-system flag clears with it. */
    fun setThemeColors(background: String, textColor: String) {
        novelPreferences.readerFollowSystemTheme().set(false)
        novelPreferences.readerBackgroundColor().set(background)
        novelPreferences.readerTextColor().set(textColor)
    }

    /** Reactive reader display settings; the screen resolves follow-system into colors. */
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, currentSettings())

    /** The chapter the viewport renders, or null while it is loading (or after a failed load). */
    data class LoadedChapter(
        val chapterId: Long,
        val title: String,
        /** The chapter path on its source, for the web actions. */
        val url: String,
        val html: String,
        val baseUrl: String?,
        val progressPercent: Int,
    )

    /** The opened entry's own title, which a merged session keeps even as chapters cross sources. */
    internal val entryTitle = MutableStateFlow<String?>(null)

    private val loadedChapter = MutableStateFlow<LoadedChapter?>(null)
    val chapter: StateFlow<LoadedChapter?> = loadedChapter

    private val liveProgress = MutableStateFlow(0)

    /**
     * How far down the open chapter the reader is, as a whole percent. Reported on every scroll frame,
     * which is what the navigator follows; [saveProgress] is the settled one that persists.
     */
    val progressPercent: StateFlow<Int> = liveProgress

    fun reportProgress(percent: Int) {
        liveProgress.value = percent.coerceIn(0, 100)
    }

    /** The chapter being read, which a merged session moves across sources. */
    @Volatile
    private var currentChapterId: Long = initialChapterId

    /** Every chapter this session can reach, in reading order, duplicates and hidden ones already gone. */
    @Volatile
    private var orderedIds: List<Long> = emptyList()

    /** Which of [orderedIds] a forward step may land on, per the skip settings. A back step ignores it,
     *  so the chapter just finished stays reachable from the one after it. */
    @Volatile
    private var forwardEligibleIds: Set<Long> = emptySet()

    private val neighbours = MutableStateFlow(Neighbours())

    /** What the navigator's chapter buttons enable on. */
    val chapterNeighbours: StateFlow<Neighbours> = neighbours

    data class Neighbours(val previous: Long? = null, val next: Long? = null)

    /** Chapters warmed by the forward prefetch, newest first, so a forward step renders without a
     *  round trip. Bounded, because a long session would otherwise hold every chapter it has read. */
    private val htmlCache: MutableMap<Long, Pair<String, String?>> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Pair<String, String?>>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<String, String?>>) =
                size > MAX_CACHED_CHAPTERS
        },
    )

    init {
        viewModelScope.launchIO {
            novelRepo.getById(novelId)?.let {
                orientationOverride.value = it.readerOrientation.toInt()
                entryTitle.value = it.title
            }
        }
        load()
    }

    /** Jump to [chapterId] from the chapter list. A no-op on the chapter already open. */
    fun open(chapterId: Long) {
        if (chapterId == currentChapterId && loadedChapter.value != null) return
        goTo(chapterId)
    }

    /** Forward only, so it is the step that can mark the departed chapter read. */
    fun nextChapter() = neighbours.value.next?.let { goTo(it, markDepartedRead = true) } ?: Unit

    fun previousChapter() = neighbours.value.previous?.let { goTo(it) } ?: Unit

    private fun goTo(chapterId: Long, markDepartedRead: Boolean = false) {
        viewModelScope.launchIO {
            // The departed chapter is stamped into history before the switch, and marked read while it
            // and its owning novel are still the current ones.
            updateHistory()
            if (markDepartedRead) markReadOnSkip(currentChapterId, currentNovelId)
            currentChapterId = chapterId
            load()
        }
    }

    /**
     * A missing row, an uninstalled source or a parse failure leaves the rendered chapter as it was
     * and reports [ReaderLoadState.Failed], so the host offers a retry rather than tearing down or
     * leaving the reader looking like nothing happened.
     */
    private fun load() {
        loadState.value = ReaderLoadState.Loading
        viewModelScope.launchIO {
            try {
                incognitoMode = getIncognitoState.await(null)
                if (orderedIds.isEmpty()) resolveReadingOrder()
                val row = chapterRepo.getById(currentChapterId) ?: error("Chapter not found: $currentChapterId")
                currentNovelId = row.novelId
                chapterReadStartTime = System.currentTimeMillis()
                bookmarkedState.value = row.bookmark
                val (html, baseUrl) = htmlCache[row.id] ?: loadChapterHtml(row).also { htmlCache[row.id] = it }
                loadedChapter.value = LoadedChapter(
                    chapterId = row.id,
                    title = row.name,
                    url = row.url,
                    html = html,
                    baseUrl = baseUrl,
                    // Stored as 0..10000 (hundredths of a percent); the web layer wants 0..100.
                    progressPercent = (row.lastTextProgress / 100).coerceIn(0L, 100L).toInt(),
                ).also { liveProgress.value = it.progressPercent }
                resolveNeighbours()
                loadState.value = ReaderLoadState.Idle
            } catch (e: Throwable) {
                // Leaving the reader cancels this scope, and swallowing that would report a load
                // failure for a chapter nobody is waiting for any more.
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter $currentChapterId" }
                loadState.value = ReaderLoadState.Failed(e.message)
            }
        }
    }

    /** Persist the reader's scroll position. The web layer reports a whole percent (0..100); store it
     *  as 0..10000 to match [NovelChapter.lastTextProgress]. Reaching the end auto-marks read. */
    fun saveProgress(percent: Int) {
        if (incognitoMode) return
        val id = loadedChapter.value?.chapterId ?: return
        val clamped = percent.coerceIn(0, 100)
        viewModelScope.launchIO {
            chapterRepo.setLastTextProgress(id, clamped * 100L)
            // Stamp the owning novel's last-read time so the LastRead library sort reflects this read.
            novelRepo.setLastReadAt(currentNovelId, System.currentTimeMillis())
            if (clamped >= 97) {
                // Fetch before marking so the shared interactor sees the chapter as still unread; it flips
                // read + honors "delete after marked as read".
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
                    chapter?.let { trackNovelChapter.await(context, currentNovelId, it.chapterNumber) }
                }
            }
        }
    }

    /** Stamp the current chapter into novel history and accumulate this session's read time. Called on
     *  chapter switch and on leaving the reader (the novel twin of ReaderViewModel.updateHistory). */
    suspend fun updateHistory() {
        if (incognitoMode) return
        val id = loadedChapter.value?.chapterId ?: return
        val now = System.currentTimeMillis()
        val duration = chapterReadStartTime?.let { now - it } ?: 0L
        upsertNovelHistory.await(NovelHistoryUpdate(id, now, duration))
        chapterReadStartTime = null
    }

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

    /**
     * The chapter sheet's rows, in reading order. Cold, so the list is only built while the sheet is
     * open, and re-emitted as downloads move or the reader changes chapter.
     */
    val chapterRows: Flow<List<ReaderChapterRow>> = flow {
        if (orderedIds.isEmpty()) resolveReadingOrder()
        val anchor = chapterRepo.getByNovelId(novelId).associateBy { it.id }
        val chapters = orderedIds.mapNotNull { id -> anchor[id] ?: chapterRepo.getById(id) }
        val sourceNames = chapterSourceNames(chapters)
        emitAll(
            combine(downloadManager.queueState, loadedChapter) { queue, _ ->
                val downloaded = downloadedChapterIds(chapters)
                val queued = queue.associateBy { it.chapterId }
                chapters.map { it.toRow(sourceNames, queued, downloaded) }
            },
        )
    }.flowOn(Dispatchers.IO)

    private fun NovelChapter.toRow(
        sourceNames: Map<Long, String>,
        queued: Map<Long, NovelDownload>,
        downloaded: Set<Long>,
    ) = ReaderChapterRow(
        id = id,
        title = name,
        // A novel has no scanlator, so the only subtitle is which source a merged group's chapter is from.
        subtitle = sourceNames[novelId],
        dateUpload = dateUpload,
        readProgress = (lastTextProgress / 100L).toInt().takeIf { it > 0 }?.let { "$it%" },
        read = read,
        bookmark = bookmark,
        downloadState = when {
            queued[id] != null -> queued.getValue(id).state.toDownloadState()
            id in downloaded -> Download.State.DOWNLOADED
            else -> Download.State.NOT_DOWNLOADED
        },
        // A novel chapter is one request, so there is no percentage to report while it runs.
        downloadProgress = 0,
    )

    /** Per-source display names keyed by novelId, for a merged novel's source labels. Empty for a
     *  single-source novel, so no label is drawn. */
    private suspend fun chapterSourceNames(chapters: List<NovelChapter>): Map<Long, String> {
        val novelIds = chapters.map { it.novelId }.distinct()
        if (novelIds.size <= 1) return emptyMap()
        return novelIds.associateWith { id ->
            textLoader.cachedSource(id)?.name
                ?: novelRepo.getById(id)?.source?.let { sourceManager.get(it)?.name ?: it }
                ?: ""
        }
    }

    fun setChapterRead(chapterId: Long, read: Boolean) {
        viewModelScope.launchIO {
            chapterRepo.getById(chapterId)?.let { setNovelReadStatus.await(read, listOf(it)) }
        }
    }

    fun setChapterBookmark(chapterId: Long, bookmarked: Boolean) {
        // Kept in step so the sheet and the app bar cannot disagree about the chapter being read.
        if (chapterId == currentChapterId) bookmarkedState.value = bookmarked
        viewModelScope.launchIO { chapterRepo.setBookmark(chapterId, bookmarked) }
    }

    /** The open chapter's bookmark state, seeded when it loads and flipped from the bar or the sheet. */
    private val bookmarkedState = MutableStateFlow(false)
    val bookmarked: StateFlow<Boolean> = bookmarkedState

    fun toggleBookmark() = setChapterBookmark(currentChapterId, !bookmarkedState.value)

    /**
     * [chapter]'s page on the source site, or null for one read from disk whose source this session
     * never resolved, which is the case the web actions have to hide rather than open empty.
     */
    fun webUrlFor(chapter: LoadedChapter): String? =
        textLoader.cachedSource(currentNovelId)?.webUrl(chapter.url)

    /** Start, cancel or delete a chapter download from the sheet, mirroring the details model. */
    fun downloadChapter(chapterId: Long, action: ChapterDownloadAction) {
        viewModelScope.launchIO {
            val chapter = chapterRepo.getById(chapterId) ?: return@launchIO
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
    }

    /**
     * Builds the order the reader pages in, once per session. Source scope walks the opened novel's own
     * chapters; group scope aggregates the merge group, so History, Updates and the library need not
     * pass a list. A group-scoped chapter can be deduped out of the unified list, so it is put back
     * (placed by chapter number) rather than leaving prev and next with nowhere to step from.
     */
    private suspend fun resolveReadingOrder() {
        val resolved = if (sourceScoped) {
            dedupIfEnabled(chapterRepo.getByNovelId(novelId).sortedWith(readingOrder())).map { it.id }
        } else {
            val chapters = resolveGroupChapters()
            val withCurrent = if (chapters.any { it.id == currentChapterId }) {
                chapters
            } else {
                val current = chapterRepo.getById(currentChapterId)
                if (current == null) chapters else (chapters + current).sortedWith(readingOrder())
            }
            withCurrent.map { it.id }
        }
        orderedIds = filterHiddenChapters(resolved)
        forwardEligibleIds = resolveForwardEligible(orderedIds)
    }

    /** The opened novel's own chapter sort, always ascending, so paging follows the order the user chose
     *  on its chapter list. The manga reader resolves the same way. */
    private suspend fun readingOrder(): Comparator<NovelChapter> {
        val novel = novelRepo.getById(novelId)
        return if (novel == null) compareBy { it.chapterNumber } else readingOrderComparator(novel, novelPreferences)
    }

    /** The merge group's unified chapters, the novel twin of `MergedChapterProvider`. A non-merged novel
     *  is just its own. The global preferred-source ranking picks the trunk, as details and library do. */
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
     * Drops same-numbered duplicates from the list rather than stepping over them, so the chapter list,
     * download-ahead and delete-after-read all count what the reader actually shows. A novel has no
     * scanlator, so its source stands in as the origin the current chapter prefers.
     */
    private fun dedupIfEnabled(
        chapters: List<NovelChapter>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
    ): List<NovelChapter> {
        if (!novelPreferences.readerSkipDuplicateChapters().get()) return chapters
        val current = chapters.find { it.id == currentChapterId } ?: return chapters
        return chapters.removeDuplicateChapters(
            current,
            numberOf = { it.chapterNumber },
            idOf = { it.id },
            originOf = { sourceIdByNovel[it.novelId] },
        )
    }

    /** Drops user-hidden chapters so paging matches the details list. The open chapter is always kept,
     *  so opening a hidden one directly still resolves. The key mirrors the details screen. */
    private suspend fun filterHiddenChapters(ids: List<Long>): List<Long> {
        val hidden = novelPreferences.hiddenChapters().get()
        if (hidden.isEmpty()) return ids
        val anchor = chapterRepo.getByNovelId(novelId).associateBy { it.id }
        val sourceIdByNovel = HashMap<Long, String>()
        return ids.filter { id ->
            if (id == currentChapterId) return@filter true
            val chapter = anchor[id] ?: chapterRepo.getById(id) ?: return@filter true
            val sourceId = sourceIdByNovel.getOrPut(chapter.novelId) {
                novelRepo.getById(chapter.novelId)?.source.orEmpty()
            }
            "$sourceId|${chapter.url}" !in hidden
        }
    }

    /**
     * Which chapters a forward step may stop on. "Skip read" drops read ones; "skip filtered" drops the
     * ones this novel's own chapter-list filters hide, which is what makes the setting mean the same
     * thing here as on the details screen. The open chapter stays eligible either way.
     */
    private suspend fun resolveForwardEligible(ids: List<Long>): Set<Long> {
        val skipRead = novelPreferences.readerSkipRead().get()
        val skipFiltered = novelPreferences.readerSkipFiltered().get()
        if (!skipRead && !skipFiltered) return ids.toSet()
        val novel = novelRepo.getById(novelId) ?: return ids.toSet()
        val chapters = ids.mapNotNull { chapterRepo.getById(it) }
        val downloaded = if (skipFiltered) downloadedChapterIds(chapters) else emptySet()
        val readFilter = novel.effectiveReadFilter(novelPreferences)
        val bookmarkFilter = novel.effectiveBookmarkedFilter(novelPreferences)
        val downloadFilter = novel.effectiveDownloadedFilter(novelPreferences)
        return chapters.filterTo(HashSet()) { ch ->
            when {
                ch.id == currentChapterId -> true
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

    /** Grouped by novel so the cache resolves each novel's download folder once rather than per chapter,
     *  which is what a merged group's long list would otherwise pay for on every queue change. */
    private suspend fun downloadedChapterIds(chapters: List<NovelChapter>): Set<Long> {
        val novelsById = chapters.map { it.novelId }.distinct().mapNotNull { novelRepo.getById(it) }
            .associateBy { it.id }
        return chapters
            .groupBy { it.novelId }
            .flatMapTo(HashSet()) { (novelId, owned) ->
                novelsById[novelId]?.let { novelDownloadCache.downloadedChapterIds(it, owned) }.orEmpty()
            }
    }

    /** Re-resolves both neighbours, then warms the next chapter and queues the download-ahead window. */
    private suspend fun resolveNeighbours() {
        val index = orderedIds.indexOf(currentChapterId)
        neighbours.value = Neighbours(
            previous = orderedIds.neighbourChapter(index, forward = false) { it in forwardEligibleIds },
            next = orderedIds.neighbourChapter(index, forward = true) { it in forwardEligibleIds },
        )
        prefetchNext()
        maybeDownloadAhead()
    }

    /** One speculative request per chapter open, so a forward step is instant and the source is not hit
     *  harder than a reader moving through it would. */
    private fun prefetchNext() {
        val nextId = neighbours.value.next ?: return
        if (htmlCache.containsKey(nextId)) return
        viewModelScope.launchIO {
            try {
                val next = chapterRepo.getById(nextId) ?: return@launchIO
                htmlCache[nextId] = loadChapterHtml(next)
            } catch (e: Throwable) {
                // A speculative fetch failing is not the reader's problem, but swallowing the
                // cancellation would report a warm-up failure for a session that is gone.
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Failed to prefetch novel chapter $nextId" }
            }
        }
    }

    /** Enqueues the next N un-downloaded chapters in reading order, the novel twin of manga's
     *  autoDownloadWhileReading. Off in incognito and when the setting is zero. */
    private suspend fun maybeDownloadAhead() {
        if (incognitoMode) return
        val ahead = novelPreferences.autoDownloadWhileReading().get()
        if (ahead <= 0) return
        val index = orderedIds.indexOf(currentChapterId)
        if (index < 0) return
        val toDownload = orderedIds.drop(index + 1).take(ahead)
            .mapNotNull { chapterRepo.getById(it) }
            .filterNot { ch ->
                val novel = novelRepo.getById(ch.novelId) ?: return@filterNot false
                downloadManager.isChapterDownloaded(novel, ch)
            }
        if (toDownload.isNotEmpty()) downloadManager.downloadChapters(toDownload)
    }

    /** Marks the chapter the user skipped away from as read, forward only, when the setting is on. */
    private suspend fun markReadOnSkip(departedId: Long, departedNovelId: Long) {
        if (incognitoMode || !novelPreferences.readerMarkReadOnSkip().get()) return
        val chapter = chapterRepo.getById(departedId) ?: return
        if (chapter.read) return
        chapterRepo.setReadBulk(listOf(departedId), true)
        if (trackPreferences.autoUpdateTrack.get()) {
            trackNovelChapter.await(context, departedNovelId, chapter.chapterNumber)
        }
    }

    suspend fun loadChapterHtml(chapter: NovelChapter): Pair<String, String?> = textLoader.load(chapter)

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

    /** Per-novel orientation [override] + the global [default]; [resolved] is what the reader applies
     *  (the override, or the default when the override is DEFAULT/unset). */
    private data class OrientationPrefs(val override: Int, val default: Int) {
        val resolved: Int get() = if (override == ReaderOrientation.DEFAULT.flagValue) default else override
    }
}

/** Chapters held in the forward-prefetch cache. Small: it exists to make one step instant, not to
 *  keep a session's reading in memory. */
private const val MAX_CACHED_CHAPTERS = 5
