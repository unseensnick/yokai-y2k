package reikai.presentation.reader.text

import android.content.Context
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.TextView
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
        selectable: Boolean,
        /** The chapter's own site, sent as the Referer for an image some hosts would otherwise refuse. */
        refererUrl: String?,
        onTextSet: () -> Unit,
    ) {
        val body = wrapParagraphs(html)
        val density = context.resources.displayMetrics.density
        val token = ++block.renderToken

        scope.launch {
            // Measured against the column the text is drawn in, so an image is decoded at the size it
            // is shown at rather than full resolution.
            val contentWidth = block.chunkViews.firstOrNull()
                ?.let { it.width - it.paddingLeft - it.paddingRight }
                ?.takeIf { it > 0 }
                ?: context.resources.displayMetrics.widthPixels
            val imageGetter = NovelImageGetter(
                context = context,
                scope = scope,
                contentWidthPx = contentWidth,
                refererUrl = refererUrl,
                resolveView = block::chunkViewFor,
                onImagesReady = { views -> remeasureForImages(views, selectable) },
            )

            val spannable = withContext(Dispatchers.Default) {
                val spanned = Html.fromHtml(
                    normalizeHtmlForRendering(body),
                    Html.FROM_HTML_MODE_LEGACY,
                    imageGetter,
                    null,
                )
                SpannableStringBuilder(spanned)
                    .also { collapseBlankLines(it) }
                    .also { NovelChapterLinks.apply(it, context) }
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
            // on first draw, and it is incompatible with a selectable view, so only one is possible.
            val params = if (selectable) null else TextViewCompat.getTextMetricsParams(block.chunkViews.first())
            val precomputed = withContext(Dispatchers.Default) {
                params?.let { p -> chunks.map { PrecomputedTextCompat.create(it, p) } }
            }
            if (token != block.renderToken || !block.container.isAttachedToWindow) return@launch

            val starts = IntArray(chunks.size)
            var offset = 0
            chunks.forEachIndexed { i, chunk ->
                starts[i] = offset
                offset += chunk.length
            }

            block.clearSelections()
            if (precomputed == null) {
                chunks.forEachIndexed { i, chunk -> block.chunkViews[i].text = chunk }
            } else {
                precomputed.forEachIndexed { i, text ->
                    // Throws when the view's metrics moved since the params were taken, which a
                    // settings change between the two dispatches can do. Plain text is the fallback.
                    try {
                        TextViewCompat.setPrecomputedText(block.chunkViews[i], text)
                    } catch (_: IllegalArgumentException) {
                        block.chunkViews[i].text = chunks[i]
                    }
                }
            }
            block.chunkStarts = starts
            block.fullText = spannable.toString()
            // After the text is set, so every image span has a view to find and re-measure.
            imageGetter.startLoading()
            onTextSet()
        }
    }

    /**
     * An image arriving changes its span's height, and a precomputed layout was measured before that,
     * so it has to be built again or the picture draws clipped into the space the placeholder took.
     * A selectable view has no precomputed layout, so asking for one is enough.
     */
    private fun remeasureForImages(views: List<TextView>, selectable: Boolean) {
        views.forEach { view -> remeasureOne(view, selectable) }
    }

    private fun remeasureOne(view: TextView, selectable: Boolean) {
        if (!view.isAttachedToWindow) return
        val snapshot = view.text
        if (selectable || snapshot == null) {
            view.requestLayout()
            return
        }
        scope.launch {
            val params = TextViewCompat.getTextMetricsParams(view)
            val precomputed = withContext(Dispatchers.Default) {
                PrecomputedTextCompat.create(SpannableStringBuilder(snapshot), params)
            }
            if (!view.isAttachedToWindow) return@launch
            try {
                TextViewCompat.setPrecomputedText(view, precomputed)
            } catch (_: IllegalArgumentException) {
                view.requestLayout()
            }
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

    /**
     * `Html.fromHtml` separates blocks with a blank line, which would sit under the paragraph spacing
     * and make the same setting draw a wider gap here than in a WebView. Collapsing it leaves the
     * spacing as the whole gap in both. Tsundoku keeps the blank line and carries that difference.
     */
    private fun collapseBlankLines(text: SpannableStringBuilder) {
        var i = text.length - 1
        while (i > 0) {
            if (text[i] == '\n' && text[i - 1] == '\n') text.delete(i, i + 1)
            i--
        }
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
