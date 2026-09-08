package reikai.domain.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import reikai.domain.merge.MergedGroupStitcher
import reikai.domain.merge.storedUnitsOf

/**
 * The novel half of keeping a merge group's stored stitch current. Twin of
 * [reikai.domain.manga.MangaGroupStitcher], pinned to it by the shared [MergedGroupStitcher] contract
 * and the one reconciliation that drives both; novels have no scanlator or gallery notion, so this
 * side only differs in where the chapters and the source ranking come from.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class NovelGroupStitcher(
    private val mergeGroupRepository: MergeGroupRepository,
    private val novelRepository: NovelRepository,
    private val chapterRepository: NovelChapterRepository,
    private val mergeManager: NovelMergeManager,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) : MergedGroupStitcher {

    override val contentType = ContentType.NOVELS

    override suspend fun stitch(groupId: Long): List<StoredUnit> {
        val members = mergeGroupRepository.getFavoriteMembers(contentType, groupId)
        if (members.isEmpty()) return emptyList()
        val chaptersByNovel = members.associateWith { chapterRepository.getByNovelId(it) }
        val sourceIdByNovel = members.associateWith { novelRepository.getById(it)?.source.orEmpty() }
        // Any member resolves the group for its per-group ranking override (empty = none).
        val memberRanking = mergeManager.overrideRankingMemberIds(members.first())
        val merged = NovelChapterAggregation.merge(
            chaptersByNovel,
            sourceIdByNovel,
            reikaiLibraryPreferences.preferredNovelSources.get(),
            memberRanking,
        )
        return storedUnitsOf(
            chapters = chaptersByNovel.values.flatten(),
            merged = merged,
            id = { it.id },
            name = { it.name },
            number = { it.chapterNumber },
        )
    }
}
