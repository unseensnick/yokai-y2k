package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * What the host may ask of whatever renders the entry, with no manga type in any signature. Mihon's
 * `Viewer` is an adapter under this rather than the interface a novel viewer implements, because
 * `ViewerChapters` cannot carry a novel chapter without treating novels as manga.
 *
 * Chapter delivery and page movement are absent for that same reason, and stay on the manga adapter
 * until novels define what a chapter set is.
 */
interface ReaderViewport {

    val view: View

    /**
     * Right to left. The only shape question the host itself asks, to point the chapter navigator,
     * and neutral because every content type has a reading direction. Which viewer implementation is
     * running is a different question, and it belongs to whichever adapter owns those viewers.
     */
    val isRtl: Boolean

    fun destroy()

    fun handleKeyEvent(event: KeyEvent): Boolean

    fun handleGenericMotionEvent(event: MotionEvent): Boolean
}
