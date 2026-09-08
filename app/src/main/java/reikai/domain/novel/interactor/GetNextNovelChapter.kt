package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.model.NovelChapter

/** A merged novel's chapters in reading order, and the ids another source of the group already read. */
data class NovelGroupChapters(
    val chapters: List<NovelChapter>,
    val readInOtherSources: Set<Long> = emptySet(),
    /** The stored stitch behind [chapters], and every member's chapters, so a caller can ask a
     *  cross-source question (bookmarked anywhere, on disk anywhere) about the copies it stands in for. */
    val stitch: List<ChapterUnit> = emptyList(),
    val pooledChapters: List<NovelChapter> = chapters,
)

/**
 * Novel twin of [tachiyomi.domain.history.interactor.GetNextChapters]: where a novel starts reading,
 * over its own source or across its merge group. Resuming from a recorded chapter is not here, because
 * that rule is shared with manga and lives on the recents surface.
 */
@Inject
class GetNextNovelChapter(
    private val chapterRepository: NovelChapterRepository,
    private val mergeManager: NovelMergeManager,
    private val mergedChapterProvider: NovelMergedChapterProvider,
) {
    /**
     * The first unread chapter, for a row with no recorded chapter to resume from (the recents
     * surface's newly-added lane). Twin of `GetNextChapters.await(mangaId, onlyUnread = true)`, which
     * the manga side already had; without it the lane could only resolve a target for manga.
     */
    suspend fun awaitFirstUnread(novelId: Long, readInOtherSources: Set<Long>): NovelChapter? =
        chapterRepository.getByNovelId(novelId).firstOrNull { !it.read && it.id !in readInOtherSources }

    /**
     * The group's chapters as one cross-source list, the same one the details "All" view shows, plus
     * what counts as read on another source. An unmerged novel gets its own list in source order.
     */
    suspend fun groupChapters(novelId: Long): NovelGroupChapters {
        val memberIds = mergeManager.computeRelatedIds(novelId).toList()
        if (memberIds.size <= 1) {
            return NovelGroupChapters(chapterRepository.getByNovelId(novelId).sortedBy { it.sourceOrder })
        }
        val pooled = memberIds.flatMap { chapterRepository.getByNovelId(it) }
        // The stored stitch, which is the cross-source reading order. A chapter number is not: it is
        // whatever its own source counted, so two sources of one novel disagree.
        val stitch = mergedChapterProvider.stitchOf(novelId)
        val unified = mergedChapterProvider.merged(pooled, stitch)
        return NovelGroupChapters(
            chapters = unified,
            readInOtherSources = flaggedOnAnotherSource(pooled, unified, stitch, { it.id }, { it.read }),
            stitch = stitch,
            pooledChapters = pooled,
        )
    }

    /** The group's first unread chapter, skipping what another of its sources has already read. */
    suspend fun awaitFirstUnreadInGroup(novelId: Long): NovelChapter? {
        val group = groupChapters(novelId)
        return group.chapters.firstOrNull { !it.read && it.id !in group.readInOtherSources }
    }
}
