package reikai.domain.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository

/**
 * The manager is a thin adapter over [MergeGroupRepository] now, so its own logic is the master-switch
 * gate and the group-key mapping; the grouping math itself is covered by MergeGroupRepositoryTest.
 */
class MangaMergeManagerTest {

    /** Groups handed to the dissolve hook, in call order, so the tracker-copy step can be asserted. */
    private val dissolved = mutableListOf<List<Long>>()

    private fun manager(
        repository: MergeGroupRepository = mockk(relaxed = true),
        mergingEnabled: Boolean = true,
    ): MangaMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { seriesMergingEnabled } returns mockk(relaxed = true) { every { get() } returns mergingEnabled }
        }
        return MangaMergeManager(repository, preferences) { dissolved += it }
    }

    @Test
    fun `computeRelatedIds returns the group members`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.MANGA, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `computeRelatedIds leaves out a member that is no longer in the library`() = runTest {
        // The group is preserved so a re-add rejoins it, but a removed source must stop feeding what
        // the group shows: its chapters, its counts, its chip. Every display read comes through here.
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.MANGA, 7L) } returns listOf(1L, 3L)
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 3L)
    }

    @Test
    fun `computeRelatedIds falls back to the target when the whole group has left the library`() = runTest {
        // A caller resolving an entry it is already holding must never get an empty list back.
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.MANGA, 7L) } returns emptyList()
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `handOutTrackersBeforeRemoval hands each group its members once`() = runTest {
        // Two members of one group are being removed together; the hand-out is per group, not per entry.
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getGroupId(ContentType.MANGA, 2L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.MANGA, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).handOutTrackersBeforeRemoval(listOf(1L, 2L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
    }

    @Test
    fun `handOutTrackersBeforeRemoval does nothing while merging is disabled`() = runTest {
        // Nothing resolves as a group with the switch off, so no tracker was ever shared to hand out.
        manager(mergingEnabled = false).handOutTrackersBeforeRemoval(listOf(1L))

        dissolved.isEmpty() shouldBe true
    }

    @Test
    fun `computeRelatedIds returns just itself when ungrouped`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns null
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `computeRelatedIds returns just itself when merging is disabled`() = runTest {
        // The repository must not be consulted when the master switch is off.
        manager(mergingEnabled = false).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `merge is refused when merging is disabled`() = runTest {
        // Nothing renders a group written while the switch is off, and the library's Unmerge action only
        // appears for a row the collapse marked, so the write had no undo on the surface that made it.
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(repo, mergingEnabled = false).merge(listOf(1L, 2L))

        coVerify(exactly = 0) { repo.merge(any(), any()) }
    }

    @Test
    fun `merge reaches the repository when merging is enabled`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(repo).merge(listOf(1L, 2L))

        coVerify { repo.merge(ContentType.MANGA, listOf(1L, 2L)) }
    }

    @Test
    fun `unmerge hands the whole group to the dissolve hook before dissolving`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.MANGA, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).unmerge(listOf(1L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
        // Ordered: the hook has to read the members while the group still has them. Run it after the
        // dissolve and it sees nothing, so nobody gets their own copy of the shared tracker link.
        coVerifyOrder {
            repo.getFavoriteMembers(ContentType.MANGA, 7L)
            repo.dissolve(ContentType.MANGA, 1L)
        }
    }

    @Test
    fun `removeFromGroup hands the group it is leaving to the dissolve hook`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(repo).removeFromGroup(longArrayOf(1L, 2L, 3L), listOf(3L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
    }

    @Test
    fun `clearing every merge hands each group to the dissolve hook`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getAllMemberships(ContentType.MANGA) } returns mapOf(1L to 7L, 2L to 7L, 5L to 9L)
        }

        manager(repo).clearAllMergesIncludingAuto()

        dissolved shouldContainExactlyInAnyOrder listOf(listOf(1L, 2L), listOf(5L))
        coVerify { repo.clearAll(ContentType.MANGA) }
    }
}
