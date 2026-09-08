package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The one skip-duplicate rule both readers now run. The novel reader used to step over duplicates while
 * navigating instead of removing them, so its chapter sheet, download-ahead and delete-after-read each
 * counted chapters the reader would never stop on.
 */
class DuplicateChaptersTest {

    private data class Ch(val id: Long, val number: Double, val origin: String?, val owner: Long = 1L)

    private fun List<Ch>.dedup(current: Ch) = removeDuplicateChapters(
        current,
        numberOf = { it.number },
        idOf = { it.id },
        originOf = { it.origin },
        ownerOf = { it.owner },
    )

    private val a1 = Ch(id = 1, number = 1.0, origin = "alpha")
    private val b1 = Ch(id = 2, number = 1.0, origin = "beta")
    private val a2 = Ch(id = 3, number = 2.0, origin = "alpha")
    private val b2 = Ch(id = 4, number = 2.0, origin = "beta")

    @Test
    fun `each chapter number survives exactly once`() {
        listOf(a1, b1, a2, b2).dedup(a1).map { it.number } shouldBe listOf(1.0, 2.0)
    }

    @Test
    fun `the chapter being read is the one kept from its own group`() {
        listOf(a1, b1).dedup(b1).map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `other groups keep the entry from the same origin as the current chapter`() {
        listOf(a1, b1, a2, b2).dedup(b1).map { it.id } shouldBe listOf(2L, 4L)
    }

    @Test
    fun `the chapter being read wins over a sibling from the same origin`() {
        // Origin alone cannot decide this group: both entries are "beta", and the one being read is
        // second. Only matching on identity keeps the reader on the chapter it is already showing.
        val betaTwin = Ch(id = 10, number = 1.0, origin = "beta")
        listOf(betaTwin, b1).dedup(b1).map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `a group with no entry from that origin falls back to its first`() {
        val orphan = Ch(id = 5, number = 3.0, origin = "gamma")
        listOf(b1, orphan).dedup(b1).map { it.id } shouldBe listOf(2L, 5L)
    }

    @Test
    fun `a list with nothing duplicated is returned unchanged`() {
        listOf(a1, a2).dedup(a1) shouldBe listOf(a1, a2)
    }

    @Test
    fun `two sources of a merged entry keep both chapters, however they number them`() {
        // Across a merge group a number identifies nothing: each source counts its own way, and the
        // stitch has already decided what is one chapter. Collapsing here ate a distinct chapter.
        val fromOne = Ch(id = 20, number = 5.0, origin = "alpha", owner = 1L)
        val fromTwo = Ch(id = 21, number = 5.0, origin = "beta", owner = 2L)

        listOf(fromOne, fromTwo).dedup(fromOne).map { it.id } shouldBe listOf(20L, 21L)
    }

    @Test
    fun `an origin nobody has still resolves, because a group always keeps one`() {
        // Novels carry no scanlator, so an unmerged novel's chapters can all answer null here.
        val n1 = Ch(id = 6, number = 1.0, origin = null)
        val n2 = Ch(id = 7, number = 1.0, origin = null)
        listOf(n1, n2).dedup(Ch(id = 9, number = 5.0, origin = null)).map { it.id } shouldBe listOf(6L)
    }
}
