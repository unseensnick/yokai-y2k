package reikai.presentation.reader.text

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan

/**
 * Extra space after a paragraph, added to the last line's descent rather than as a margin, so it
 * survives the chunking that splits a chapter across several views.
 *
 * Ported from tsundoku (`textview/NovelViewerSpans.kt`).
 */
class ParagraphSpacingSpan(private val spacingPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        if (end > 0 && end <= text.length && text[end - 1] == '\n') {
            fm.descent += spacingPx
            fm.bottom += spacingPx
        }
    }
}

/** First-line indent for a paragraph. Draws nothing; the margin is the whole effect. */
class ParagraphIndentSpan(private val indentPx: Int) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = if (first) indentPx else 0

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) = Unit
}
