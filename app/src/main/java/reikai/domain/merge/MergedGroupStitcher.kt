package reikai.domain.merge

import reikai.domain.library.ContentType

/**
 * Stitching one merge group of one content type: loading its library members' chapters and running
 * the cross-source stitch over them. The only per-type part of keeping the stored stitch current, so
 * the reconciliation itself is written once.
 */
interface MergedGroupStitcher {

    val contentType: ContentType

    /** [groupId]'s stitch, as rows to store. Empty when the group has nothing left to stitch. */
    suspend fun stitch(groupId: Long): List<MergedChapterUnitRepository.StoredUnit>
}

/**
 * Every chapter that went into a stitch, as a row to store. A chapter the stitch dropped is stored
 * with no unit rather than left out, so it counts nowhere while staleness can still see it.
 */
fun <T> storedUnitsOf(
    chapters: List<T>,
    merged: MergedChapters<T>,
    id: (T) -> Long,
    name: (T) -> String,
    number: (T) -> Double,
): List<MergedChapterUnitRepository.StoredUnit> {
    val placed = merged.units.associateBy { it.chapterId }
    return chapters.map { chapter ->
        val unit = placed[id(chapter)]
        MergedChapterUnitRepository.StoredUnit(
            chapterId = id(chapter),
            unit = unit?.unit,
            copyOrder = unit?.copyOrder ?: 0,
            chapterName = name(chapter),
            chapterNumber = number(chapter),
        )
    }
}
