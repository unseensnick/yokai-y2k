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

    @Test
    fun `a single line break stays inside its paragraph`() {
        val html = NovelHtmlUtils.plainTextToHtml("One line\nnext line")

        html shouldContain "<p>One line\nnext line</p>"
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
