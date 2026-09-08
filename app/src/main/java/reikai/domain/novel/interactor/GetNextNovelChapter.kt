package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter

/** A merged novel's chapters in reading order, and the ids another source of the group already read. */
data class NovelGroupChapters(
    val chapters: List<NovelChapter>,
    val readInOtherSources: Set<Long> = emptySet(),
)

/**
 * Novel twin of [tachiyomi.domain.history.interactor.GetNextChapters]: where a novel starts reading,
 * over its own source or across its merge group. Resuming from a recorded chapter is not here, because
 * that rule is shared with manga and lives on the recents surface.
 */
@Inject
class GetNextNovelChapter(
    private val chapterRepository: NovelChapterRepository,
    private val novelRepository: NovelRepository,
    private val mergeManager: NovelMergeManager,
    private val libraryPreferences: ReikaiLibraryPreferences,
) {
    /**
     * The first unread chapter, for a row with no recorded chapter to resume from (the recents
     * surface's newly-added lane). Twin of `GetNextChapters.await(mangaId, onlyUnread = true)`, which
     * the manga side already had; without it the lane could only resolve a target for manga.
     */
    suspend fun awaitFirstUnread(novelId: Long): NovelChapter? =
        chapterRepository.getByNovelId(novelId).firstOrNull { !it.read }

    /**
     * The group's chapters as one cross-source list, the same one the details "All" view shows, plus
     * what counts as read on another source. An unmerged novel gets its own list in source order.
     */
    suspend fun groupChapters(novelId: Long): NovelGroupChapters {
        val memberIds = mergeManager.computeRelatedIds(novelId).toList()
        if (memberIds.size <= 1) {
            return NovelGroupChapters(chapterRepository.getByNovelId(novelId).sortedBy { it.sourceOrder })
        }
        val byNovel = memberIds.associateWith { chapterRepository.getByNovelId(it) }
        val sourceIdByNovel = memberIds.associateWith { novelRepository.getById(it)?.source.orEmpty() }
        val unified = NovelChapterAggregation.aggregate(
            byNovel,
            sourceIdByNovel,
            libraryPreferences.preferredNovelSources.get(),
            mergeManager.overrideRankingMemberIds(novelId),
        )
            // The stitched position, which is the cross-source reading order. A chapter number is not:
            // it is whatever its own source counted, so two sources of one novel disagree.
            .sortedBy { it.sourceOrder }
        return NovelGroupChapters(unified, NovelChapterAggregation.readInOtherSources(byNovel, unified))
    }

    /** The group's first unread chapter, skipping what another of its sources has already read. */
    suspend fun awaitFirstUnreadInGroup(novelId: Long): NovelChapter? {
        val group = groupChapters(novelId)
        return group.chapters.firstOrNull { !it.read && it.id !in group.readInOtherSources }
    }
}
