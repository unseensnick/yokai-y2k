package reikai.domain.merge

/**
 * Renders a merge group's stored stitch: one chapter per merged chapter, in the stitch's order, taking
 * the highest-ranked copy that survived the caller's own filters. Every surface showing a grouped
 * series goes through here, so none can reach a different answer than the library badge.
 *
 * [chapters] is what the caller can show, already filtered; a merged chapter whose every copy was
 * filtered away drops out. An empty [stitch] means nothing to merge, so the list is its own.
 */
fun <T> renderStoredStitch(chapters: List<T>, stitch: List<ChapterUnit>, id: (T) -> Long): List<T> {
    if (stitch.isEmpty()) return chapters
    val available = chapters.associateBy(id)
    return stitch.asSequence()
        .filter { it.chapterId in available }
        .groupBy { it.unit }
        .toSortedMap()
        .values
        .mapNotNull { copies -> available[copies.minBy { it.copyOrder }.chapterId] }
}

/**
 * Chapters of [shown] that are unread on their own row but already read on another source of the
 * group. A merged chapter appears once, so without this it reads as unread purely because the copy the
 * stitch ranked first happens to be the unread one. [chapters] is every member's chapters, which is
 * where the other copies live.
 */
fun <T> readOnAnotherSource(
    chapters: List<T>,
    shown: List<T>,
    stitch: List<ChapterUnit>,
    id: (T) -> Long,
    read: (T) -> Boolean,
): Set<Long> {
    if (stitch.isEmpty()) return emptySet()
    val readIds = chapters.asSequence().filter(read).mapTo(HashSet(), id)
    if (readIds.isEmpty()) return emptySet()
    val readUnits = stitch.asSequence().filter { it.chapterId in readIds }.mapTo(HashSet()) { it.unit }
    if (readUnits.isEmpty()) return emptySet()
    val unitByChapterId = stitch.associate { it.chapterId to it.unit }
    return shown.asSequence()
        .filter { !read(it) && unitByChapterId[id(it)] in readUnits }
        .mapTo(HashSet(), id)
}

/**
 * Every chapter that is the same merged chapter as one of [chapterIds], the given ones included. What
 * an action taken on the merged list has to reach, so marking a chapter read marks the group's copies
 * of it and not a chapter a few along that happens to share a number.
 */
fun expandToUnits(chapterIds: Set<Long>, stitch: List<ChapterUnit>): Set<Long> {
    if (stitch.isEmpty()) return chapterIds
    val units = stitch.asSequence().filter { it.chapterId in chapterIds }.mapTo(HashSet()) { it.unit }
    if (units.isEmpty()) return chapterIds
    return stitch.asSequence().filter { it.unit in units }.mapTo(HashSet(chapterIds)) { it.chapterId }
}
