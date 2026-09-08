package reikai.presentation.reader.text

import kotlin.math.roundToInt

/**
 * How far the reader is through one chapter, measured from that chapter's own laid-out bounds.
 *
 * The recycler's own scroll metrics cannot answer this. `ScrollbarHelper` estimates the range as the
 * average laid-out item size times the item count, and the offset from that same average, so both
 * are exact only while one item is laid out and drift as the window grows. A chapter's view knows
 * its height exactly, whether it is alone or one of several.
 */
object ChapterScrollProgress {

    /**
     * [top] is the chapter view's top edge in viewport coordinates, negative once scrolled into. The
     * trailing viewport is subtracted because nothing scrolls into it: a chapter ends when its last
     * line reaches the bottom of the screen. A chapter too short to fill the screen reports 0, as the
     * WebView renderer does, so it is not marked read the moment it opens.
     */
    fun fractionOf(top: Int, height: Int, viewportHeight: Int): Float {
        val scrollable = height - viewportHeight
        if (scrollable <= 0) return 0f
        return (-top).coerceIn(0, scrollable).toFloat() / scrollable
    }

    /**
     * The inverse: how far to scroll from the chapter's top to sit at [fraction] of the way through
     * it. Kept beside the forward direction so seeking and reporting cannot drift apart. [fraction]
     * arrives clamped, from `ChapterProgress.fraction` or from a stored percent.
     */
    fun offsetFor(fraction: Float, height: Int, viewportHeight: Int): Int {
        val scrollable = height - viewportHeight
        if (scrollable <= 0) return 0
        return (scrollable * fraction).roundToInt()
    }
}
