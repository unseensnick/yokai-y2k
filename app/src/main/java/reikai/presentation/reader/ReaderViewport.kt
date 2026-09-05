package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import reikai.domain.reader.ChapterProgress

/**
 * What the host may ask of whatever renders the entry, with no manga type in any signature. Mihon's
 * `Viewer` is an adapter under this rather than the interface a novel viewer implements, because
 * `ViewerChapters` cannot carry a novel chapter without treating novels as manga.
 *
 * Chapter delivery is absent for that same reason, and stays on the manga adapter until novels
 * define what a chapter set is.
 */
interface ReaderViewport {

    val view: View

    /**
     * Right to left. The only shape question the host itself asks, to point the chapter navigator,
     * and neutral because every content type has a reading direction. Which viewer implementation is
     * running is a different question, and it belongs to whichever adapter owns those viewers.
     */
    val isRtl: Boolean

    /**
     * Moves the reader to [progress] inside the chapter it is showing. Typed rather than an `Int`, so
     * each adapter reads the unit its own medium produced and a scrub can never be interpreted as a
     * page count in one reader and a percentage in the other.
     */
    fun seekTo(progress: ChapterProgress)

    fun destroy()

    fun handleKeyEvent(event: KeyEvent): Boolean

    fun handleGenericMotionEvent(event: MotionEvent): Boolean
}
