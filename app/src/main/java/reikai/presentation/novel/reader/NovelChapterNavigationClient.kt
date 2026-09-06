package reikai.presentation.novel.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.util.system.openInBrowser

/**
 * Keeps a chapter document on the page it was loaded as.
 *
 * The reader WebView carries the native bridge and the app's shared cookie jar, so a foreign page
 * loaded into it inherits both. Nothing in the pipeline strips anchors. A tapped link opens in the
 * browser; every other navigation is refused.
 */
class NovelChapterNavigationClient(
    private val context: Context,
    /** The document's own URL, as handed to `loadDataWithBaseURL`. */
    private val baseUrl: () -> String?,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return when (decide(request.url.toString(), baseUrl(), request.hasGesture())) {
            Decision.ALLOW -> false
            Decision.BLOCK -> true
            Decision.OPEN_EXTERNALLY -> {
                context.openInBrowser(Uri.parse(request.url.toString()))
                true
            }
        }
    }

    enum class Decision { ALLOW, BLOCK, OPEN_EXTERNALLY }

    companion object {

        /**
         * `loadDataWithBaseURL` makes [baseUrl] the document's own URL, so a link resolving to it, or
         * to it plus a fragment, is the chapter jumping within itself. A null base means the document
         * has no origin worth trusting, and then nothing is same-document.
         */
        fun decide(requestUrl: String, baseUrl: String?, hasGesture: Boolean): Decision {
            if (baseUrl != null && (requestUrl == baseUrl || requestUrl.startsWith("$baseUrl#"))) {
                return Decision.ALLOW
            }
            val isWeb = requestUrl.startsWith("http://") || requestUrl.startsWith("https://")
            // Without a gesture the page is navigating itself, which a chapter has no reason to do.
            return if (hasGesture && isWeb) Decision.OPEN_EXTERNALLY else Decision.BLOCK
        }
    }
}
