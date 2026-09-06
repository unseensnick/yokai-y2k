package reikai.presentation.reader.text

import android.text.Spannable
import android.text.method.MovementMethod
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView

/**
 * Dispatches a tap to a link and does nothing else.
 *
 * `LinkMovementMethod` calls `Selection.setSelection`, which throws "Selection cancelled" on a
 * TextView that is not selectable, and it also swallows the taps the reader needs for its chrome.
 * Ported from tsundoku (`textview/LinkOnlyMovementMethod.kt`).
 */
object LinkOnlyMovementMethod : MovementMethod {
    override fun initialize(widget: TextView, text: Spannable) = Unit
    override fun onKeyDown(widget: TextView, text: Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyUp(widget: TextView, text: Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyOther(view: TextView, text: Spannable, event: KeyEvent) = false
    override fun onTrackballEvent(widget: TextView, text: Spannable, event: MotionEvent) = false
    override fun onGenericMotionEvent(widget: TextView, text: Spannable, event: MotionEvent) = false
    override fun canSelectArbitrarily() = false
    override fun onTakeFocus(widget: TextView, text: Spannable, direction: Int) = Unit

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) return false
        val layout = widget.layout ?: return false
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        val links = buffer.getSpans(offset, offset, ClickableSpan::class.java)
        if (links.isEmpty()) return false
        if (action == MotionEvent.ACTION_UP) links[0].onClick(widget)
        return true
    }
}
