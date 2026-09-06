package reikai.presentation.reader.text

import android.content.Context
import android.text.Spannable
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import androidx.core.net.toUri
import eu.kanade.tachiyomi.util.system.openInBrowser
import reikai.presentation.novel.reader.NovelChapterNavigationClient

/**
 * Replaces the `URLSpan`s `Html.fromHtml` produces with spans that route a tap through the reader's
 * own navigation policy.
 *
 * A stock `URLSpan` fires an `ACTION_VIEW` for whatever the href says, including `intent://`, so the
 * native renderer would trust chapter markup that the WebView path refuses. Both renderers ask the
 * same question, which is the point of it being a pure function.
 */
object NovelChapterLinks {

    fun apply(spannable: Spannable, context: Context) {
        val urls = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        urls.forEach { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            spannable.removeSpan(span)
            spannable.setSpan(PolicyLinkSpan(span.url, context), start, end, flags)
        }
    }

    private class PolicyLinkSpan(
        private val url: String,
        private val context: Context,
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            // No document URL to be same-document with: the renderer holds no anchors to scroll to,
            // so an in-chapter jump is simply not offered rather than sent anywhere.
            val decision = NovelChapterNavigationClient.decide(url, baseUrl = null, hasGesture = true)
            if (decision == NovelChapterNavigationClient.Decision.OPEN_EXTERNALLY) {
                context.openInBrowser(url.toUri())
            }
        }
    }
}
