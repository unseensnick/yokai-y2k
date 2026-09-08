package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Rendering a merge group's stored stitch, pinned once for both content types: the manga and novel
 * providers differ only in which rows they hand in, so what the stitch means is tested here.
 */
class StoredStitchTest {

    private data class Row(val id: Long, val read: Boolean = false)

    /** Two sources' copies of two merged chapters, the first source ranked ahead of the second. */
    private val stitch = listOf(
        ChapterUnit(chapterId = 10, unit = 0, copyOrder = 0),
        ChapterUnit(chapterId = 20, unit = 0, copyOrder = 1),
        ChapterUnit(chapterId = 11, unit = 1, copyOrder = 0),
        ChapterUnit(chapterId = 21, unit = 1, copyOrder = 1),
    )

    private fun render(vararg rows: Row) = renderStoredStitch(rows.toList(), stitch) { it.id }

    @Test
    @DisplayName("one chapter per merged chapter, in the stitch's order")
    fun oneChapterPerUnit() {
        val shown = render(Row(20), Row(21), Row(10), Row(11))

        shown.map { it.id } shouldBe listOf(10L, 11L)
    }

    @Test
    @DisplayName("a hidden copy falls through to the next source's")
    fun hiddenCopyFallsThrough() {
        // What an excluded scanlator or a source removed from the library looks like from here: the
        // row is simply not handed in, and the merged chapter is still shown.
        val shown = render(Row(20), Row(11))

        shown.map { it.id } shouldBe listOf(20L, 11L)
    }

    @Test
    @DisplayName("a merged chapter with every copy hidden drops out")
    fun fullyHiddenUnitDropsOut() {
        val shown = render(Row(10), Row(20))

        shown.map { it.id } shouldBe listOf(10L)
    }

    @Test
    @DisplayName("nothing stitched means the list is its own")
    fun unstitchedListIsItsOwn() {
        val rows = listOf(Row(3), Row(1), Row(2))

        renderStoredStitch(rows, emptyList()) { it.id } shouldBe rows
    }

    @Test
    @DisplayName("a chapter read on another source reads as read")
    fun readOnSiblingCountsAsRead() {
        val rows = listOf(Row(10), Row(20, read = true), Row(11))

        readOnAnotherSource(rows, render(*rows.toTypedArray()), stitch, { it.id }, { it.read }) shouldBe setOf(10L)
    }

    @Test
    @DisplayName("a chapter nobody has read is not reported")
    fun unreadEverywhereIsNotReported() {
        val rows = listOf(Row(10), Row(20), Row(11))

        readOnAnotherSource(rows, render(*rows.toTypedArray()), stitch, { it.id }, { it.read }).isEmpty() shouldBe true
    }

    @Test
    @DisplayName("an ungrouped entry reports nothing read elsewhere")
    fun ungroupedReportsNothing() {
        val rows = listOf(Row(10, read = true), Row(11))

        readOnAnotherSource(rows, rows, emptyList(), { it.id }, { it.read }).isEmpty() shouldBe true
    }

    @Test
    @DisplayName("acting on a chapter reaches the group's other copies of it")
    fun expandReachesTheSiblingCopy() {
        expandToUnits(setOf(10L), stitch) shouldBe setOf(10L, 20L)
    }

    @Test
    @DisplayName("acting on a chapter no stitch knows reaches only itself")
    fun expandLeavesAnUnknownChapterAlone() {
        expandToUnits(setOf(99L), stitch) shouldBe setOf(99L)
    }
}
