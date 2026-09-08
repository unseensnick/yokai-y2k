package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The gap rule, pinned once for both content types. */
class ChapterGapTest {

    private fun at(number: Double, name: String = "Chapter $number", owner: Long = 1L) =
        ChapterGap.Neighbour(number, name, owner)

    @Test
    @DisplayName("consecutive chapters are missing nothing")
    fun consecutiveIsZero() {
        ChapterGap.between(at(5.0), at(4.0)) shouldBe 0
    }

    @Test
    @DisplayName("a skipped chapter is counted")
    fun oneSkippedIsOne() {
        ChapterGap.between(at(5.0), at(3.0)) shouldBe 1
    }

    @Test
    @DisplayName("a decimal chapter does not count as a gap")
    fun decimalsAreFloored() {
        ChapterGap.between(at(5.5), at(5.0)) shouldBe 0
    }

    @Test
    @DisplayName("no neighbour below means everything under it is missing")
    fun leadingEdgeCountsDownToOne() {
        ChapterGap.between(at(4.0), null) shouldBe 3
    }

    @Test
    @DisplayName("two sources of one entry are not compared")
    fun crossSourcePairIsDeclined() {
        // The same chapter is numbered differently by each source, so the difference measures nothing.
        ChapterGap.between(at(526.0, owner = 1L), at(522.0, owner = 2L)) shouldBe 0
    }

    @Test
    @DisplayName("a volume extra's recognized number is not believed")
    fun volumeExtraIsDeclined() {
        val extra = at(2.0, name = "Chapter v11ex2: Vol 11 Extra 2: A Brief Repit")

        ChapterGap.between(at(483.0, name = "Chapter 483: What Was Lost"), extra) shouldBe 0
    }

    @Test
    @DisplayName("an epilogue's recognized number is not believed either")
    fun epilogueIsDeclined() {
        val epilogue = at(1.0, name = "Chapter epl1: Vol 11 Epilogue", owner = 1L)

        ChapterGap.between(at(483.0, name = "Chapter 483: What Was Lost"), epilogue) shouldBe 0
    }

    @Test
    @DisplayName("a label that is not a plain number is not believed")
    fun unparseableLabelIsDeclined() {
        ChapterGap.between(at(9.0, name = "Chapter v2s3: Something"), at(3.0)) shouldBe 0
    }

    @Test
    @DisplayName("a title carrying two numbers is not believed")
    fun dualNumberedTitleIsDeclined() {
        // "siteIndex - realNumber" naming: the recognizer takes the first, which is the site's own
        // index, so comparing it with a plainly numbered neighbour invents a gap.
        val higher = at(523.0, name = "Chapter 523 - 517")
        val lower = at(516.0, name = "Chapter 516: Ever His Humble Servant")

        ChapterGap.between(higher, lower) shouldBe 0
    }

    @Test
    @DisplayName("a decimal chapter reads as one number, not two")
    fun decimalIsOneNumber() {
        ChapterGap.between(at(7.0, name = "Chapter 7"), at(5.5, name = "Chapter 5.5")) shouldBe 1
    }

    @Test
    @DisplayName("an unrecognized number is not a gap")
    fun negativeNumberIsDeclined() {
        ChapterGap.between(at(5.0), at(-1.0, name = "Chapter -1.0")) shouldBe 0
    }
}
