package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The compile kernel both the reading pipeline and the rule editor's preview run through, so a rule
 * cannot behave one way when tested and another way when read.
 */
class NovelRegexReplacementsTest {

    private fun rule(
        pattern: String,
        replacement: String = "",
        isRegex: Boolean = true,
        matchWholeWord: Boolean = false,
        caseSensitive: Boolean = false,
    ) = NovelRegexReplacement(
        title = "rule",
        pattern = pattern,
        replacement = replacement,
        isRegex = isRegex,
        matchWholeWord = matchWholeWord,
        caseSensitive = caseSensitive,
    )

    private fun run(rule: NovelRegexReplacement, input: String) =
        NovelRegexReplacements.compile(rule).applyTo(input)

    @Test
    fun `a regex rule rewrites what it matches`() {
        run(rule("h[0-9]", "p"), "<h1>Title</h1>") shouldBe "<p>Title</p>"
    }

    /** The default, because a reader writing "chapter" should not have to guess the source's casing. */
    @Test
    fun `matching ignores case unless the rule asks for it`() {
        run(rule("chapter", "Part"), "CHAPTER one") shouldBe "Part one"
    }

    @Test
    fun `a case-sensitive rule leaves the other casing alone`() {
        run(rule("chapter", "Part", caseSensitive = true), "CHAPTER one") shouldBe "CHAPTER one"
    }

    /** Without escaping, a find-and-replace of "a.b" would also match "axb". */
    @Test
    fun `a text rule treats its pattern as characters rather than a pattern`() {
        run(rule("a.b", "X", isRegex = false), "axb a.b") shouldBe "axb X"
    }

    @Test
    fun `whole word matching skips a longer word containing it`() {
        run(rule("cat", "dog", isRegex = false, matchWholeWord = true), "cat catalogue") shouldBe "dog catalogue"
    }

    /**
     * The half that separates the two modes. A regex rule may reference its groups, and a text rule
     * may not, so a replacement containing a dollar sign is written out rather than failing the rule.
     */
    @Test
    fun `a regex replacement can reference a captured group`() {
        run(rule("""Chapter (\d+)""", "Ch. $1"), "Chapter 12") shouldBe "Ch. 12"
    }

    @Test
    fun `a text replacement writes a dollar sign out as typed`() {
        run(rule("price", "$5", isRegex = false), "price") shouldBe "$5"
    }

    /** What the editor reports as an error rather than saving a rule that could never run. */
    @Test
    fun `an unparseable pattern throws rather than compiling to nothing`() {
        assertThrows<Exception> { NovelRegexReplacements.compile(rule("[unclosed")) }
    }
}
