package reikai.presentation.reader.text

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A chapter's text as a column of chunk TextViews rather than one long view.
 *
 * One view makes layout, span lookup and selection hit-testing cost O(chapter); tsundoku measured
 * constant garbage collection and multi-second touch handling on large chapters before chunking.
 * Ported from tsundoku (`textview/ChapterTextBlock.kt`); its selection and placeholder members are
 * left out until the steps that need them (quotes and read-aloud).
 */
class ChapterTextBlock(
    context: Context,
    private val createChunkView: () -> TextView,
) {

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    val chunkViews = mutableListOf<TextView>()

    /** Start offset of each chunk's text within [fullText]. */
    var chunkStarts = IntArray(0)

    /** The chunks concatenated, which is what an offset above is measured against. */
    var fullText: String? = null

    /**
     * Bumped when a render of this block starts. A render coroutine captures the value and bails if
     * it changes, so an overlapping re-render never races view mutations against a stale one.
     */
    var renderToken: Int = 0

    fun ensureChunkCount(count: Int) {
        while (chunkViews.size < count) {
            val view = createChunkView()
            chunkViews.add(view)
            container.addView(view)
        }
        while (chunkViews.size > count) {
            val view = chunkViews.removeAt(chunkViews.size - 1)
            container.removeView(view)
        }
    }

    /** The chunk whose text holds the span backed by [drawable], so a finished image knows which
     *  view to re-measure. */
    fun chunkViewFor(drawable: Drawable): TextView? = chunkViews.firstOrNull { view ->
        (view.text as? Spanned)
            ?.getSpans(0, view.text.length, ImageSpan::class.java)
            ?.any { it.drawable === drawable } == true
    }

    fun clearSelections() = chunkViews.forEach { view ->
        val text = view.text
        if (text is Spannable && text.isNotEmpty() && Selection.getSelectionStart(text) >= 0) {
            Selection.removeSelection(text)
        }
        if (view.isFocused) view.clearFocus()
    }
}
