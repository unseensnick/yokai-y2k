package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Auto-split breaks a wall of text into paragraphs, for a source that ships one. Ported whole from
 * tsundoku, which has no test over it either, so these are the first.
 */
class NovelTextSplitterTest {

    private fun sentences(count: Int, wordsEach: Int): String =
        (1..count).joinToString(" ") { List(wordsEach) { "word" }.joinToString(" ") + "." }

    @Test
    fun `a word count of zero leaves the text alone`() {
        val text = sentences(count = 5, wordsEach = 30)

        NovelTextSplitter.splitText(text, wordCount = 0, isHtml = false) shouldBe text
    }

    /**
     * The break waits for the end of a sentence, so a paragraph runs past the target rather than
     * cutting a sentence in half. Twenty-word sentences against a target of twenty-five means the
     * first break can only fall at the end of the second.
     */
    @Test
    fun `a break falls at the end of a sentence, not at the word count`() {
        val text = sentences(count = 4, wordsEach = 20)

        val split = NovelTextSplitter.splitText(text, wordCount = 25, isHtml = false)

        split.split("\n\n").first().split(" ").size shouldBe 40
    }

    @Test
    fun `text with no sentence ending is never broken`() {
        val text = List(200) { "word" }.joinToString(" ")

        NovelTextSplitter.splitText(text, wordCount = 20, isHtml = false) shouldNotContain "\n\n"
    }

    /** Below twenty the target is raised, so a small number cannot shred the text into fragments. */
    @Test
    fun `a target below the floor is raised to it`() {
        val text = sentences(count = 6, wordsEach = 5)

        val split = NovelTextSplitter.splitText(text, wordCount = 1, isHtml = false)

        split.split("\n\n").first().split(" ").size shouldBe 20
    }

    /** Breaks rather than paragraph tags, so a split stays valid inside a div-based chapter. */
    @Test
    fun `html is split with line breaks rather than blank lines`() {
        val html = "<p>${sentences(count = 4, wordsEach = 20)}</p>"

        val split = NovelTextSplitter.splitText(html, wordCount = 25, isHtml = true)

        split shouldContain "<br><br>"
        split shouldNotContain "\n\n"
    }

    /** An opening tag restarts the count, so a chapter already in paragraphs is left alone. */
    @Test
    fun `an existing paragraph shorter than the target gets no break`() {
        val html = (1..4).joinToString("") { "<p>${sentences(count = 1, wordsEach = 10)}</p>" }

        NovelTextSplitter.splitText(html, wordCount = 25, isHtml = true) shouldBe html
    }
}
