package reikai.presentation.reader.text

/**
 * The values the chapter's spans are built from, paragraph shape and bionic emphasis alike.
 *
 * Indent and spacing become pixels at build time and bionic emphasis becomes bold spans, so none of
 * them can be restyled into a view afterwards: the chapter has to be drawn again.
 */
data class ParagraphShape(
    val indent: Float,
    val spacing: Float,
    val fontSize: Int,
    val bionic: Boolean,
) {

    /**
     * Whether moving to [next] owes a redraw rather than a restyle.
     *
     * A size change alone counts only while something is measured against it. That exemption is the
     * point of the rule: with both at zero, which is the default, dragging the text-size slider
     * restyles the views it already has instead of re-parsing the chapter per step.
     */
    fun needsRedrawFor(next: ParagraphShape): Boolean {
        if (indent != next.indent || spacing != next.spacing || bionic != next.bionic) return true
        val measuredAgainstSize = next.indent > 0f || next.spacing > 0f
        return measuredAgainstSize && fontSize != next.fontSize
    }
}
