package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What an update run counts, announces and downloads when several sources of one merge group report
 * the same chapter. Pinned once for both content types: the jobs differ only in how they read the
 * memberships and the stitch.
 */
class CollapseNewChaptersTest {

    private data class Row(val id: Long)

    /** Group 7 over entries 1 and 2: chapter 10 and 20 are one merged chapter, 11 is another. */
    private val stitch = listOf(
        ChapterUnit(chapterId = 10, unit = 0, copyOrder = 0),
        ChapterUnit(chapterId = 20, unit = 0, copyOrder = 1),
        ChapterUnit(chapterId = 11, unit = 1, copyOrder = 0),
    )

    private fun collapse(newByEntry: Map<Long, List<Row>>, groupOf: Map<Long, Long> = mapOf(1L to 7L, 2L to 7L)) =
        collapseNewChapters(newByEntry, groupOf, mapOf(7L to stitch)) { it.id }

    @Test
    @DisplayName("an entry in no group keeps every chapter it reported")
    fun ungroupedKeepsItsOwn() {
        collapse(mapOf(3L to listOf(Row(30), Row(31))), groupOf = emptyMap()) shouldBe setOf(30L, 31L)
    }

    @Test
    @DisplayName("one chapter reported by both sources is announced once")
    fun oneArrivalPerMergedChapter() {
        collapse(mapOf(1L to listOf(Row(10)), 2L to listOf(Row(20)))) shouldBe setOf(10L)
    }

    @Test
    @DisplayName("the copy the stitch ranks first is the one kept")
    fun theRankedCopyStands() {
        // Only the second source reported it, but the first source's copy is the one the list shows,
        // so a run that hands in both must not pick by which entry updated first.
        collapse(mapOf(2L to listOf(Row(20)), 1L to listOf(Row(10)))) shouldBe setOf(10L)
    }

    @Test
    @DisplayName("a chapter the group already had is not news")
    fun alreadyKnownIsNotNews() {
        // Chapter 10 arrived on the first source in an earlier run and was announced then; the second
        // source catching up is the same chapter, not a new one.
        collapse(mapOf(2L to listOf(Row(20)))) shouldBe emptySet()
    }

    @Test
    @DisplayName("a chapter only one source has is still announced")
    fun aSoleArrivalStands() {
        collapse(mapOf(1L to listOf(Row(11)))) shouldBe setOf(11L)
    }

    @Test
    @DisplayName("a group with no stitch keeps everything, since nothing has decided otherwise")
    fun unstitchedGroupKeepsEverything() {
        collapseNewChapters(
            mapOf(1L to listOf(Row(10)), 2L to listOf(Row(20))),
            mapOf(1L to 7L, 2L to 7L),
            emptyMap(),
        ) { it.id } shouldBe setOf(10L, 20L)
    }

    @Test
    @DisplayName("a chapter the stitch places nowhere is not announced")
    fun droppedChapterIsNotAnnounced() {
        // The stitch drops a copy nothing identifies and nothing places, so it renders on no screen
        // and counts in no badge. Announcing it would offer the user a chapter they cannot open.
        collapse(mapOf(1L to listOf(Row(99)))) shouldBe emptySet()
    }
}
