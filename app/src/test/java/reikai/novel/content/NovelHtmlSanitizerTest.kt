package reikai.novel.content

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * What a chapter's own markup may reach the WebView with. The WebView runs JavaScript with the app's
 * cookie jar and a bridge bound, so a source that gets a script past this executes in that context.
 * The TEXT_VIEW cases are a different question: `Html.fromHtml` executes nothing, so they only pin
 * that the visible-text cleanup still happens.
 */
class NovelHtmlSanitizerTest {

    private fun web(
        content: String,
        keepEmbeddedCss: Boolean = true,
        keepEmbeddedJs: Boolean = false,
        blockMedia: Boolean = false,
    ) = NovelHtmlUtils.sanitizeForRender(
        content = content,
        target = RenderTarget.WEB_VIEW,
        keepEmbeddedCss = keepEmbeddedCss,
        keepEmbeddedJs = keepEmbeddedJs,
        blockMedia = blockMedia,
    )

    /** The regex closed on `</script>` exactly, so one space past it left the whole block standing. */
    @Test
    fun `an end tag with whitespace does not carry a script through`() {
        web("<p>a</p><script>alert(1)</script >") shouldNotContain "alert(1)"
    }

    /**
     * Scripts were stripped before comments, so a comment inside the tag name hid the tag for that
     * pass and the comment strip afterwards put it back together. Asked of the parsed result rather
     * than the string, because that is the question the WebView answers: the fragments left behind
     * are text on the page, and only an element that survives as a `script` can run.
     */
    @Test
    fun `a script spliced with a comment does not survive reassembly`() {
        val spliced = "<p>a</p><scr<!-- -->ipt>alert(1)</scr<!-- -->ipt>"

        Jsoup.parseBodyFragment(web(spliced)).select("script").shouldBeEmpty()
    }

    @Test
    fun `a comment is dropped`() {
        web("<p>a</p><!-- hidden -->") shouldNotContain "hidden"
    }

    /** Serialising foreign content and re-parsing it does not always round-trip, which is the shape
     *  every mutation bypass takes. A chapter has no use for either, so neither reaches the page. */
    @Test
    fun `foreign content is removed`() {
        web("<svg><desc><p>a</p></desc></svg>") shouldNotContain "svg"
    }

    @Test
    fun `an event handler attribute is removed`() {
        web("""<img src="x" onerror="alert(1)">""") shouldNotContain "onerror"
    }

    @Test
    fun `a javascript url is removed`() {
        web("""<a href="javascript:alert(1)">tap</a>""") shouldNotContain "javascript:"
    }

    /** No regex covered these at all, and both run their own content in the page. */
    @Test
    fun `a frame is removed`() {
        web("""<iframe src="https://example.invalid"></iframe>""") shouldNotContain "iframe"
    }

    @Test
    fun `a plugin element is removed`() {
        web("""<object data="x.swf"></object>""") shouldNotContain "object"
    }

    /** Keeping the source's CSS is a setting and defaults on, so hardening must not quietly drop it. */
    @Test
    fun `the source's own styles are kept when the setting is on`() {
        val kept = web("<style>p { color: red }</style><p>body text</p>")

        kept shouldContain "color: red"
        kept shouldContain "body text"
    }

    @Test
    fun `the source's own styles are dropped when the setting is off`() {
        web("<style>p { color: red }</style><p>a</p>", keepEmbeddedCss = false) shouldNotContain "color: red"
    }

    /** A style attribute is CSS, so it follows the same setting rather than the script rule. */
    @Test
    fun `a style attribute is dropped when the setting is off`() {
        web("""<p style="color: red">a</p>""", keepEmbeddedCss = false) shouldNotContain "color: red"
    }

    @Test
    fun `a script is kept when the reader was told to keep them`() {
        web("<script>alert(1)</script>", keepEmbeddedJs = true) shouldContain "alert(1)"
    }

    @Test
    fun `blocking media removes an image`() {
        web("""<p>a</p><img src="x.png">""", blockMedia = true) shouldNotContain "img"
    }

    @Test
    fun `paragraph text survives`() {
        web("<p>the chapter</p>") shouldContain "the chapter"
    }

    /** The line breaks a plain-text chapter is rebuilt with, which a reflow would collapse. */
    @Test
    fun `a run of breaks survives`() {
        web("<p>one<br><br>two</p>") shouldContain "<br><br>"
    }

    @Test
    fun `the text target still strips a script`() {
        val stripped = NovelHtmlUtils.sanitizeForRender(
            content = "<p>a</p><script>alert(1)</script>",
            target = RenderTarget.TEXT_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )

        stripped shouldNotContain "alert(1)"
    }
}
