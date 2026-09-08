package reikai.domain.manga

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.SourceManager

/**
 * Covers the reading-order policy [MergedChapterProvider.aggregate] adds on top of
 * [ChapterAggregation] (which has its own stitch/dedup tests): the unified list is ordered
 * newest-first and `sourceOrder` is reindexed so a "by source order" sort doesn't interleave sources.
 */
class MergedChapterProviderTest {

    private var nextId = 1L

    private fun chapter(mangaId: Long, number: Double): Chapter =
        Chapter.create().copy(id = nextId++, mangaId = mangaId, chapterNumber = number, name = "Chapter $number")

    private fun provider(preferred: List<Long> = emptyList()): MergedChapterProvider {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { preferredMangaSources } returns mockk(relaxed = true) { every { get() } returns preferred }
        }
        // No per-group override in these cases, so the global preferred list drives the ranking.
        val mergeManager = mockk<MangaMergeManager> {
            coEvery { overrideRankingMemberIds(any()) } returns emptyList()
        }
        // Non-gallery sources: a relaxed mock returns null from get(), so these serial-manga cases keep
        // the normal cross-source number dedup.
        val sourceManager = mockk<SourceManager>(relaxed = true)
        return MergedChapterProvider(mockk(), mergeManager, sourceManager, preferences)
    }

    /** Newest first, stamped in the order the rows come back, which is how a manga source lists them. */
    private fun source(mangaId: Long, vararg numbers: Double): List<Chapter> =
        numbers.mapIndexed { index, number -> chapter(mangaId, number).copy(sourceOrder = index.toLong()) }

    @Test
    fun `aggregate orders the unified list newest-first across sources`() = runTest {
        val source1 = source(1L, 3.0, 2.0, 1.0)
        val source2 = source(2L, 4.0, 3.0) // 4 gap-fills from the other source

        val unified = provider().aggregate(mapOf(1L to source1, 2L to source2), mapOf(1L to 100L, 2L to 200L))

        unified.map { it.chapterNumber } shouldBe listOf(4.0, 3.0, 2.0, 1.0)
    }

    @Test
    fun `aggregate restamps source order to match reading order`() = runTest {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0))

        val unified = provider().aggregate(mapOf(1L to source1, 2L to source2), mapOf(1L to 100L, 2L to 200L))

        unified.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L, 3L)
    }

    /**
     * Opening a chapter the cross-source dedup dropped (from a non-preferred source's chip, or from
     * history / updates). It carries its own source's `sourceOrder`, and the reader sorts on that
     * alone, so it has to be renumbered into the unified list's scale or it lands at an arbitrary
     * index and prev/next breaks.
     */
    private suspend fun dedupedOut(): Pair<List<Chapter>, Chapter> {
        val preferred = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        // Source 2 loses chapter 3 to the preferred source, so opening its own copy re-adds it.
        val opened = chapter(2L, 3.0).copy(sourceOrder = 42L)
        val unified = provider(preferred = listOf(100L))
            .aggregate(mapOf(1L to preferred, 2L to listOf(opened)), mapOf(1L to 100L, 2L to 200L))
        return unified to opened
    }

    @Test
    fun `a deduped-out opened chapter is re-added to the list`() = runTest {
        val (unified, opened) = dedupedOut()

        val chapters = provider().withOpenedChapter(unified, opened)

        // By size, not membership: proves the dedup really dropped it and it came back, where
        // `any { id == opened.id }` would also pass if it had never been dropped.
        chapters.size shouldBe unified.size + 1
    }

    @Test
    fun `re-adding an opened chapter renumbers the list into one source order scale`() = runTest {
        val (unified, opened) = dedupedOut()

        val chapters = provider().withOpenedChapter(unified, opened)

        chapters.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L, 3L)
    }

    @Test
    fun `a chapter already in the list leaves the source order untouched`() = runTest {
        val single = listOf(chapter(1L, 1.0), chapter(1L, 2.0))

        val chapters = provider().withOpenedChapter(single, single.first())

        chapters shouldBe single
    }

    @Test
    fun `a chapter read on another source is reported as read for the group`() = runTest {
        // Source 1 leads and its chapter 2 is unread; source 2's copy of chapter 2 is read.
        val leadUnread = chapter(1, 2.0)
        val chaptersBySource = mapOf(
            1L to listOf(chapter(1, 1.0), leadUnread),
            2L to listOf(chapter(2, 1.0), chapter(2, 2.0).copy(read = true)),
        )
        val unified = listOf(chaptersBySource.getValue(1L)[0], leadUnread)

        val result = provider().readInOtherSources(
            chaptersBySource,
            sourceIdByManga = mapOf(1L to 10L, 2L to 20L),
            unified = unified,
        )

        result shouldBe setOf(leadUnread.id)
    }

    @Test
    fun `a chapter nobody has read is not reported`() = runTest {
        val chaptersBySource = mapOf(
            1L to listOf(chapter(1, 1.0)),
            2L to listOf(chapter(2, 1.0)),
        )
        val unified = listOf(chaptersBySource.getValue(1L)[0])

        val result = provider().readInOtherSources(
            chaptersBySource,
            sourceIdByManga = mapOf(1L to 10L, 2L to 20L),
            unified = unified,
        )

        result.isEmpty() shouldBe true
    }

    @Test
    fun `an unmerged entry reports nothing`() = runTest {
        val chaptersBySource = mapOf(1L to listOf(chapter(1, 1.0)))

        val result = provider().readInOtherSources(
            chaptersBySource,
            sourceIdByManga = mapOf(1L to 10L),
            unified = chaptersBySource.getValue(1L),
        )

        result.isEmpty() shouldBe true
    }

    @Test
    fun `an unrecognized chapter number cannot stand in for another source's`() = runTest {
        // A negative number has no cross-source identity, so a read one elsewhere proves nothing.
        val leadUnread = chapter(1, -1.0)
        val chaptersBySource = mapOf(
            1L to listOf(leadUnread),
            2L to listOf(chapter(2, -1.0).copy(read = true)),
        )

        val result = provider().readInOtherSources(
            chaptersBySource,
            sourceIdByManga = mapOf(1L to 10L, 2L to 20L),
            unified = listOf(leadUnread),
        )

        result.isEmpty() shouldBe true
    }
}
