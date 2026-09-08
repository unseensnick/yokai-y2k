package reikai.domain.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterAggregationTest {

    private var nextId = 1L

    private fun chapter(mangaId: Long, number: Double, scanlator: String? = null): Chapter =
        Chapter.create().copy(
            id = nextId++,
            mangaId = mangaId,
            chapterNumber = number,
            scanlator = scanlator,
            name = "Chapter $number",
        ).let { it.copy(url = "/$mangaId/$number/${scanlator.orEmpty()}/${it.id}") }

    private fun List<Chapter>.numbers(): List<Double> = map { it.chapterNumber }.sorted()

    @Test
    fun `trunk is the source with the most distinct numbers, not the most rows`() {
        // Source 1: 3 real chapters, each duplicated across two scanlators -> 6 rows, 3 distinct.
        val source1 = listOf(
            chapter(1L, 1.0, "A"),
            chapter(1L, 1.0, "B"),
            chapter(1L, 2.0, "A"),
            chapter(1L, 2.0, "B"),
            chapter(1L, 3.0, "A"),
            chapter(1L, 3.0, "B"),
        )
        // Source 2: 5 real chapters, single scanlator -> 5 rows, 5 distinct.
        val source2 = listOf(
            chapter(2L, 1.0),
            chapter(2L, 2.0),
            chapter(2L, 3.0),
            chapter(2L, 4.0),
            chapter(2L, 5.0),
        )

        val unified = ChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        // Source 2 wins the trunk despite source 1 having more rows; source 1 adds nothing new.
        unified.size shouldBe 5
        unified.map { it.mangaId }.distinct() shouldBe listOf(2L)
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
    }

    @Test
    fun `gap-fills numbers the trunk is missing from other sources`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val other = listOf(chapter(2L, 4.0), chapter(2L, 5.0), chapter(2L, 6.0), chapter(2L, 7.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)
        // Trunk owns 1..5; only the missing 6 and 7 are borrowed from source 2.
        unified.first { it.chapterNumber == 5.0 }.mangaId shouldBe 1L
        unified.first { it.chapterNumber == 6.0 }.mangaId shouldBe 2L
        unified.first { it.chapterNumber == 7.0 }.mangaId shouldBe 2L
    }

    @Test
    fun `collapses scanlator duplicates when gap-filling`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        // Number 3 appears twice (two scanlators) in the gap-filled source.
        val other = listOf(chapter(2L, 3.0, "A"), chapter(2L, 3.0, "B"), chapter(2L, 4.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.count { it.chapterNumber == 3.0 } shouldBe 1
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `collapses the trunk's own scanlator variants to one row per number`() {
        val trunk = listOf(chapter(1L, 1.0, "A"), chapter(1L, 1.0, "B"), chapter(1L, 2.0))
        val other = listOf(chapter(2L, 1.0), chapter(2L, 2.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.count { it.chapterNumber == 1.0 } shouldBe 1
        unified.numbers() shouldBe listOf(1.0, 2.0)
        unified.map { it.mangaId }.distinct() shouldBe listOf(1L)
    }

    @Test
    fun `drops sibling chapters with an unrecognized number`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val other = listOf(chapter(2L, -1.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        // The unrecognized (-1) sibling chapter can't be matched by number, so it's dropped; 3 fills.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0)
    }

    @Test
    fun `collapses the same number stored as a float in one source and a double in another`() {
        // The source API stores chapter_number as a 32-bit float, so a source that reports a number
        // hands back 1.1f (widened: 1.10000002384...), while ChapterRecognition yields the exact
        // double 1.1. These differ by ~2.4e-8 and must still dedup to one row.
        val floatOrigin = listOf(chapter(1L, 1.0), chapter(1L, 1.1f.toDouble()), chapter(1L, 1.2f.toDouble()))
        val doubleOrigin = listOf(chapter(2L, 1.0), chapter(2L, 1.1), chapter(2L, 1.2))

        val unified = ChapterAggregation.aggregate(mapOf(1L to floatOrigin, 2L to doubleOrigin))

        // One row per logical number despite the float/double representation gap.
        unified.size shouldBe 3
        unified.map { it.chapterNumber.toFloat() }.sorted() shouldBe listOf(1.0f, 1.1f, 1.2f)
    }

    @Test
    fun `keeps genuinely distinct sub-chapters that a coarse round would merge`() {
        // A 0.005-offset source must not collapse 1.005 into 1.0; float narrowing keeps them apart.
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val offset = listOf(chapter(2L, 1.005), chapter(2L, 1.1))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to offset))

        unified.numbers() shouldBe listOf(1.0, 1.005, 1.1, 2.0)
    }

    @Test
    fun `single source returns its chapters unchanged`() {
        val only = listOf(chapter(1L, 1.0, "A"), chapter(1L, 1.0, "B"), chapter(1L, -1.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to only))

        unified shouldBe only
    }

    @Test
    fun `empty input returns empty`() {
        ChapterAggregation.aggregate(emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `a preferred source becomes the trunk even with fewer distinct numbers`() {
        // Source 1 has more distinct numbers, but source 2's source id is preferred.
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(200L),
        )

        // Source 2 wins the trunk (1..3); source 1 only gap-fills 4 and 5.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.mangaId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.mangaId shouldBe 1L
    }

    @Test
    fun `multiple preferred sources order by list index, not distinct count`() {
        val sourceA = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val sourceB = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val sourceC = listOf(chapter(3L, 1.0), chapter(3L, 2.0), chapter(3L, 3.0), chapter(3L, 4.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to sourceA, 2L to sourceB, 3L to sourceC),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L, 3L to 300L),
            // B is ranked first, then A; C is unranked (most distinct, but lowest priority).
            preferredSourceIds = listOf(200L, 100L),
        )

        // B (rank 0) is the trunk owning 1..3; C only gap-fills 4 despite having the most chapters.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
        unified.first { it.chapterNumber == 1.0 }.mangaId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.mangaId shouldBe 3L
    }

    @Test
    fun `a preferred id not present in the group falls back to distinct count`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(999L), // no source in the group has this id
        )

        // Unchanged from the no-preference case: source 1 (most distinct) is the trunk.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.map { it.mangaId }.distinct() shouldBe listOf(1L)
    }

    @Test
    fun `empty preferred list is identical to the no-argument call`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0))
        val bySource = mapOf(1L to source1, 2L to source2)

        val withDefaults = ChapterAggregation.aggregate(bySource)
        val withEmptyPrefs = ChapterAggregation.aggregate(
            chaptersBySource = bySource,
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = emptyList(),
        )

        withEmptyPrefs shouldBe withDefaults
    }

    @Test
    fun `gallery sources are kept whole and never collapsed across sources by number`() {
        // Two gallery sources of the same one-shot. Each numbers its primary chapter 1; the first also
        // lists older versions 2 and 3. Without the exemption they'd collide on 1 and one source drops.
        val galleryA = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val galleryB = listOf(chapter(2L, 1.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to galleryA, 2L to galleryB),
            gallerySourceMangaIds = setOf(1L, 2L),
        )

        // Every gallery chapter survives: A's 3 plus B's 1, including both number-1 rows.
        unified.size shouldBe 4
        unified.count { it.chapterNumber == 1.0 } shouldBe 2
        unified.map { it.mangaId }.toSet() shouldBe setOf(1L, 2L)
    }

    @Test
    fun `without the gallery exemption same-numbered galleries still collapse`() {
        // The pre-fix behavior: serial-manga dedup drops the second source's number-1 gallery.
        val galleryA = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val galleryB = listOf(chapter(2L, 1.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to galleryA, 2L to galleryB))

        unified.size shouldBe 3
        unified.count { it.chapterNumber == 1.0 } shouldBe 1
    }

    @Test
    fun `member ranking makes the chosen member the trunk over distinct count`() {
        // Source 1 has more distinct numbers, but the group's override ranks member 2 first.
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            memberRanking = listOf(2L, 1L),
        )

        // Member 2 wins the trunk (1..3); member 1 only gap-fills 4 and 5.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.mangaId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.mangaId shouldBe 1L
    }

    @Test
    fun `member ranking overrides the preferred-source list`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(100L), // would rank member 1 first
            memberRanking = listOf(2L, 1L), // but the per-group override wins
        )

        // Member 2 leads despite the preferred list favoring member 1's source.
        unified.first { it.chapterNumber == 1.0 }.mangaId shouldBe 2L
    }

    @Test
    fun `member ranking orders two members that share one source`() {
        // The reachable duplicate-row case: two library rows from the same source in one group. A
        // source-id ranking cannot tell them apart; a member-id ranking must, in the order given.
        val first = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val second = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val bySource = mapOf(1L to first, 2L to second)
        val sameSource = mapOf(1L to 100L, 2L to 100L)

        // Member 1 chosen as trunk: it owns 1 and 2, member 2 only gap-fills 3.
        val member1First = ChapterAggregation.aggregate(bySource, sameSource, memberRanking = listOf(1L, 2L))
        member1First.first { it.chapterNumber == 1.0 }.mangaId shouldBe 1L
        member1First.first { it.chapterNumber == 3.0 }.mangaId shouldBe 2L

        // Reversing the override flips the trunk, proving the order is per-member, not per-source.
        val member2First = ChapterAggregation.aggregate(bySource, sameSource, memberRanking = listOf(2L, 1L))
        member2First.first { it.chapterNumber == 1.0 }.mangaId shouldBe 2L
    }

    @Test
    fun `empty member ranking falls back to the preferred-source path`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val bySource = mapOf(1L to source1, 2L to source2)

        val withEmptyOverride = ChapterAggregation.aggregate(
            chaptersBySource = bySource,
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(200L),
            memberRanking = emptyList(),
        )
        val withoutOverride = ChapterAggregation.aggregate(
            chaptersBySource = bySource,
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(200L),
        )

        withEmptyOverride shouldBe withoutOverride
    }

    @Test
    fun `a chapter only the other source has lands between its neighbours`() {
        // The output is a reading order, not a pool the caller sorts: a gap-filled chapter has to sit
        // where it belongs, because no key it carries is comparable across sources.
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 3.0))
        val other = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.map { it.chapterNumber } shouldBe listOf(1.0, 2.0, 3.0)
        unified.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L)
    }
}
