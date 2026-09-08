package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChapterScrollProgressTest {

    @Test
    @DisplayName("a chapter that has not been scrolled reports nothing read")
    fun unscrolledReportsZero() {
        ChapterScrollProgress.fractionOf(top = 0, height = 5000, viewportHeight = 1000) shouldBe 0f
    }

    @Test
    @DisplayName("a chapter scrolled to its last screen reports finished")
    fun scrolledToEndReportsOne() {
        ChapterScrollProgress.fractionOf(top = -4000, height = 5000, viewportHeight = 1000) shouldBe 1f
    }

    @Test
    @DisplayName("the trailing viewport is not counted as unread")
    fun trailingViewportExcluded() {
        // Half of the 4000 scrollable pixels, not half of the 5000 the chapter is tall.
        ChapterScrollProgress.fractionOf(top = -2000, height = 5000, viewportHeight = 1000) shouldBe 0.5f
    }

    @Test
    @DisplayName("a chapter shorter than the screen reports nothing read")
    fun shorterThanScreenReportsZero() {
        ChapterScrollProgress.fractionOf(top = 0, height = 800, viewportHeight = 1000) shouldBe 0f
    }

    @Test
    @DisplayName("a chapter with no measured height reports nothing read")
    fun unmeasuredReportsZero() {
        ChapterScrollProgress.fractionOf(top = 0, height = 0, viewportHeight = 1000) shouldBe 0f
    }

    @Test
    @DisplayName("overscrolling past the end still reports finished")
    fun overscrollClampsToOne() {
        ChapterScrollProgress.fractionOf(top = -6000, height = 5000, viewportHeight = 1000) shouldBe 1f
    }

    @Test
    @DisplayName("a chapter held below the viewport top reports nothing read")
    fun positiveTopClampsToZero() {
        ChapterScrollProgress.fractionOf(top = 300, height = 5000, viewportHeight = 1000) shouldBe 0f
    }

    @Test
    @DisplayName("seeking to the start of a chapter asks for no offset")
    fun offsetForStartIsZero() {
        ChapterScrollProgress.offsetFor(fraction = 0f, height = 5000, viewportHeight = 1000) shouldBe 0
    }

    @Test
    @DisplayName("seeking to the end of a chapter asks for its last screen")
    fun offsetForEndIsScrollable() {
        ChapterScrollProgress.offsetFor(fraction = 1f, height = 5000, viewportHeight = 1000) shouldBe 4000
    }

    @Test
    @DisplayName("seeking into a chapter shorter than the screen asks for no offset")
    fun offsetForShortChapterIsZero() {
        ChapterScrollProgress.offsetFor(fraction = 0.5f, height = 800, viewportHeight = 1000) shouldBe 0
    }

    @Test
    @DisplayName("seeking rounds to the nearest pixel rather than truncating")
    fun offsetForRounds() {
        // 0.5 of 4001 scrollable pixels is 2000.5, which truncation would put a pixel short.
        ChapterScrollProgress.offsetFor(fraction = 0.5f, height = 5001, viewportHeight = 1000) shouldBe 2001
    }

    @Test
    @DisplayName("seeking is the inverse of reporting")
    fun offsetForRoundTripsWithFractionOf() {
        val offset = ChapterScrollProgress.offsetFor(fraction = 0.25f, height = 5000, viewportHeight = 1000)

        ChapterScrollProgress.fractionOf(top = -offset, height = 5000, viewportHeight = 1000) shouldBe 0.25f
    }
}
