package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType

/**
 * Storage for what the cross-source stitch decided about a merge group's chapters. See
 * `data/merged_chapter_unit.sq` for why it is a rebuildable cache and why it is kept current by
 * reconciliation rather than by hooking every chapter write.
 */
interface MergedChapterUnitRepository {

    /** Groups of [contentType] whose stored stitch no longer matches the chapters behind them. */
    suspend fun getStaleGroups(contentType: ContentType): List<Long>

    /**
     * [groupId]'s stored stitch, in merged order, for a screen about to render it. Chapters the stitch
     * dropped are left out, since they render nowhere. Empty when the group has never been stitched.
     */
    suspend fun getStitch(contentType: ContentType, groupId: Long): List<ChapterUnit>

    /**
     * Replace [groupId]'s stored stitch with [units], clearing what was there first, in one
     * transaction. Passing none clears the group, which is what a group with too few library members
     * left to stitch comes to.
     */
    suspend fun replaceGroup(contentType: ContentType, groupId: Long, units: List<StoredUnit>)

    /**
     * Unread chapters per merge group: one per chapter the group covers, counted only when no member
     * source's copy is read. A group with everything read produces NO ENTRY, not a zero, so a caller
     * must read a missing group as zero rather than as "no data, keep the last value".
     */
    suspend fun getUnreadCounts(contentType: ContentType): Map<Long, Long>

    /** Reactive [getUnreadCounts]: re-emits when the stitch or any chapter behind it changes, so a
     *  badge is not left showing what the group looked like before it was stitched. */
    fun getUnreadCountsAsFlow(contentType: ContentType): Flow<Map<Long, Long>>

    /**
     * How many of its group's chapters each merged manga covers, keyed by manga id: the count the
     * stitch ranks the trunk on, so the collapsed library row leads on the same source the details
     * chapter list does. An empty map means nothing is stitched yet, which callers must tell apart
     * from a manga covering none.
     */
    suspend fun getCoveredChapterCounts(): Map<Long, Long>

    /**
     * One chapter's place in its group's stitch. [unit] is its position in the merged list, null when
     * the stitch dropped it. The derived values are the identity's inputs, stored so a changed chapter
     * reads as stale; which of them matter is per content type, and the other is written anyway so one
     * shape serves both tables.
     */
    data class StoredUnit(
        val chapterId: Long,
        val unit: Int?,
        val copyOrder: Int,
        val chapterName: String,
        val chapterNumber: Double,
    )
}
