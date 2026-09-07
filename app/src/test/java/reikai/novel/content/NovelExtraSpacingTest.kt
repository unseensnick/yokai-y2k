package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class NovelExtraSpacingTest {

    @Test
    @DisplayName("padding entities before a paragraph boundary are dropped")
    fun dropsPaddingEntities() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>Real text&nbsp;&nbsp;</p>")

        cleaned shouldBe "<p>Real text</p>"
    }

    @Test
    @DisplayName("a run of three or more breaks collapses to two")
    fun collapsesBreakRuns() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("One<br><br><br><br>Two")

        cleaned shouldBe "One<br><br>Two"
    }

    @Test
    @DisplayName("breaks padding a paragraph boundary are dropped, since the paragraph already spaces it")
    fun dropsBreaksBesideParagraphs() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>One</p><br><br><p>Two</p>")

        cleaned shouldNotContain "<br>"
    }

    @Test
    @DisplayName("a break between words is left alone, because it is the source's own line break")
    fun keepsBreaksInsideText() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>One<br>Two</p>")

        cleaned shouldBe "<p>One<br>Two</p>"
    }

    @Test
    @DisplayName("markup with nothing to strip comes back unchanged")
    fun leavesCleanMarkupAlone() {
        val clean = "<p>One</p><p>Two</p>"

        NovelHtmlUtils.removeExtraParagraphSpacing(clean) shouldBe clean
    }
}
