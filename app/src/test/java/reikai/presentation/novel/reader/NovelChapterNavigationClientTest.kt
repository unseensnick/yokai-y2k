package reikai.presentation.novel.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.novel.reader.NovelChapterNavigationClient.Companion.decide
import reikai.presentation.novel.reader.NovelChapterNavigationClient.Decision

/**
 * The reader's WebView holds the native bridge and the app's cookie jar, so what a chapter's markup
 * is allowed to navigate to is a security rule, not a UX one. Chapter markup is source-controlled.
 */
class NovelChapterNavigationClientTest {

    private val base = "https://source.example/novel/ch1"

    @Test
    fun `the document's own url is allowed`() {
        decide(base, base, hasGesture = true) shouldBe Decision.ALLOW
    }

    @Test
    fun `a footnote jump within the chapter is allowed`() {
        decide("$base#note-4", base, hasGesture = true) shouldBe Decision.ALLOW
    }

    @Test
    fun `a tapped link to another site opens outside the reader`() {
        decide("https://elsewhere.example/", base, hasGesture = true) shouldBe Decision.OPEN_EXTERNALLY
    }

    @Test
    fun `a navigation the page starts by itself is refused`() {
        decide("https://elsewhere.example/", base, hasGesture = false) shouldBe Decision.BLOCK
    }

    @Test
    fun `an intent url is refused even when tapped`() {
        decide("intent://evil#Intent;scheme=http;end", base, hasGesture = true) shouldBe Decision.BLOCK
    }

    @Test
    fun `a javascript url is refused even when tapped`() {
        decide("javascript:alert(1)", base, hasGesture = true) shouldBe Decision.BLOCK
    }

    @Test
    fun `a file url is refused even when tapped`() {
        decide("file:///data/data/app.reikai/databases/", base, hasGesture = true) shouldBe Decision.BLOCK
    }

    /** A prefix match alone would let this through, since it starts with the document's own URL. */
    @Test
    fun `a sibling path sharing the document's url as a prefix is not the document`() {
        decide("$base-evil", base, hasGesture = false) shouldBe Decision.BLOCK
    }

    @Test
    fun `nothing is same-document when the chapter was loaded without an origin`() {
        decide("https://source.example/novel/ch1", null, hasGesture = false) shouldBe Decision.BLOCK
    }
}
