package reikai.presentation.reader

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.reader.ChapterProgress

/**
 * The light-novel half of the reader's provider seam, over the live [NovelReaderViewModel] the host
 * resolved. The twin of `MangaReaderProvider`, and thin for the same reason: everything that decides
 * behaviour belongs to the model, so this only adapts.
 */
class NovelReaderProvider(
    val viewModel: NovelReaderViewModel,
    private val novelPreferences: NovelPreferences,
) : ReaderProvider {

    override val chrome: Flow<ReaderChromeState> =
        combine(viewModel.entryTitle, viewModel.chapter) { title, chapter ->
            ReaderChromeState(title, chapter?.title)
        }

    override val bottomButtons: Flow<Set<String>> = novelPreferences.readerBottomButtons().changes()

    // Always the rail: a chapter is one continuous page, so there is nothing for a horizontal bar to
    // step through. Hundredths, because that is the unit the stored progress is in.
    override val navigator: Flow<ReaderNavigatorState> = combine(
        viewModel.progressPercent,
        viewModel.settings,
        viewModel.chapterNeighbours,
    ) { percent, settings, neighbours ->
        ReaderNavigatorState(
            progress = ChapterProgress.Percent(percent * 100L),
            useRail = true,
            railOnLeft = settings.railOnLeft,
            railHeightPercent = settings.railHeightPercent,
            hasPrevious = neighbours.previous != null,
            hasNext = neighbours.next != null,
        )
    }

    override val showProgress: Flow<Boolean> = viewModel.settings.map { it.showProgressPercentage }

    override val loadState: Flow<ReaderLoadState> = viewModel.loadState

    override fun retryLoad() = viewModel.retryLoad()

    override val bookmarked: Flow<Boolean> = viewModel.bookmarked

    override fun toggleBookmark() = viewModel.toggleBookmark()

    override val webUrl: Flow<String?> = viewModel.chapter.map { it?.let(viewModel::webUrlFor) }

    override suspend fun previousChapter() = viewModel.previousChapter()

    override suspend fun nextChapter() = viewModel.nextChapter()

    override val chapterList: ReaderChapterList = object : ReaderChapterList {
        override val rows: Flow<List<ReaderChapterRow>> = viewModel.chapterRows

        override val currentChapterId: Flow<Long> = viewModel.chapter.map { it?.chapterId ?: -1L }

        override fun open(chapterId: Long) = viewModel.open(chapterId)

        override fun setRead(chapterId: Long, read: Boolean) = viewModel.setChapterRead(chapterId, read)

        override fun setBookmark(chapterId: Long, bookmarked: Boolean) =
            viewModel.setChapterBookmark(chapterId, bookmarked)

        override fun download(chapterId: Long, action: ChapterDownloadAction) =
            viewModel.downloadChapter(chapterId, action)
    }

    override val textSettings: ReaderTextSettings = object : ReaderTextSettings {
        override val state: Flow<ReaderTextState> = viewModel.settings.map {
            // The stored colour, not the resolved one: the picker marks what was chosen, and "Auto"
            // is a choice of its own rather than whichever preset it resolves to right now.
            ReaderTextState(it.fontSize, it.followSystemTheme, it.backgroundColor)
        }

        override fun setFontSize(size: Int) = viewModel.setFontSize(size)

        override fun followSystemTheme() = viewModel.setFollowSystemTheme()

        override fun setThemeColors(background: String, textColor: String) =
            viewModel.setThemeColors(background, textColor)
    }

    override val autoScroll: ReaderAutoScroll = object : ReaderAutoScroll {
        override val enabled: Flow<Boolean> = viewModel.settings.map { it.autoScroll }

        override fun toggle() = viewModel.setAutoScroll(!viewModel.settings.value.autoScroll)

        override fun stop() {
            if (viewModel.settings.value.autoScroll) viewModel.setAutoScroll(false)
        }
    }

    override val orientation: Flow<Int> = viewModel.settings.map { it.orientation }

    override val keepScreenOn: Flow<Boolean> = viewModel.settings.map { it.keepScreenOn }

    override fun setOrientation(flagValue: Int) = viewModel.setOrientation(flagValue)

    override fun setKeepScreenOn(enabled: Boolean) = viewModel.setKeepScreenOn(enabled)

    /**
     * The volume-key values and text-selectability are read once here, because the viewport takes them
     * as plain values so it can be built without the graph. Nothing rebuilds a novel viewport
     * mid-session, so a change lands on the next open. That is only acceptable while none of them is
     * reachable from the in-reader sheet: putting one there needs a viewport rebuild, and for
     * text-selectability also a re-bind, since it decides which of the two tap owners is installed.
     */
    override fun createViewport(host: ReaderActivity): ReaderViewport {
        val settings = viewModel.settings.value
        if (novelPreferences.readerRenderingMode().get() == NovelRenderingMode.NATIVE) {
            return NovelTextViewport(
                context = host,
                textSelectable = novelPreferences.readerTextSelectable().get(),
                volumeKeysEnabled = settings.useVolumeButtons,
                volumeKeysInverted = settings.volumeButtonsInverted,
                volumeKeyScrollFraction = settings.volumeButtonsFraction,
                onProgressChanged = viewModel::reportProgress,
                onProgressSettled = viewModel::saveProgress,
                onToggleMenu = host::toggleMenu,
                onStepChapter = { forward ->
                    if (forward) host.engine.nextChapter() else host.engine.previousChapter()
                },
            )
        }
        return NovelWebViewport(
            context = host,
            volumeKeysEnabled = settings.useVolumeButtons,
            volumeKeysInverted = settings.volumeButtonsInverted,
            volumeKeyScrollFraction = settings.volumeButtonsFraction,
            // The live percent drives the navigator; the settled one persists, and it carries
            // mark-as-read and the tracker push with it.
            onProgressChanged = viewModel::reportProgress,
            onProgressSettled = viewModel::saveProgress,
            // Taken from the host being built against rather than held, so a reader rebuilt after a
            // rotation toggles the live Activity's menu instead of the destroyed one's.
            onToggleMenu = host::toggleMenu,
            // Through the engine rather than the model, so a swipe is sequenced with the viewport the
            // same way the bar's step buttons are.
            onStepChapter = { forward -> if (forward) host.engine.nextChapter() else host.engine.previousChapter() },
            statusBarHeightPx = host::displayCutoutTopDp,
        )
    }
}
