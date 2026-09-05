package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The light-novel half of the reader's provider seam, over the live [NovelReaderViewModel] the host
 * resolved. The twin of `MangaReaderProvider`, and thin for the same reason: everything that decides
 * behaviour belongs to the model, so this only adapts.
 */
class NovelReaderProvider(
    val viewModel: NovelReaderViewModel,
    scope: CoroutineScope,
    private val onToggleMenu: () -> Unit,
) : ReaderProvider {

    override val chrome: StateFlow<ReaderChromeState> =
        combine(viewModel.entryTitle, viewModel.chapter) { title, chapter ->
            ReaderChromeState(title, chapter?.title)
        }.stateIn(scope, SharingStarted.Eagerly, ReaderChromeState())

    /**
     * The volume-key values are read once, here, because the viewport takes them as plain values so it
     * can be built without the graph. The host rebuilds the viewport when they change, which is the
     * same swap a reading-mode change already performs.
     */
    override fun createViewport(host: ReaderActivity): ReaderViewport {
        val settings = viewModel.settings.value
        return NovelWebViewport(
            context = host,
            volumeKeysEnabled = settings.useVolumeButtons,
            volumeKeysInverted = settings.volumeButtonsInverted,
            volumeKeyScrollFraction = settings.volumeButtonsFraction,
            // Live progress has no consumer until the host owns novel chrome; the settled percent is
            // the one that persists, and it carries mark-as-read and trackers with it.
            onProgressChanged = {},
            onProgressSettled = viewModel::saveProgress,
            onToggleMenu = onToggleMenu,
        )
    }
}
