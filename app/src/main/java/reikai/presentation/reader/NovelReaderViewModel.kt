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
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryUpdate
import reikai.domain.novel.model.readerOrientation
import reikai.domain.novel.track.TrackNovelChapter
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.reader.NovelReaderSettings
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences

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
    private val trackNovelChapter: TrackNovelChapter,
    private val trackPreferences: TrackPreferences,
    private val getIncognitoState: GetIncognitoState,
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

    /** Sources resolved lazily per novelId. A merged reading session walks chapters from several
     *  novels, each with its own source, so cache per novelId rather than once. */
    private val sourcesByNovel: MutableMap<Long, NovelSource> =
        java.util.Collections.synchronizedMap(HashMap())

    /** [LnPluginInstaller.ensureLoaded] needs to run once before the first source resolve. */
    @Volatile
    private var pluginsLoaded = false

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

    /** Per-novel reader orientation override (a [ReaderOrientation] flagValue; 0 = follow the global
     *  default). Keyed on the opened entry [novelId] (the anchor for a merged novel), since orientation
     *  is a book-level preference like sort/filter rather than per-source progress. */
    private val orientationOverride = MutableStateFlow(ReaderOrientation.DEFAULT.flagValue)

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
        val html: String,
        val baseUrl: String?,
        val progressPercent: Int,
    )

    private val loadedChapter = MutableStateFlow<LoadedChapter?>(null)
    val chapter: StateFlow<LoadedChapter?> = loadedChapter

    init {
        // Seed the per-novel orientation from the opened entry (the anchor for a merged novel), which
        // is what the eager seed above cannot read without a DB hit.
        viewModelScope.launchIO {
            novelRepo.getById(novelId)?.let { orientationOverride.value = it.readerOrientation.toInt() }
        }
        open(initialChapterId)
    }

    /** Load [chapterId] into [chapter]. A missing row, an uninstalled source or a parse failure leaves
     *  the state as it was, so the host keeps rendering instead of tearing down. */
    fun open(chapterId: Long) {
        viewModelScope.launchIO {
            try {
                incognitoMode = getIncognitoState.await(null)
                val row = chapterRepo.getById(chapterId) ?: error("Chapter not found: $chapterId")
                currentNovelId = row.novelId
                chapterReadStartTime = System.currentTimeMillis()
                val (html, baseUrl) = loadChapterHtml(row)
                loadedChapter.value = LoadedChapter(
                    chapterId = row.id,
                    title = row.name,
                    html = html,
                    baseUrl = baseUrl,
                    // Stored as 0..10000 (hundredths of a percent); the web layer wants 0..100.
                    progressPercent = (row.lastTextProgress / 100).coerceIn(0L, 100L).toInt(),
                )
            } catch (e: Throwable) {
                // Leaving the reader cancels this scope, and swallowing that would report a load
                // failure for a chapter nobody is waiting for any more.
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter $chapterId" }
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

    /** Downloaded chapter -> read the self-contained HTML from disk (no source, null base URL, images
     *  already inlined). Otherwise resolve the chapter's source and parse live, using the source site
     *  as the base URL so relative image URLs resolve. */
    suspend fun loadChapterHtml(chapter: NovelChapter): Pair<String, String?> {
        val novel = novelRepo.getById(chapter.novelId)
        if (novel != null) downloadManager.getChapterText(novel, chapter)?.let { return it to null }
        val src = resolveSourceFor(chapter.novelId)
        return src.parseChapter(chapter.url) to src.site.ifBlank { null }
    }

    /** Resolve (and cache) the source owning [forNovelId]. Each chapter in a merged session resolves
     *  by its own `novelId`, so prev/next can cross source boundaries. */
    private suspend fun resolveSourceFor(forNovelId: Long): NovelSource {
        sourcesByNovel[forNovelId]?.let { return it }
        if (!pluginsLoaded) {
            runCatching { installer.ensureLoaded() }.onSuccess { pluginsLoaded = true }
        }
        val sourceId = novelRepo.getById(forNovelId)?.source ?: error("Novel not found")
        val resolved = sourceManager.get(sourceId) ?: error("Source not installed: $sourceId")
        sourcesByNovel[forNovelId] = resolved
        return resolved
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

    /** Per-novel orientation [override] + the global [default]; [resolved] is what the reader applies
     *  (the override, or the default when the override is DEFAULT/unset). */
    private data class OrientationPrefs(val override: Int, val default: Int) {
        val resolved: Int get() = if (override == ReaderOrientation.DEFAULT.flagValue) default else override
    }
}
