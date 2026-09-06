package reikai.presentation.reader

import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import tachiyomi.source.local.isLocal
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manga's answers, over the live [ReaderViewModel] the host already resolved. It stays Mihon's and
 * stays synced; this only adapts it.
 */
class MangaReaderProvider(
    private val viewModel: ReaderViewModel,
    private val readerPreferences: ReaderPreferences,
    private val downloadManager: DownloadManager,
) : ReaderProvider {

    // The visible chapter rather than the active one: they differ mid-scroll across a boundary, and
    // pairing a title from one with a page count from the other is the chrome tear.
    override val chrome: Flow<ReaderChromeState> = viewModel.state
        .map { ReaderChromeState(it.manga?.title, it.visibleChapter?.chapter?.name) }

    override val bottomButtons: Flow<Set<String>> = readerPreferences.readerBottomButtons.changes()

    override val navigator: Flow<ReaderNavigatorState> = combine(
        viewModel.state,
        readerPreferences.verticalNavigator.changes(),
        readerPreferences.verticalNavigatorOnLeft.changes(),
        readerPreferences.verticalNavigatorHeight.changes(),
    ) { state, railModes, onLeft, height ->
        ReaderNavigatorState(
            progress = state.position?.progress,
            // The resolved mode, so a series on auto-webtoon gets the rail its actual mode asks for.
            useRail = ReadingMode.fromPreference(viewModel.getMangaReadingMode()) in railModes,
            railOnLeft = onLeft,
            railHeightPercent = height,
            hasPrevious = state.viewerChapters?.prevChapter != null,
            hasNext = state.viewerChapters?.nextChapter != null,
        )
    }

    override val showProgress: Flow<Boolean> = readerPreferences.showPageNumber.changes()

    // Manga reports only the in-flight half. A chapter that cannot open at all is upstream's
    // initError, which closes the reader with its own message rather than offering a retry.
    override val loadState: Flow<ReaderLoadState> = viewModel.state
        .map { if (it.isLoadingAdjacentChapter) ReaderLoadState.Loading else ReaderLoadState.Idle }

    // Re-opened through the sheet's own path, which resolves against the live chapter list rather
    // than the reader's copy, so a retry lands the same way picking the chapter by hand would.
    override fun retryLoad() {
        val id = viewModel.state.value.currentChapter?.chapter?.id ?: return
        chapterList.open(id)
    }

    override val bookmarked: Flow<Boolean> = viewModel.state.map { it.bookmarked }

    override fun toggleBookmark() = viewModel.toggleChapterBookmark()

    // Re-read per chapter rather than per state emission: the source builds the URL, and the chapter
    // is the only thing about it that changes while a session runs.
    override val webUrl: Flow<String?> = viewModel.state
        .map { it.viewerChapters?.currChapter?.chapter?.id }
        .distinctUntilChanged()
        .map { viewModel.getChapterUrl() }

    override suspend fun previousChapter() = viewModel.loadPreviousChapter()

    override suspend fun nextChapter() = viewModel.loadNextChapter()

    override val chapterList: ReaderChapterList = object : ReaderChapterList {

        /**
         * The disk check is the expensive half (a folder-name hash per chapter), so it runs once per
         * queue change, which is also when a finished download leaves the queue. Only the progress
         * numbers refresh on the sampled tick, and they are read off the live queue entries.
         */
        override val rows: Flow<List<ReaderChapterRow>> = downloadManager.queueState
            .flatMapLatest { queue ->
                val chapters = viewModel.getChapters()
                val queued = queue.associateBy { it.chapter.id }
                val downloaded = chapters.filterTo(HashSet(), ::isDownloaded).mapTo(HashSet()) { it.chapter.id }
                val build = { chapters.map { it.toRow(queued, downloaded) } }
                if (queued.isEmpty()) {
                    flowOf(build())
                } else {
                    downloadManager.progressFlow().sample(PROGRESS_SAMPLE).map { build() }.onStart { emit(build()) }
                }
            }
            .flowOn(Dispatchers.IO)

        override val currentChapterId: Flow<Long> =
            viewModel.state.map { it.currentChapter?.chapter?.id ?: -1L }

        override fun open(chapterId: Long) {
            viewModel.getChapters().find { it.chapter.id == chapterId }
                ?.let { viewModel.loadNewChapterFromDialog(it.chapter) }
        }

        override fun setRead(chapterId: Long, read: Boolean) {
            chapterOf(chapterId)?.let { viewModel.setChapterReadStatus(it, read) }
        }

        override fun setBookmark(chapterId: Long, bookmarked: Boolean) =
            viewModel.toggleBookmark(chapterId, bookmarked)

        override fun download(chapterId: Long, action: ChapterDownloadAction) {
            chapterOf(chapterId)?.let { viewModel.handleChapterDownload(it, action) }
        }

        private fun chapterOf(chapterId: Long) =
            viewModel.getChapters().find { it.chapter.id == chapterId }?.chapter
    }

    private fun isDownloaded(item: ReaderChapterItem) = item.manga.isLocal() ||
        downloadManager.isChapterDownloaded(
            item.chapter.name,
            item.chapter.scanlator,
            item.chapter.url,
            item.manga.title,
            item.manga.source,
        )

    private fun ReaderChapterItem.toRow(queued: Map<Long, Download>, downloaded: Set<Long>): ReaderChapterRow {
        val active = queued[chapter.id]
        return ReaderChapterRow(
            id = chapter.id,
            title = chapter.name,
            // In a merged group the source leads, then the scanlator, so a unified list says where each
            // chapter came from.
            subtitle = listOfNotNull(sourceName, chapter.scanlator).joinToString(" • ").ifEmpty { null },
            dateUpload = chapter.dateUpload,
            // The page a manga chapter was left on is not shown here, as upstream does not show it.
            readProgress = null,
            read = chapter.read,
            bookmark = chapter.bookmark,
            downloadState = when {
                active != null -> active.status
                chapter.id in downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            },
            downloadProgress = active?.progress ?: 0,
        )
    }

    // A manga page is an image the source ships, so there is no text for a size or a page colour to
    // act on. The buttons are absent rather than shown doing nothing.
    override val textSettings: ReaderTextSettings? = null

    // Unresolved, because the picker's "use default" action has to be able to tell a series following
    // the default from one pinned to the same value the default happens to be.
    override val orientation: Flow<Int> = viewModel.state
        .map { it.manga?.readerOrientation?.toInt() ?: ReaderOrientation.DEFAULT.flagValue }

    override val keepScreenOn: Flow<Boolean> = readerPreferences.keepScreenOn.changes()

    override fun setOrientation(flagValue: Int) =
        viewModel.setMangaOrientationType(ReaderOrientation.fromPreference(flagValue))

    override fun setKeepScreenOn(enabled: Boolean) = readerPreferences.keepScreenOn.set(enabled)

    // Resolved rather than read off the stored flag, because auto-webtoon overrides a series to long
    // strip at open time without writing the preference.
    override fun createViewport(host: ReaderActivity): ReaderViewport =
        MangaViewport(
            viewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), host),
            pageAt = { index -> viewModel.state.value.currentChapter?.pages?.getOrNull(index) },
        )

    /**
     * Binds the page-action verbs to one page, so the dialog carries a capability rather than a
     * manga type and the verbs need no argument. Novels build no equivalent, which is what makes
     * that dialog unreachable for them instead of present and dead.
     */
    fun pageActions(page: ReaderPage): ReaderPageActions = object : ReaderPageActions {
        override fun save() = viewModel.saveImage(page)

        override fun share(copyToClipboard: Boolean) = viewModel.shareImage(page, copyToClipboard)

        override fun setAsCover() = viewModel.setAsCover(page)
    }
}

/** How often a running download refreshes the sheet. Rebuilding the whole list on every reported frame
 *  would recompose it many times a second for a spinner that cannot show that detail. */
private val PROGRESS_SAMPLE = 500.milliseconds
