package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode

/**
 * Manga's answers, over the live [ReaderViewModel] the host already resolved. It stays Mihon's and
 * stays synced; this only adapts it. Stateless, so which instance answers does not matter.
 */
class MangaReaderProvider(private val viewModel: ReaderViewModel) : ReaderProvider {

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
