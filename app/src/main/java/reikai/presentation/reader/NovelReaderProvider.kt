package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * The light-novel half of the reader's provider seam, over the live [NovelReaderViewModel] the host
 * resolved. The twin of `MangaReaderProvider`, and thin for the same reason: everything that decides
 * behaviour belongs to the model, so this only adapts.
 */
class NovelReaderProvider(
    val viewModel: NovelReaderViewModel,
    private val onToggleMenu: () -> Unit,
) : ReaderProvider {

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
            // Both are unconsumed until the host owns novel chrome and reading progress. Progress is
            // one behaviour, not two: the stored position, the history row, tracking and the
            // mark-as-read threshold all hang off the settled percent, so they arrive together.
            onProgressChanged = {},
            onProgressSettled = {},
            onToggleMenu = onToggleMenu,
        )
    }
}
