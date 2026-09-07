package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

/**
 * The pipeline's own guarantees. The plain-text case is the one that matters most: a chapter whose
 * URL ends in `.txt` skips sanitisation by design, so whatever renders it owes the escaping, and a
 * source picks its own chapter URLs.
 */
class NovelContentPipelineTest {

    private val preferences = NovelPreferences(InMemoryPreferenceStore())
    private val pipeline = NovelContentPipeline(preferences)

    private fun config(chapterUrl: String?) = NovelContentConfig(
        chapterUrl = chapterUrl,
        chapterName = "Chapter 1",
        target = RenderTarget.WEB_VIEW,
    )

    /**
     * Seeded through the constructor: `InMemoryPreferenceStore.set` is invisible to the next `get`, so
     * a stage switched on with `set` would look off and the test would pass against a deleted stage.
     */
    private fun pipelineWith(vararg seeded: InMemoryPreference<*>) =
        NovelContentPipeline(NovelPreferences(InMemoryPreferenceStore(seeded.asSequence())))

    @Test
    fun `a find-and-replace rule reaches the chapter`() = runTest {
        val rule = """[{"title":"t","pattern":"badger","replacement":"otter","isRegex":false}]"""
        val seeded = pipelineWith(InMemoryPreference("ln_reader_regex_replacements", rule, "[]"))

        val processed = seeded.process("<p>a badger appears</p>", config("/book/ch1.html"))

        processed.text shouldContain "otter"
        processed.text shouldNotContain "badger"
    }

    @Test
    fun `auto-split breaks a wall of text into paragraphs`() = runTest {
        val seeded = pipelineWith(
            InMemoryPreference("ln_reader_auto_split_text", true, false),
            InMemoryPreference("ln_reader_auto_split_word_count", 20, 50),
        )
        val wall = (1..6).joinToString(" ") { List(20) { "word" }.joinToString(" ") + "." }

        val processed = seeded.process("<p>$wall</p>", config("/book/ch1.html"))

        // Breaks rather than paragraph tags, so a split stays valid inside a div-based chapter.
        processed.text shouldContain "<br><br>"
    }

    @Test
    fun `a plain-text chapter is reported as plain text so a sink knows to escape it`() = runTest {
        val processed = pipeline.process("<script>evil()</script>", config("/book/ch1.txt"))

        processed.isPlainText shouldBe true
    }

    @Test
    fun `plain text rendered for an HTML sink has its markup escaped`() {
        val html = NovelHtmlUtils.plainTextToHtml("<script>evil()</script>")

        html shouldNotContain "<script>"
        html shouldContain "&lt;script&gt;"
    }

    /**
     * Paragraphs rather than one `pre` block: a `pre` renders in the browser's monospace default, so a
     * plain-text chapter ignored the font the reader had chosen.
     */
    @Test
    fun `a blank line in plain text starts a new paragraph`() {
        val html = NovelHtmlUtils.plainTextToHtml("First para.\n\nSecond para.")

        html shouldContain "<p>First para.</p>"
        html shouldContain "<p>Second para.</p>"
        html shouldNotContain "<pre"
    }

    /**
     * As a `br` rather than a newline: a WebView shows a bare newline because the stylesheet puts the
     * paragraph in `pre-wrap`, and the text renderer collapses it to a space, so the two laid the same
     * chapter out differently.
     */
    @Test
    fun `a single line break stays inside its paragraph`() {
        val html = NovelHtmlUtils.plainTextToHtml("One line\nnext line")

        html shouldContain "<p>One line<br>next line</p>"
    }

    /**
     * Found on device: with the chapter's own styling turned off, the sanitiser's Jsoup round-trip
     * pretty-printed the document and collapsed the line breaks inside a plain-text paragraph.
     */
    @Test
    fun `stripping chapter styling does not reflow the chapter`() {
        val input = NovelHtmlUtils.plainTextToHtml("First line\nsecond line")

        val stripped = NovelHtmlUtils.sanitizeForRender(
            input,
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = false,
            keepEmbeddedJs = false,
            blockMedia = false,
        )

        stripped shouldContain "First line<br>second line"
    }

    @Test
    fun `a script block is stripped from an HTML chapter by default`() = runTest {
        val processed = pipeline.process("<p>a</p><script>evil()</script>", config("/book/ch1.html"))

        processed.text shouldNotContain "evil()"
    }

    @Test
    fun `a chapter's own styling survives by default`() = runTest {
        val processed = pipeline.process("<style>p{color:red}</style><p>a</p>", config("/book/ch1.html"))

        processed.text shouldContain "color:red"
    }

    /**
     * The loader is what both readers call, and both feed an HTML document, so it owes the escaping
     * the pipeline deliberately leaves undone. A source picks its own chapter URLs, so the `.txt`
     * extension that reaches this branch is not the user's choice.
     */
    @Test
    fun `the loader escapes a plain-text chapter before a reader renders it`() = runTest {
        val source = mockk<NovelSource> {
            every { site } returns "https://example.test"
            coEvery { parseChapter(any()) } returns "<script>evil()</script>"
        }
        // Stubbed outside the mockk block: a bare `get(any())` inside one binds to MockK's own get.
        val sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get(any()) } returns source }
        val loader = NovelChapterTextLoader(
            novelRepo = mockk { coEvery { getById(any()) } returns Novel.create().copy(id = 1L, source = "s") },
            sourceManager = sourceManager,
            installer = mockk(relaxed = true),
            preferences = preferences,
            readDownloaded = { _, _ -> null },
        )

        val (html, _) = loader.load(chapter(url = "/book/ch1.txt"))

        html shouldNotContain "<script>"
        html shouldContain "&lt;script&gt;"
    }

    private fun chapter(url: String) = NovelChapter(
        id = 1L,
        novelId = 1L,
        url = url,
        name = "Chapter 1",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = 1.0,
        sourceOrder = 0L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
