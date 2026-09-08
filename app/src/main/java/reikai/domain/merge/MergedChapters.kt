package reikai.domain.merge

/**
 * A merge group's chapter list plus the identity behind it. [chapters] is the reading order, one row
 * per chapter; [units] places every source's own chapter against one of those rows. A chapter the
 * stitch dropped is in neither, so it counts nowhere.
 *
 * Anything that must count the group once needs [units], not the list: the list alone cannot say
 * whether two sources' rows are one chapter, which is where a second rule creeps in and disagrees.
 */
class MergedChapters<T>(
    val chapters: List<T>,
    val units: List<ChapterUnit>,
)

/**
 * One source's chapter and the merged chapter it belongs to, [unit] indexing the merged list.
 *
 * [copyOrder] ranks the sources holding that merged chapter, 0 being the one the list shows. A
 * surface that hides a copy (an excluded scanlator, a source no longer in the library) falls to the
 * next one, so those stay live filters instead of forcing the group to be stitched again.
 */
class ChapterUnit(val chapterId: Long, val unit: Int, val copyOrder: Int)

/** A list that needed no stitching, so every chapter is its own merged chapter and order is untouched. */
fun <T> unstitchedChapters(chapters: List<T>, id: (T) -> Long) = MergedChapters(
    chapters,
    chapters.mapIndexed { position, chapter -> ChapterUnit(id(chapter), position, 0) },
)
