package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The merged download badge: one per chapter the group holds, however many of its sources hold it.
 * Pinned once for both content types, which differ only in the probe they hand in.
 */
class DownloadedUnitsTest {

    private fun row(id: Long, unit: Int, owner: Long) =
        DownloadUnitRow(
            groupId = 7L,
            unit = unit,
            ownerId = owner,
            chapterName = "c$id",
            scanlator = null,
            chapterUrl = "u$id",
        )

    /** Chapters 10 and 20 are one merged chapter, 11 and 21 another; owners 1 and 2. */
    private val rows = listOf(row(10, 0, 1), row(20, 0, 2), row(11, 1, 1), row(21, 1, 2))

    @Test
    @DisplayName("a chapter both sources hold counts once")
    fun bothSourcesHoldItOnce() {
        val onDisk = setOf("c10", "c20")

        downloadedUnitsByGroup(mapOf(7L to rows), setOf(1L, 2L)) { it.chapterName in onDisk } shouldBe mapOf(7L to 1)
    }

    @Test
    @DisplayName("different merged chapters count separately")
    fun distinctChaptersCountApart() {
        val onDisk = setOf("c10", "c21")

        downloadedUnitsByGroup(mapOf(7L to rows), setOf(1L, 2L)) { it.chapterName in onDisk } shouldBe mapOf(7L to 2)
    }

    @Test
    @DisplayName("a group holding nothing reports zero rather than dropping out")
    fun holdingNothingIsZero() {
        downloadedUnitsByGroup(mapOf(7L to rows), setOf(1L)) { false } shouldBe mapOf(7L to 0)
    }

    @Test
    @DisplayName("a member that has downloaded nothing is never probed")
    fun aMemberWithoutDownloadsIsNotProbed() {
        // The probe reads the download cache per chapter, so a library whose merged entries hold no
        // downloads must not pay for one: that is what keeps this off every library emission.
        val probed = mutableListOf<Long>()

        downloadedUnitsByGroup(mapOf(7L to rows), setOf(1L)) {
            probed += it.ownerId
            true
        }

        probed.distinct() shouldBe listOf(1L)
    }

    @Test
    @DisplayName("nothing downloaded anywhere skips the whole pass")
    fun noOwnersSkipsEverything() {
        downloadedUnitsByGroup(mapOf(7L to rows), emptySet()) { error("probed with no owners") } shouldBe emptyMap()
    }
}
