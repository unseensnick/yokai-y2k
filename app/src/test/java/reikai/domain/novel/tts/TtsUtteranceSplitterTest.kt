package reikai.domain.novel.tts

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The rule every case here checks is the same one: no chunk may exceed the cap, because an utterance
 * past it is refused outright and the paragraph is what stops being read.
 */
class TtsUtteranceSplitterTest {

    private fun split(text: String, maxLength: Int, locale: Locale = Locale.ENGLISH) =
        TtsUtteranceSplitter.split(text, maxLength, locale)

    /** Capitalized because a sentence boundary is not one where the next word is lower case, so a
     *  fixture without it never exercises the sentence pass at all. */
    private fun sentences(count: Int, wordsEach: Int) =
        (1..count).joinToString(" ") { "Word " + List(wordsEach - 1) { "word" }.joinToString(" ") + "." }

    @Test
    fun `a paragraph within the cap is one utterance`() {
        split("One sentence. And a second.", maxLength = 100) shouldBe listOf("One sentence. And a second.")
    }

    @Test
    fun `blank text is nothing to speak`() {
        split("   \n  ", maxLength = 100).shouldBeEmpty()
    }

    @Test
    fun `a long paragraph is broken at its sentences`() {
        val chunks = split(sentences(count = 20, wordsEach = 10), maxLength = 200)

        chunks.forEach { it.length shouldBeLessThanOrEqual 200 }
        chunks.joinToString(" ") shouldBe sentences(count = 20, wordsEach = 10)
    }

    /**
     * A sentence that fits stays whole, so the voice pauses where the writing does. Four forty-character
     * sentences against a cap of sixty: two never fit together, so every chunk is one whole sentence
     * and ends where one ends. Breaking anywhere else would leave a chunk ending mid-sentence.
     */
    @Test
    fun `a sentence that fits is never split`() {
        val chunks = split(sentences(count = 4, wordsEach = 8), maxLength = 60)

        chunks shouldHaveSize 4
        chunks.forEach { it.last() shouldBe '.' }
    }

    /** One run-on sentence past the cap, which the sentence pass cannot help with. */
    @Test
    fun `a single oversized sentence is broken between words`() {
        val text = List(300) { "word" }.joinToString(" ") + "."

        val chunks = split(text, maxLength = 100)

        chunks.forEach { it.length shouldBeLessThanOrEqual 100 }
        chunks.forEach { chunk -> chunk.split(" ").forEach { it.trimEnd('.') shouldBe "word" } }
    }

    /**
     * The case the ruling is about. One Japanese sentence with no spaces and no terminator, so the
     * sentence pass cannot place a single break and the line instance has to. A small kana may not
     * begin a line, so a splitter that counted characters instead would strand one at a chunk start.
     */
    @Test
    fun `a run with no spaces breaks where the language allows, not at the cap`() {
        val text = "あきょ".repeat(40)

        val chunks = split(text, maxLength = 20, locale = Locale.JAPANESE)

        chunks.forEach { it.length shouldBeLessThanOrEqual 20 }
        chunks.joinToString("") shouldBe text
        chunks.forEach { it.first() shouldNotBe 'ょ' }
    }

    /** No break opportunity anywhere, so the cut is all that is left; it still respects the cap. */
    @Test
    fun `an unbreakable run is cut at the cap`() {
        val chunks = split("x".repeat(250), maxLength = 100)

        chunks shouldHaveSize 3
        chunks.forEach { it.length shouldBeLessThanOrEqual 100 }
    }

    /** A cut that lands between the halves of a character speaks as a replacement glyph. */
    @Test
    fun `a cut never lands inside a character`() {
        val chunks = split("𝄞".repeat(60), maxLength = 25)

        chunks.forEach { Character.isHighSurrogate(it.last()) shouldBe false }
        chunks.joinToString("") shouldBe "𝄞".repeat(60)
    }
}
