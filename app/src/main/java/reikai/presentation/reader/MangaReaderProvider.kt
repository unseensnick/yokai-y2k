package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Manga's answers, over the live [ReaderViewModel] the host already resolved. It stays Mihon's and
 * stays synced; this only adapts it. The scope is the host's, since the chrome flow outlives no
 * single view and must not outlive the session.
 */
class MangaReaderProvider(
    private val viewModel: ReaderViewModel,
    scope: CoroutineScope,
) : ReaderProvider {

    // The visible chapter rather than the active one: they differ mid-scroll across a boundary, and
    // pairing a title from one with a page count from the other is the chrome tear.
    override val chrome: StateFlow<ReaderChromeState> = viewModel.state
        .map { ReaderChromeState(it.manga?.title, it.visibleChapter?.chapter?.name) }
        .stateIn(scope, SharingStarted.Eagerly, ReaderChromeState())

    // Resolved rather than read off the stored flag, because auto-webtoon overrides a series to long
    // strip at open time without writing the preference.
    override fun createViewport(host: ReaderActivity): ReaderViewport =
        MangaViewport(ReadingMode.toViewer(viewModel.getMangaReadingMode(), host))

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
