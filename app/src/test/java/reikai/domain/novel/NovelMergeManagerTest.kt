package reikai.domain.novel

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
 * The manager is a thin adapter over [MergeGroupRepository]; its own logic is the master-switch gate and
 * the group-key mapping. The grouping math is covered by MergeGroupRepositoryTest.
 */
class NovelMergeManagerTest {

    private fun manager(
        repository: MergeGroupRepository = mockk(relaxed = true),
        mergingEnabled: Boolean = true,
    ): NovelMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { seriesMergingEnabled } returns mockk(relaxed = true) { every { get() } returns mergingEnabled }
        }
        return NovelMergeManager(repository, preferences) { dissolved += it }
    }

    /** Groups handed to the dissolve hook, in call order, so the tracker-copy step can be asserted. */
    private val dissolved = mutableListOf<List<Long>>()

    @Test
    fun `computeRelatedIds returns the group members`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.NOVELS, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `relatedIdsList returns the group members`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.NOVELS, 7L) } returns listOf(1L, 2L)
        }

        manager(repo).relatedIdsList(1L) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `computeRelatedIds leaves out a member that is no longer in the library`() = runTest {
        // Twin of the manga case: the scoping lives on the shared base, so both types stay pinned.
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.NOVELS, 7L) } returns listOf(1L, 3L)
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 3L)
    }

    @Test
    fun `computeRelatedIds falls back to the target when the whole group has left the library`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.NOVELS, 7L) } returns emptyList()
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `handOutTrackersBeforeRemoval hands each group its members once`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getGroupId(ContentType.NOVELS, 2L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.NOVELS, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).handOutTrackersBeforeRemoval(listOf(1L, 2L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
    }

    @Test
    fun `handOutTrackersBeforeRemoval does nothing while merging is disabled`() = runTest {
        manager(mergingEnabled = false).handOutTrackersBeforeRemoval(listOf(1L))

        dissolved.isEmpty() shouldBe true
    }

    @Test
    fun `computeRelatedIds returns just itself when ungrouped`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns null
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `resolution returns just itself when merging is disabled`() = runTest {
        manager(mergingEnabled = false).computeRelatedIds(1L).toList() shouldBe listOf(1L)
        manager(mergingEnabled = false).relatedIdsList(1L) shouldBe listOf(1L)
    }

    @Test
    fun `merge is refused when merging is disabled`() = runTest {
        // Twin of the manga case: the gate lives on the shared base, so both types must stay pinned.
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(repo, mergingEnabled = false).merge(listOf(1L, 2L))

        coVerify(exactly = 0) { repo.merge(any(), any()) }
    }

    @Test
    fun `merge reaches the repository when merging is enabled`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(repo).merge(listOf(1L, 2L))

        coVerify { repo.merge(ContentType.NOVELS, listOf(1L, 2L)) }
    }

    @Test
    fun `unmerge hands the whole group to the dissolve hook before dissolving`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.NOVELS, 7L) } returns listOf(1L, 2L)
        }

        manager(repo).unmerge(listOf(1L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L))
        // Ordered, like manga's twin: the hook has to read the members while the group still has
        // them, or nobody gets their own copy of the shared tracker link.
        coVerifyOrder {
            repo.getFavoriteMembers(ContentType.NOVELS, 7L)
            repo.dissolve(ContentType.NOVELS, 1L)
        }
    }

    @Test
    fun `clearing every merge hands each group to the dissolve hook`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getAllMemberships(ContentType.NOVELS) } returns mapOf(1L to 7L, 2L to 7L, 5L to 9L)
        }

        manager(repo).clearAllMergesIncludingAuto()

        dissolved shouldContainExactlyInAnyOrder listOf(listOf(1L, 2L), listOf(5L))
        coVerify { repo.clearAll(ContentType.NOVELS) }
    }
}
