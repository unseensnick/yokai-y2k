package reikai.domain.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.renderStoredStitch
import reikai.domain.novel.model.NovelChapter

/**
 * The novel twin of [reikai.domain.manga.MergedChapterProvider]: a group's chapter list as the stored
 * stitch decided it, never stitched at the call site. Pinned to the manga side by the one stitch both
 * store through and by the conformance test over the badge; only the id types differ.
 */
@Inject
@SingleIn(AppScope::class)
class NovelMergedChapterProvider(
    private val mergeManager: NovelMergeManager,
    private val units: MergedChapterUnitRepository,
    private val reconcile: ReconcileMergedChapters,
) {

    /** The group's stored stitch, rebuilt first when it is stale. Empty when the novel is ungrouped. */
    suspend fun stitchOf(anchorId: Long): List<ChapterUnit> {
        val groupId = mergeManager.groupIdOf(anchorId) ?: return emptyList()
        reconcile.awaitGroup(ContentType.NOVELS, groupId)
        return units.getStitch(ContentType.NOVELS, groupId)
    }

    /** [chapters] as the merged reading order [stitch] describes, source order restamped onto it, so a
     *  "by source order" sort reads top to bottom instead of interleaving the sources. */
    fun merged(chapters: List<NovelChapter>, stitch: List<ChapterUnit>): List<NovelChapter> =
        renderStoredStitch(chapters, stitch) { it.id }
            .let { merged ->
                if (stitch.isEmpty()) {
                    merged
                } else {
                    merged.mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }
                }
            }
}
