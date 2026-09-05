package reikai.presentation.reader

import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manga's answers, over the live [ReaderViewModel] the host already resolved. It stays Mihon's and
 * stays synced; this only adapts it.
 */
class MangaReaderProvider(
    private val viewModel: ReaderViewModel,
    private val readerPreferences: ReaderPreferences,
) : ReaderProvider {

    // The visible chapter rather than the active one: they differ mid-scroll across a boundary, and
    // pairing a title from one with a page count from the other is the chrome tear.
    override val chrome: Flow<ReaderChromeState> = viewModel.state
        .map { ReaderChromeState(it.manga?.title, it.visibleChapter?.chapter?.name) }

    override val bottomButtons: Flow<Set<String>> = readerPreferences.readerBottomButtons.changes()

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
