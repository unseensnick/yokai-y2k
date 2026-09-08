package reikai.domain.novel

import reikai.domain.merge.MergedChapterOrder
import reikai.domain.merge.MergedChapters
import reikai.domain.merge.unstitchedChapters
import reikai.domain.novel.model.NovelChapter

/**
 * Pure cross-source chapter stitcher for merged novel groups, the novel analogue of
 * [reikai.domain.manga.ChapterAggregation]. Trunk = the preferred source if one is in the group, else the
 * source with the most chapters. Chapters match across sources by [matchKey]: normalized TITLE TEXT when
 * the name has any, else the recognized number. Title-first survives the off-by-one numbers sources
 * disagree on and the title-only MTL sources that ship no number. Each [NovelChapter] keeps its own
 * `novelId`, so it can be read from its origin source.
 */
object NovelChapterAggregation {

    /**
     * @param chaptersByNovel each grouped novel's id mapped to that novel's chapters.
     * @param sourceIdByNovel each grouped novel's id mapped to its source id (for the priority rank).
     * @param preferredSourceIds the global preferred-source ranking, highest priority first.
     * @param memberRanking a per-group override: the member novel ids in the group's trunk order. When
     *   non-empty it ranks members by position and [preferredSourceIds] is ignored, so two members
     *   sharing a source still order distinctly. Empty uses the source list.
     * @return the unified chapter list in reading order, each chapter's `sourceOrder` restamped as its
     *   position in that order, which is the only index comparable across the group. For 0 or 1 novel,
     *   the input unchanged.
     */
    fun aggregate(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): List<NovelChapter> =
        merge(chaptersByNovel, sourceIdByNovel, preferredSourceIds, memberRanking).chapters

    /**
     * [aggregate] plus which merged chapter every input chapter belongs to, for the callers that have
     * to count the group once rather than render it. Same walk, so the two can never disagree.
     */
    fun merge(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): MergedChapters<NovelChapter> {
        if (chaptersByNovel.size <= 1) {
            return unstitchedChapters(chaptersByNovel.values.firstOrNull().orEmpty()) { it.id }
        }

        val ranked = rank(chaptersByNovel, sourceIdByNovel, preferredSourceIds, memberRanking)

        // No usable keys on the trunk -> no reliable cross-source matching, so just show its full list.
        val trunk = ranked.first()
        if (trunk.chapters.none { matchKey(it) != null }) return unstitchedChapters(trunk.chapters) { it.id }

        val order = MergedChapterOrder(::matchKey)
        ranked.forEachIndexed { index, source ->
            order.startSource()
            val isTrunk = index == 0
            for (chapter in source.chapters) {
                // Keep every trunk chapter (no intra-source collapse: novels have no scanlator
                // variants, so distinct rows that happen to share a title are still distinct).
                if (isTrunk) {
                    order.place(chapter)
                    continue
                }
                val existing = order.positionOf(chapter)
                // Already covered, so this source carries on from where its copy sits.
                if (existing >= 0) {
                    order.followTo(existing, chapter)
                    continue
                }
                val key = matchKey(chapter)
                when {
                    // Unkeyable siblings drop: nothing identifies them and nothing places them.
                    key == null -> {}
                    // A title is an identity, so a new one is a chapter this source alone has.
                    key.startsWith(TITLE_KEY_PREFIX) -> order.place(chapter)
                    // Only a number, which the other source counts differently, so it identifies
                    // nothing across the group. Its position decides instead.
                    else -> order.defer(chapter)
                }
            }
        }
        val stitched = order.result()
        // The merged position, which is the only order comparable across sources. Overwrites each
        // chapter's own index; these are copies, so nothing persists.
        val merged = stitched.merged.mapIndexed { position, chapter ->
            chapter.copy(sourceOrder = position.toLong())
        }
        return MergedChapters(merged, stitched.units { it.id })
    }

    /**
     * The member novel ids in trunk order (first = trunk), the same ranking [aggregate] applies. Lets the
     * manage-sources dialog badge the primary source without stitching the whole chapter list.
     */
    fun rankedMemberIds(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): List<Long> = rank(chaptersByNovel, sourceIdByNovel, preferredSourceIds, memberRanking).map { it.novelId }

    // Rank by preferred-source priority first (a ranked source wins the trunk regardless of count), then
    // chapter count desc, then novel id asc for a stable order. A per-group override ranks by member id
    // directly (memberRanking), bypassing the source list.
    private fun rank(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String>,
        preferredSourceIds: List<String>,
        memberRanking: List<Long>,
    ): List<RankedSource> = chaptersByNovel.entries
        .map { (novelId, chapters) ->
            val prefRank = if (memberRanking.isNotEmpty()) {
                memberRanking.indexOf(novelId).takeIf { it >= 0 } ?: Int.MAX_VALUE
            } else {
                sourceIdByNovel[novelId]
                    ?.let { preferredSourceIds.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            }
            RankedSource(novelId, chapters, prefRank)
        }
        .sortedWith(
            compareBy<RankedSource> { it.prefRank }
                .thenByDescending { it.chapters.size }
                .thenBy { it.novelId },
        )

    /**
     * The cross-source identity of a chapter, or null when it has none. Prefers the normalized title
     * text (drops "chapter"/"vol" label words, the leading chapter-number tokens, and punctuation);
     * falls back to the recognized chapter number for numeric-only names. Used for both the unified merge
     * and the read/bookmark propagation across grouped sources.
     */
    fun matchKey(chapter: NovelChapter): String? = matchKey(chapter.name, chapter.chapterNumber)

    /**
     * Value-based overload, for callers that hold a chapter's fields without the row (the match-key
     * reconciliation reads them straight out of SQL). Keeps one definition of the identity rather
     * than a second copy that can drift.
     */
    fun matchKey(name: String, chapterNumber: Double): String? {
        val title = normalizedTitle(name)
        if (title.isNotEmpty()) return "$TITLE_KEY_PREFIX$title"
        if (chapterNumber > 0.0) return "n:$chapterNumber"
        return null
    }

    /** Marks a key built from title text rather than from a number, which is the identity that
     *  survives two sources counting differently. */
    private const val TITLE_KEY_PREFIX = "t:"

    private val labelWords = setOf(
        "chapter", "ch", "chap", "episode", "ep", "part", "pt", "vol", "volume", "book", "season", "s",
    )
    private val numberToken = Regex("""^[0-9]+(\.[0-9]+)?$""")
    private val nonAlphanumeric = Regex("""[^a-z0-9]+""")

    // Drops label words anywhere and only the LEADING chapter-number tokens; a number that follows a
    // title word is kept, so "Pleasureful Repeats 2" stays distinct from "Pleasureful Repeats" (else a
    // sequel-titled sibling chapter collides and gets deduped out of the unified list), while
    // "Chapter 1 - 0 Surviving Just to Die" and "0 Surviving Just to Die" still both reduce to
    // "surviving just to die".
    private fun normalizedTitle(name: String): String {
        val out = mutableListOf<String>()
        var seenWord = false
        for (token in name.lowercase().replace(nonAlphanumeric, " ").trim().split(' ')) {
            when {
                token.isEmpty() || token in labelWords -> {}
                numberToken.matches(token) -> if (seenWord) out.add(token)
                else -> {
                    out.add(token)
                    seenWord = true
                }
            }
        }
        return out.joinToString(" ")
    }

    private class RankedSource(
        val novelId: Long,
        val chapters: List<NovelChapter>,
        val prefRank: Int,
    )
}
