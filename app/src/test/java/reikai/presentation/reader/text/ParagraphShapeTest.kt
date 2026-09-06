package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ParagraphShapeTest {

    @Test
    @DisplayName("an unchanged shape needs no redraw")
    fun unchangedNeedsNoRedraw() {
        val shape = ParagraphShape(indent = 1f, spacing = 0.5f, fontSize = 16)

        shape.needsRedrawFor(shape) shouldBe false
    }

    @Test
    @DisplayName("a changed indent needs a redraw")
    fun changedIndentNeedsRedraw() {
        val before = ParagraphShape(indent = 0f, spacing = 0f, fontSize = 16)

        before.needsRedrawFor(before.copy(indent = 2f)) shouldBe true
    }

    @Test
    @DisplayName("a changed spacing needs a redraw")
    fun changedSpacingNeedsRedraw() {
        val before = ParagraphShape(indent = 0f, spacing = 0f, fontSize = 16)

        before.needsRedrawFor(before.copy(spacing = 1f)) shouldBe true
    }

    @Test
    @DisplayName("a font size change needs a redraw while indent is set, since indent is a multiple of it")
    fun fontSizeChangeWithIndentNeedsRedraw() {
        val before = ParagraphShape(indent = 2f, spacing = 0f, fontSize = 16)

        before.needsRedrawFor(before.copy(fontSize = 24)) shouldBe true
    }

    @Test
    @DisplayName("a font size change needs a redraw while spacing is set")
    fun fontSizeChangeWithSpacingNeedsRedraw() {
        val before = ParagraphShape(indent = 0f, spacing = 1f, fontSize = 16)

        before.needsRedrawFor(before.copy(fontSize = 24)) shouldBe true
    }

    @Test
    @DisplayName("a font size change alone restyles, so the default reader does not re-parse per slider step")
    fun fontSizeChangeWithoutSpansRestyles() {
        val before = ParagraphShape(indent = 0f, spacing = 0f, fontSize = 16)

        before.needsRedrawFor(before.copy(fontSize = 24)) shouldBe false
    }

    @Test
    @DisplayName("clearing both while the size also changes still needs the redraw that removes the spans")
    fun clearingSpansNeedsRedraw() {
        val before = ParagraphShape(indent = 2f, spacing = 1f, fontSize = 16)

        before.needsRedrawFor(ParagraphShape(indent = 0f, spacing = 0f, fontSize = 24)) shouldBe true
    }
}
