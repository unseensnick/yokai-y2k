package reikai.domain.merge

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType

/**
 * Brings the stored cross-source stitch back in line with the chapters behind it, for every merge
 * group whose chapters have changed. Written once over both content types: what differs is only how a
 * group's chapters are loaded and stitched, which each [MergedGroupStitcher] answers.
 *
 * Costs one indexed query per content type when nothing has changed, so it is cheap to call from any
 * path that might have written chapters.
 */
@Inject
@SingleIn(AppScope::class)
class ReconcileMergedChapters(
    private val repository: MergedChapterUnitRepository,
    private val stitchers: Set<MergedGroupStitcher>,
) {

    /** Rebuild every stale group of every content type. */
    suspend fun await() {
        stitchers.forEach { stitcher ->
            repository.getStaleGroups(stitcher.contentType).forEach { groupId ->
                rebuild(stitcher.contentType, groupId)
            }
        }
    }

    /**
     * Rebuild [groupId] if it is stale, for a screen about to render it. A group whose chapters just
     * arrived cannot wait for the next library update to be stitched, and a reader that stitched for
     * itself instead is how the surfaces came to disagree in the first place.
     */
    suspend fun awaitGroup(contentType: ContentType, groupId: Long) {
        if (groupId in repository.getStaleGroups(contentType)) rebuild(contentType, groupId)
    }

    private suspend fun rebuild(contentType: ContentType, groupId: Long) {
        val stitcher = stitchers.firstOrNull { it.contentType == contentType } ?: return
        repository.replaceGroup(contentType, groupId, stitcher.stitch(groupId))
    }
}
