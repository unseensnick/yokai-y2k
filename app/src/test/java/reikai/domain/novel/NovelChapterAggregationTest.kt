package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.novel.model.NovelChapter

class NovelChapterAggregationTest {

    private var nextId = 1L

    private fun chapter(
        novelId: Long,
        number: Double,
        title: String = "",
        read: Boolean = false,
    ): NovelChapter =
        NovelChapter(
            id = nextId++,
            novelId = novelId,
            url = "/$novelId/$number/$nextId",
            // A descriptive title exercises the title-based match key; a blank one ("Chapter N")
            // normalizes to empty and falls back to the recognized number.
            name = title.ifBlank { "Chapter $number" },
            read = read,
            bookmark = false,
            lastTextProgress = 0,
            chapterNumber = number,
            sourceOrder = nextId,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )

    private fun List<NovelChapter>.numbers(): List<Double> = map { it.chapterNumber }.sorted()

    @Test
    fun `trunk is the source with the most chapters`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0), chapter(2L, 4.0), chapter(2L, 5.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        unified.size shouldBe 5
        unified.map { it.novelId }.distinct() shouldBe listOf(2L)
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
    }

    @Test
    fun `gap-fills numbers the trunk is missing from other sources`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val other = listOf(chapter(2L, 4.0), chapter(2L, 5.0), chapter(2L, 6.0), chapter(2L, 7.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)
        unified.first { it.chapterNumber == 5.0 }.novelId shouldBe 1L
        unified.first { it.chapterNumber == 6.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 7.0 }.novelId shouldBe 2L
    }

    @Test
    fun `collapses duplicate numbers when gap-filling`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val other = listOf(chapter(2L, 3.0), chapter(2L, 3.0), chapter(2L, 4.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.count { it.chapterNumber == 3.0 } shouldBe 1
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `drops sibling chapters with an unrecognized number`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        // 0.0 means "no number" for novels, so it can't be matched across sources.
        val other = listOf(chapter(2L, 0.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0)
    }

    // Cross-source read carry-over, the twin of MergedChapterProviderTest's manga cases. Run over the
    // shared kernel, against the units this stitch produced, which is what every surface reads.

    private fun readInOtherSources(byNovel: Map<Long, List<NovelChapter>>): Set<Long> {
        val merged = NovelChapterAggregation.merge(byNovel)
        return flaggedOnAnotherSource(byNovel.values.flatten(), merged.chapters, merged.units, { it.id }, { it.read })
    }

    @Test
    fun `a chapter read on another source is reported as read for the group`() {
        val trunk = listOf(chapter(1L, 1.0, "Terminal"))
        val other = listOf(chapter(2L, 1.0, "Terminal", read = true))
        val byNovel = mapOf(1L to trunk, 2L to other)

        val result = readInOtherSources(byNovel)

        result shouldBe setOf(trunk.single().id)
    }

    @Test
    fun `a chapter nobody has read is not reported`() {
        val byNovel = mapOf(1L to listOf(chapter(1L, 1.0, "Terminal")), 2L to listOf(chapter(2L, 1.0, "Terminal")))

        val result = readInOtherSources(byNovel)

        result shouldBe emptySet()
    }

    @Test
    fun `an unmerged novel reports nothing, even where two of its own rows match`() {
        // One source can hold two rows with the same title. Reading one is not reading the other, and
        // only another SOURCE having read it counts.
        val byNovel = mapOf(
            1L to listOf(
                chapter(1L, 1.0, "Terminal", read = true),
                chapter(1L, 1.0, "Terminal"),
            ),
        )

        val result = readInOtherSources(byNovel)

        result shouldBe emptySet()
    }

    @Test
    fun `a different chapter read on another source is not reported`() {
        val trunk = listOf(chapter(1L, 1.0, "Terminal"))
        val other = listOf(chapter(2L, 2.0, "New Arc", read = true))
        val byNovel = mapOf(1L to trunk, 2L to other)

        val result = readInOtherSources(byNovel)

        result shouldBe emptySet()
    }

    @Test
    fun `matches chapters across sources by title when numbers disagree`() {
        // Same chapters, off-by-one numbering across sources, but identical title text.
        val source1 = listOf(chapter(1L, 1.0, "Surviving Just To Die"), chapter(1L, 2.0, "Terminal"))
        val source2 = listOf(
            chapter(2L, 0.0, "surviving just to die"),
            chapter(2L, 1.0, "Terminal"),
            chapter(2L, 2.0, "New Arc"),
        )

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        // 3 distinct chapters by title; the disagreeing numbers don't create duplicates.
        unified.size shouldBe 3
        unified.map { it.name.lowercase() }.toSet() shouldBe setOf("surviving just to die", "terminal", "new arc")
    }

    @Test
    fun `title match ignores a leading chapter label and number`() {
        val source1 = listOf(chapter(1L, 1.0, "Chapter 1 - 0 Surviving Just To Die"))
        val source2 = listOf(chapter(2L, 5.0, "0 Surviving Just to Die"))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        // Both normalize to "surviving just to die", so they collapse to one row.
        unified.size shouldBe 1
    }

    @Test
    fun `keeps every trunk chapter even when two share a title`() {
        // Novels have no scanlator variants, so two distinct trunk chapters with the same title text
        // must both survive (the bug was collapsing the trunk against itself).
        val trunk = listOf(
            chapter(1L, 1.0, "Interlude"),
            chapter(1L, 2.0, "Story"),
            chapter(1L, 3.0, "Interlude"),
        )
        val other = listOf(chapter(2L, 1.0, "Interlude"))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        // All 3 trunk chapters kept; the sibling "Interlude" repeats a key, so it's dropped.
        unified.size shouldBe 3
        unified.map { it.novelId }.distinct() shouldBe listOf(1L)
        unified.count { it.name == "Interlude" } shouldBe 2
    }

    @Test
    fun `keeps a sibling sequel chapter that differs only by a trailing number`() {
        // "Pleasureful Repeats 2" must stay distinct from "Pleasureful Repeats": the trailing number is
        // part of the title, so a sibling's sequel chapter must survive gap-fill, not be deduped out (the
        // bug that showed the chapter present per-source but "missing" in the unified view).
        val trunk = listOf(
            chapter(1L, 1.0, "Pleasureful Repeats"),
            chapter(1L, 2.0, "Missing Book"),
            chapter(1L, 3.0, "Trouble Itself"),
        )
        val other = listOf(chapter(2L, 1.0, "Pleasureful Repeats"), chapter(2L, 1.5, "Pleasureful Repeats 2"))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        // The sibling's "Pleasureful Repeats" repeats the trunk's key and drops; "Pleasureful Repeats 2"
        // keys distinctly and is gap-filled in.
        unified.map { it.name }.toSet() shouldBe
            setOf("Pleasureful Repeats", "Missing Book", "Trouble Itself", "Pleasureful Repeats 2")
        unified.size shouldBe 4
    }

    @Test
    fun `unnumbered novels show the fullest source's full list unchanged`() {
        // Both sources leave every chapter unnumbered (0.0), the common lnreader case. There's no
        // cross-source key, so the unified view is just the source with the most chapters.
        val small = listOf(chapter(1L, 0.0), chapter(1L, 0.0), chapter(1L, 0.0))
        val big = listOf(chapter(2L, 0.0), chapter(2L, 0.0), chapter(2L, 0.0), chapter(2L, 0.0), chapter(2L, 0.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to small, 2L to big))

        unified shouldBe big
        unified.map { it.novelId }.distinct() shouldBe listOf(2L)
    }

    @Test
    fun `single source returns its chapters unchanged`() {
        val only = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 0.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to only))

        unified shouldBe only
    }

    @Test
    fun `empty input returns empty`() {
        NovelChapterAggregation.aggregate(emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `a preferred source becomes the trunk even with fewer chapters`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = listOf("src.b"),
        )

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 1L
    }

    @Test
    fun `multiple preferred sources order by list index, not chapter count`() {
        val sourceA = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val sourceB = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val sourceC = listOf(chapter(3L, 1.0), chapter(3L, 2.0), chapter(3L, 3.0), chapter(3L, 4.0))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to sourceA, 2L to sourceB, 3L to sourceC),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b", 3L to "src.c"),
            preferredSourceIds = listOf("src.b", "src.a"),
        )

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 3L
    }

    @Test
    fun `empty preferred list is identical to the no-argument call`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0))
        val byNovel = mapOf(1L to source1, 2L to source2)

        val withDefaults = NovelChapterAggregation.aggregate(byNovel)
        val withEmptyPrefs = NovelChapterAggregation.aggregate(
            chaptersByNovel = byNovel,
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = emptyList(),
        )

        withDefaults shouldBe withEmptyPrefs
    }

    @Test
    fun `member ranking makes the chosen member the trunk over chapter count`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            memberRanking = listOf(2L, 1L),
        )

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 1L
    }

    @Test
    fun `member ranking overrides the preferred-source list`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = listOf("src.a"), // would rank member 1 first
            memberRanking = listOf(2L, 1L), // but the per-group override wins
        )

        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
    }

    @Test
    fun `member ranking orders two members that share one source`() {
        // Two library rows from the same source in one group: a source-id ranking cannot tell them
        // apart, so the override must rank by member id.
        val first = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val second = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val byNovel = mapOf(1L to first, 2L to second)
        val sameSource = mapOf(1L to "src.a", 2L to "src.a")

        val member1First = NovelChapterAggregation.aggregate(byNovel, sameSource, memberRanking = listOf(1L, 2L))
        member1First.first { it.chapterNumber == 1.0 }.novelId shouldBe 1L
        member1First.first { it.chapterNumber == 3.0 }.novelId shouldBe 2L

        val member2First = NovelChapterAggregation.aggregate(byNovel, sameSource, memberRanking = listOf(2L, 1L))
        member2First.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
    }

    @Test
    fun `two sources counting differently still come out in reading order`() {
        // The shape that broke: both sources number by their own site's position, so the same chapter
        // is 11 on one and 1 on the other. Only the titles agree, and the order must follow them.
        val trunk = listOf(
            chapter(1L, 11.0, "Alpha"),
            chapter(1L, 12.0, "Bravo"),
            chapter(1L, 13.0, "Charlie"),
        )
        val other = listOf(
            chapter(2L, 1.0, "Alpha"),
            chapter(2L, 2.0, "Bravo"),
            chapter(2L, 3.0, "Charlie"),
            chapter(2L, 4.0, "Delta"),
        )

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.map { it.name } shouldBe listOf("Alpha", "Bravo", "Charlie", "Delta")
        unified.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L, 3L)
    }

    @Test
    fun `a chapter only the other source has lands between its neighbours`() {
        val trunk = listOf(chapter(1L, 1.0, "Alpha"), chapter(1L, 3.0, "Charlie"))
        val other = listOf(chapter(2L, 1.0, "Alpha"), chapter(2L, 2.0, "Bravo"), chapter(2L, 3.0, "Charlie"))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.map { it.name } shouldBe listOf("Alpha", "Bravo", "Charlie")
    }
}
