package reikai.data.merge

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergedChapterUnitRepositoryImpl(
    private val database: Database,
) : MergedChapterUnitRepository {

    private val queries = database.merged_chapter_unitQueries

    override suspend fun getStaleGroups(contentType: ContentType): List<Long> =
        when (contentType) {
            ContentType.NOVELS -> queries.staleMergedNovelGroups().awaitAsList()
            else -> queries.staleMergedGroups().awaitAsList()
        }

    override suspend fun getStitch(contentType: ContentType, groupId: Long): List<ChapterUnit> =
        when (contentType) {
            ContentType.NOVELS -> queries.stitchOfNovelGroup(groupId) { chapterId, unit, copyOrder ->
                ChapterUnit(chapterId, unit!!.toInt(), copyOrder.toInt())
            }
            else -> queries.stitchOfGroup(groupId) { chapterId, unit, copyOrder ->
                ChapterUnit(chapterId, unit!!.toInt(), copyOrder.toInt())
            }
        }.awaitAsList()

    override suspend fun getUnreadCounts(contentType: ContentType): Map<Long, Long> =
        when (contentType) {
            ContentType.NOVELS ->
                queries.unreadCountsByGroupNovel().awaitAsList().associate { it.groupId to it.unreadCount }
            else ->
                queries.unreadCountsByGroup().awaitAsList().associate { it.groupId to it.unreadCount }
        }

    override suspend fun getCoveredChapterCounts(): Map<Long, Long> =
        queries.coveredChapterCountsByManga().awaitAsList().associate { it.mangaId to it.coveredCount }

    override suspend fun replaceGroup(contentType: ContentType, groupId: Long, units: List<StoredUnit>) {
        val novels = contentType == ContentType.NOVELS
        database.transaction {
            if (novels) queries.deleteNovelGroup(groupId) else queries.deleteGroup(groupId)
            units.forEach {
                val unit = it.unit?.toLong()
                val copyOrder = it.copyOrder.toLong()
                if (novels) {
                    queries.insertNovel(it.chapterId, groupId, unit, copyOrder, it.chapterName, it.chapterNumber)
                } else {
                    queries.insert(it.chapterId, groupId, unit, copyOrder, it.chapterNumber)
                }
            }
        }
    }
}
