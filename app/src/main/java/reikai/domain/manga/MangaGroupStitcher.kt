package reikai.domain.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import reikai.domain.merge.MergedGroupStitcher
import reikai.domain.merge.storedUnitsOf
import tachiyomi.domain.manga.interactor.GetMangaWithChapters

/**
 * The manga half of keeping a merge group's stored stitch current. Chapters are loaded WITHOUT the
 * scanlator filter: an excluded scanlator is a display choice, and the stored copy ranking lets a
 * reader skip its rows and fall to the next source, so excluding one needs no restitch.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class MangaGroupStitcher(
    private val mergeGroupRepository: MergeGroupRepository,
    private val getMangaWithChapters: GetMangaWithChapters,
    private val mergedChapterProvider: MergedChapterProvider,
) : MergedGroupStitcher {

    override val contentType = ContentType.MANGA

    override suspend fun stitch(groupId: Long): List<StoredUnit> {
        val members = mergeGroupRepository.getFavoriteMembers(contentType, groupId)
        if (members.isEmpty()) return emptyList()
        val chaptersBySource = members.associateWith { getMangaWithChapters.awaitChapters(it) }
        val sourceIdByManga = members.associateWith { getMangaWithChapters.awaitManga(it).source }
        val merged = mergedChapterProvider.merge(chaptersBySource, sourceIdByManga)
        return storedUnitsOf(
            chapters = chaptersBySource.values.flatten(),
            merged = merged,
            id = { it.id },
            name = { it.name },
            number = { it.chapterNumber },
        )
    }
}
