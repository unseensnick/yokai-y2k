package reikai.presentation.reader.text

/**
 * The values the paragraph spans are measured from when a chapter is built.
 *
 * Indent and spacing become pixels at build time, so neither they nor a size they are a multiple of
 * can be restyled into a view afterwards: the chapter has to be drawn again.
 */
data class ParagraphShape(
    val indent: Float,
    val spacing: Float,
    val fontSize: Int,
) {

    /**
     * Whether moving to [next] owes a redraw rather than a restyle.
     *
     * A size change alone counts only while something is measured against it. That exemption is the
     * point of the rule: with both at zero, which is the default, dragging the text-size slider
     * restyles the views it already has instead of re-parsing the chapter per step.
     */
    fun needsRedrawFor(next: ParagraphShape): Boolean {
        if (indent != next.indent || spacing != next.spacing) return true
        val measuredAgainstSize = next.indent > 0f || next.spacing > 0f
        return measuredAgainstSize && fontSize != next.fontSize
    }
}
