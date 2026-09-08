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
 * Chapters of [shown] whose own row does not carry [flag] but whose copy on another source of the
 * group does. A merged chapter appears once, so without this it reads as unflagged purely because the
 * copy the stitch ranked first happens to be the one without it. [chapters] is every member's
 * chapters, which is where the other copies live. Read, bookmark and downloaded all resolve here, so
 * the three cannot drift into three rules.
 */
fun <T> flaggedOnAnotherSource(
    chapters: List<T>,
    shown: List<T>,
    stitch: List<ChapterUnit>,
    id: (T) -> Long,
    flag: (T) -> Boolean,
): Set<Long> {
    if (stitch.isEmpty()) return emptySet()
    val flaggedIds = chapters.asSequence().filter(flag).mapTo(HashSet(), id)
    if (flaggedIds.isEmpty()) return emptySet()
    val flaggedUnits = stitch.asSequence().filter { it.chapterId in flaggedIds }.mapTo(HashSet()) { it.unit }
    if (flaggedUnits.isEmpty()) return emptySet()
    val unitByChapterId = stitch.associate { it.chapterId to it.unit }
    return shown.asSequence()
        .filter { !flag(it) && unitByChapterId[id(it)] in flaggedUnits }
        .mapTo(HashSet(), id)
}

/**
 * The chapters an update run should count, announce and download, once per merged chapter rather than
 * once per source that reported it. An entry in no group keeps all of its own; within a group a
 * chapter is news only if the group did not already have that merged chapter, and of several copies
 * arriving together the stitch's first-ranked one stands for them. [stitches] must be current, so run
 * the reconciliation first: a group with no stitch keeps every chapter, which is what "not stitched
 * yet" has to mean rather than "nothing arrived".
 */
fun <T> collapseNewChapters(
    newByEntry: Map<Long, List<T>>,
    groupOf: Map<Long, Long>,
    stitches: Map<Long, List<ChapterUnit>>,
    id: (T) -> Long,
): Set<Long> {
    val kept = HashSet<Long>()
    val pooled = HashMap<Long, MutableList<T>>()
    newByEntry.forEach { (entryId, chapters) ->
        val groupId = groupOf[entryId]
        if (groupId == null) chapters.mapTo(kept, id) else pooled.getOrPut(groupId) { mutableListOf() } += chapters
    }
    pooled.forEach { (groupId, chapters) ->
        val stitch = stitches[groupId].orEmpty()
        if (stitch.isEmpty()) {
            chapters.mapTo(kept, id)
            return@forEach
        }
        val arrived = chapters.mapTo(HashSet(), id)
        val alreadyHad = stitch.asSequence().filter { it.chapterId !in arrived }.mapTo(HashSet()) { it.unit }
        val placed = stitch.filter { it.chapterId in arrived && it.unit !in alreadyHad }
        placed.groupBy { it.unit }.values.forEach { copies -> kept += copies.minBy { it.copyOrder }.chapterId }
    }
    return kept
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
