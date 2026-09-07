package reikai.presentation.reader

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow

/**
 * Scrolling the chapter on its own, for a content type that offers it. Null for one that does not, so
 * the bar button is absent rather than present and dead.
 *
 * How fast is not here: the speed reaches the renderer with the rest of that type's display settings,
 * and the bar only turns the thing on and off.
 */
@Stable
interface ReaderAutoScroll {

    val enabled: Flow<Boolean>

    /** A flip rather than a set, because the button shows the state it is inverting. */
    fun toggle()

    /** Turn it off wherever the reader is about to move somewhere the user chose, so the scroll does
     *  not immediately carry them away from it. */
    fun stop()
}
