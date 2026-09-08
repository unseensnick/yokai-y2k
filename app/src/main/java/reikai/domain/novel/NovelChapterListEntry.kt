package reikai.domain.novel

import reikai.domain.merge.ChapterGap
import reikai.domain.novel.model.NovelChapter

/**
 * A row in the novel details chapter list: either a chapter or a "N missing chapters" separator
 * inserted between two chapters whose numbers skip one or more integers. The novel twin of manga's
 * `ChapterList.Item` / `ChapterList.MissingCount` (see MangaViewModel.chapterListItems).
 */
sealed interface NovelChapterListEntry {
    data class Item(val chapter: NovelChapter) : NovelChapterListEntry
    data class Missing(val id: String, val count: Int) : NovelChapterListEntry
}

/**
 * Build the display list, inserting a [NovelChapterListEntry.Missing] wherever consecutive chapter
 * numbers leave a gap. Mirrors manga's `insertSeparators` swap logic: [sortDescending] flips which
 * neighbour is the higher number, the leading gap (ascending, before the first) and trailing gap
 * (descending, after the last) use `floor(number) - 1`, and an unrecognized number (< 0) yields no
 * separator (the Double [calculateChapterGap] overload returns 0 for it).
 */
fun buildNovelChapterListEntries(
    chapters: List<NovelChapter>,
    sortDescending: Boolean,
): List<NovelChapterListEntry> {
    val result = ArrayList<NovelChapterListEntry>(chapters.size)
    for (i in 0..chapters.size) {
        val before = chapters.getOrNull(i - 1)
        val after = chapters.getOrNull(i)
        missingSeparator(before, after, sortDescending)?.let(result::add)
        if (after != null) result.add(NovelChapterListEntry.Item(after))
    }
    return result
}

private fun missingSeparator(
    before: NovelChapter?,
    after: NovelChapter?,
    sortDescending: Boolean,
): NovelChapterListEntry.Missing? {
    val (lowerChapter, higherChapter) = if (sortDescending) after to before else before to after
    if (higherChapter == null) return null

    val count = ChapterGap.between(higherChapter.toGapNeighbour(), lowerChapter?.toGapNeighbour())
    return count.takeIf { it > 0 }?.let {
        NovelChapterListEntry.Missing(
            id = "${lowerChapter?.id}-${higherChapter.id}",
            count = it,
        )
    }
}

/** A merged list's neighbours can come from different sources, so the owning novel travels with the
 *  number the gap is computed from. */
private fun NovelChapter.toGapNeighbour() = ChapterGap.Neighbour(chapterNumber, name, novelId)

/** The header's total, over the same rule the inline markers use, so the two cannot disagree. */
fun novelMissingChapterCount(chapters: List<NovelChapter>, sortDescending: Boolean): Int =
    ChapterGap.total(chapters.map { it.toGapNeighbour() }, sortDescending)
