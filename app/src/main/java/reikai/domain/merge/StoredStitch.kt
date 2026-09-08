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

/** Which merged chapter a copy belongs to, for a caller deduplicating a list of its own. */
data class MergedChapterKey(val groupId: Long, val unit: Int)

/**
 * What an update run should count and announce, and what it needs to deduplicate anything else it
 * gathered. [announced] is one chapter per merged chapter: an entry in no group keeps all of its own,
 * and within a group a chapter is news only if the group did not already have that merged chapter,
 * the stitch's first-ranked copy standing for the ones arriving together.
 */
class CollapsedArrivals(val announced: Set<Long>, val keyOf: Map<Long, MergedChapterKey>) {
    /** [chapterId]'s merged chapter, or the id itself where the stitch places it alone. Deduplicating
     *  a list on this keeps one copy per merged chapter without deciding which. */
    fun dedupeKey(chapterId: Long): Any = keyOf[chapterId] ?: chapterId
}

/**
 * The arrivals of an update run, collapsed per merge group. [stitches] must be current, so run the
 * reconciliation first: a group with no stitch keeps every chapter, which is what "not stitched yet"
 * has to mean rather than "nothing arrived". A chapter the stitch places nowhere is kept too: it is
 * still a row the Updates feed lists, and dropping it would announce fewer chapters than that feed.
 */
fun <T> collapseNewChapters(
    newByEntry: Map<Long, List<T>>,
    groupOf: Map<Long, Long>,
    stitches: Map<Long, List<ChapterUnit>>,
    id: (T) -> Long,
): CollapsedArrivals {
    val announced = HashSet<Long>()
    val keyOf = HashMap<Long, MergedChapterKey>()
    val pooled = HashMap<Long, MutableList<T>>()
    newByEntry.forEach { (entryId, chapters) ->
        val groupId = groupOf[entryId]
        if (groupId == null) {
            chapters.mapTo(announced, id)
        } else {
            pooled.getOrPut(groupId) { mutableListOf() } += chapters
        }
    }
    pooled.forEach { (groupId, chapters) ->
        val stitch = stitches[groupId].orEmpty()
        if (stitch.isEmpty()) {
            chapters.mapTo(announced, id)
            return@forEach
        }
        val arrived = chapters.mapTo(HashSet(), id)
        stitch.forEach { if (it.chapterId in arrived) keyOf[it.chapterId] = MergedChapterKey(groupId, it.unit) }
        val alreadyHad = stitch.asSequence().filter { it.chapterId !in arrived }.mapTo(HashSet()) { it.unit }
        arrived.filterTo(announced) { it !in keyOf }
        stitch.asSequence()
            .filter { it.chapterId in arrived && it.unit !in alreadyHad }
            .groupBy { it.unit }
            .values
            .forEach { copies -> announced += copies.minBy { it.copyOrder }.chapterId }
    }
    return CollapsedArrivals(announced, keyOf)
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
