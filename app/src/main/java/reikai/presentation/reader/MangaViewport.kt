package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer

/**
 * The only adapter under [ReaderViewport] today, over whatever `ReadingMode.toViewer` built. The
 * three image viewers stay unedited: everything neutral about them is expressible from out here.
 *
 * [viewer] is public because the reader settings sheet asks which viewer implementation is running,
 * which is a manga question rather than a neutral one, so it is answered by unwrapping this adapter
 * instead of by widening the contract.
 */
class MangaViewport(val viewer: Viewer) : ReaderViewport {

    override val view: View
        get() = viewer.getView()

    // The two viewer families spell right-to-left differently, and this is the one place that knows.
    override val isRtl: Boolean
        get() = viewer is R2LPagerViewer || (viewer as? WebGpuViewer)?.isReversed == true

    override fun destroy() = viewer.destroy()

    override fun handleKeyEvent(event: KeyEvent): Boolean = viewer.handleKeyEvent(event)

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = viewer.handleGenericMotionEvent(event)
}
