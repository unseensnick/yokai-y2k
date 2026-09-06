package reikai.presentation.reader.text

import android.content.Context
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Turns a processed chapter into styled text across [ChapterTextBlock]'s chunk views.
 *
 * Ported from tsundoku (`textview/NovelTextRenderer.kt`), re-plumbed off their host Activity and
 * preference class. It is handed pipeline output, which a renderer must not process again, and it
 * takes the HTML built for a WebView sink, so plain text arrives already wrapped in paragraphs.
 * Details in docs/dev/plans/content-layer-reader-surface.md.
 */
class NovelTextRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /**
     * [paragraphSpacing] and [paragraphIndent] are multiples of the font size, matching how tsundoku
     * stores them. [onTextSet] fires once the views hold the finished text.
     */
    fun render(
        block: ChapterTextBlock,
        html: String,
        fontSize: Int,
        paragraphSpacing: Float,
        paragraphIndent: Float,
        onTextSet: () -> Unit,
    ) {
        val body = wrapParagraphs(html)
        val density = context.resources.displayMetrics.density
        val token = ++block.renderToken

        scope.launch {
            val spannable = withContext(Dispatchers.Default) {
                // No image getter yet, so an img renders as its alt text rather than a picture.
                val spanned = Html.fromHtml(normalizeHtmlForRendering(body), Html.FROM_HTML_MODE_LEGACY, null, null)
                SpannableStringBuilder(spanned)
            }

            val spacingPx = (paragraphSpacing * fontSize * density).toInt()
            val indentPx = (paragraphIndent * fontSize * density).toInt()

            val chunks = withContext(Dispatchers.Default) {
                chunkRanges(spannable).map { (start, end) ->
                    SpannableStringBuilder(spannable.subSequence(start, end)).also {
                        if (spacingPx > 0 || indentPx > 0) applyParagraphSpans(it, spacingPx, indentPx)
                    }
                }
            }

            if (token != block.renderToken || !block.container.isAttachedToWindow) return@launch
            block.ensureChunkCount(chunks.size)
            if (chunks.isEmpty()) {
                block.chunkStarts = IntArray(0)
                block.fullText = ""
                onTextSet()
                return@launch
            }

            // Precomputing the layout off the main thread is what keeps a long chapter from stalling
            // on first draw. It is incompatible with selection, which is why it is not conditional.
            val params = TextViewCompat.getTextMetricsParams(block.chunkViews.first())
            val precomputed = withContext(Dispatchers.Default) {
                chunks.map { PrecomputedTextCompat.create(it, params) }
            }
            if (token != block.renderToken || !block.container.isAttachedToWindow) return@launch

            val starts = IntArray(chunks.size)
            var offset = 0
            chunks.forEachIndexed { i, chunk ->
                starts[i] = offset
                offset += chunk.length
            }

            block.clearSelections()
            precomputed.forEachIndexed { i, text ->
                // Throws when the view's metrics have moved since the params were taken, which a
                // settings change between the two dispatches can do. The plain text is the fallback.
                try {
                    TextViewCompat.setPrecomputedText(block.chunkViews[i], text)
                } catch (_: IllegalArgumentException) {
                    block.chunkViews[i].text = chunks[i]
                }
            }
            block.chunkStarts = starts
            block.fullText = spannable.toString()
            onTextSet()
        }
    }

    /** Splits at the first paragraph boundary past every [CHUNK_TARGET_CHARS], so each chunk's
     *  layout stays small and no chunk ends mid-paragraph. */
    private fun chunkRanges(text: CharSequence): List<Pair<Int, Int>> {
        val length = text.length
        if (length == 0) return emptyList()
        val ranges = ArrayList<Pair<Int, Int>>(length / CHUNK_TARGET_CHARS + 1)
        var start = 0
        while (start < length) {
            var end = (start + CHUNK_TARGET_CHARS).coerceAtMost(length)
            if (end < length) {
                var newline = end
                while (newline < length && text[newline] != '\n') newline++
                end = if (newline < length) newline + 1 else length
            }
            ranges.add(start to end)
            start = end
        }
        return ranges
    }

    private fun wrapParagraphs(html: String): String {
        val content = html.replace(leadingSpaceInParagraph, "<p>")
        if (content.contains("<p>", ignoreCase = true)) return content
        return "<p>" + content.replace("\r\n\r\n", "</p><p>").replace("\n\n", "</p><p>") + "</p>"
    }

    /**
     * `Html.fromHtml` has no CSS and no `picture` support, so the markup is reshaped into what it can
     * read. Failing open leaves the chapter as it was rather than blanking it.
     */
    private fun normalizeHtmlForRendering(html: String): String = try {
        val doc = Jsoup.parse(html)
        doc.select("style, script").remove()
        unwrapPictureSources(doc)
        val targetWidth = context.resources.displayMetrics.widthPixels
        doc.select("img").forEach { img ->
            applySrcsetCandidate(img, targetWidth)
            if (img.parent()?.tagName() != "p" && img.parent()?.tagName() != "div") {
                img.wrap("<p style=\"text-align:center;\"></p>")
            }
        }
        doc.body().html()
    } catch (_: Exception) {
        html
    }

    /** Picks the narrowest candidate at least as wide as the screen, else the widest available. */
    private fun applySrcsetCandidate(img: Element, targetWidth: Int) {
        val srcset = img.attr("srcset").takeIf { it.isNotBlank() } ?: return
        val candidates = srcset.split(',').mapNotNull { entry ->
            val parts = entry.trim().split(whitespace, limit = 2)
            val url = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val width = parts.getOrNull(1)?.trim()
                ?.let { widthDescriptor.find(it) }
                ?.groupValues?.get(1)?.toIntOrNull()
            url to width
        }
        if (candidates.isEmpty()) return
        val withWidth = candidates.filter { it.second != null }
        val best = withWidth.filter { it.second!! >= targetWidth }.minByOrNull { it.second!! }
            ?: withWidth.maxByOrNull { it.second!! }
            ?: candidates.first()
        img.attr("src", best.first)
    }

    private fun applyParagraphSpans(spannable: SpannableStringBuilder, spacingPx: Int, indentPx: Int) {
        var i = 0
        var paragraphStart = 0
        while (i < spannable.length) {
            if (spannable[i] == '\n' || i == spannable.length - 1) {
                val paragraphEnd = i + 1
                if (spacingPx > 0) {
                    spannable.setSpan(
                        ParagraphSpacingSpan(spacingPx),
                        paragraphStart,
                        paragraphEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (indentPx > 0) {
                    spannable.setSpan(
                        ParagraphIndentSpan(indentPx),
                        paragraphStart,
                        paragraphEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                paragraphStart = paragraphEnd
            }
            i++
        }
    }

    companion object {
        private const val CHUNK_TARGET_CHARS = 6_000

        private val leadingSpaceInParagraph = Regex("<p>(?: |&#160;|&nbsp;)+")
        private val whitespace = Regex("\\s+")
        private val widthDescriptor = Regex("^(\\d+)w$")

        // `source` is a void element and Html.fromHtml corrupts the rest of the document without one,
        // so a picture is collapsed to the img it wraps before it ever reaches the parser.
        private val decodableImageTypes = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/bmp",
        )

        private fun Element.isDecodableSource(): Boolean {
            val type = attr("type").trim().lowercase()
            return type.isEmpty() || type in decodableImageTypes
        }

        internal fun unwrapPictureSources(doc: Document) {
            doc.select("video source, audio source").remove()
            doc.select("picture").forEach { picture ->
                val sources = picture.select("source")
                val fallbackSrcset = sources.firstOrNull { it.isDecodableSource() && it.hasAttr("srcset") }
                    ?.attr("srcset")
                val img = picture.selectFirst("img")
                if (img == null) {
                    if (fallbackSrcset != null) picture.appendElement("img").attr("srcset", fallbackSrcset)
                } else if (!img.hasAttr("srcset") && fallbackSrcset != null) {
                    img.attr("srcset", fallbackSrcset)
                }
                sources.remove()
                picture.unwrap()
            }
        }
    }
}
