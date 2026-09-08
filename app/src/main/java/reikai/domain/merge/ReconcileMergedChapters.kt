package reikai.domain.merge

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

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
                repository.replaceGroup(stitcher.contentType, groupId, stitcher.stitch(groupId))
            }
        }
    }
}
