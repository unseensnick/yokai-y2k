package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.novel.download.NovelDownloadManager

/**
 * Keep the last N read chapters downloaded (the `removeAfterReadSlots` buffer): delete the chapter
 * [slots] positions back in reading order, so reading forward keeps a rolling window on disk. The
 * novel twin of manga's `ReaderViewModel.deleteChapterIfNeeded`, and the reading-position sibling of
 * [DeleteNovelChaptersAfterRead], which fires on the mark itself instead.
 *
 * Both readers call this, so the buffer cannot end up meaning two different things.
 */
@Inject
class DeleteNovelChaptersBehindReader(
    private val novelPreferences: NovelPreferences,
    private val getNovelCategories: GetNovelCategories,
    // Deferred for the same reason as in [DeleteNovelChaptersAfterRead]: building the manager resumes
    // the persisted download queue, and a reader open must not do that.
    private val downloadManager: () -> NovelDownloadManager,
    private val chapterRepository: NovelChapterRepository,
) {

    /** [orderedIds] is the session's reading order, so the buffer counts positions the user actually
     *  moves through rather than raw chapter numbers. */
    suspend fun await(novelId: Long, orderedIds: List<Long>, readChapterId: Long) {
        val slots = novelPreferences.removeAfterReadSlots().get()
        if (slots < 0) return
        val index = orderedIds.indexOf(readChapterId)
        if (index < 0) return
        val targetId = orderedIds.getOrNull(index - slots) ?: return
        val target = chapterRepository.getById(targetId) ?: return
        if (!target.read) return
        if (target.bookmark && !novelPreferences.removeBookmarkedChapters().get()) return
        val excluded = novelPreferences.removeExcludeCategories().get().mapNotNull { it.toLongOrNull() }
        if (excluded.isNotEmpty()) {
            val cats = getNovelCategories.awaitByNovelId(novelId).map { it.id }.ifEmpty { listOf(0L) }
            if (cats.intersect(excluded.toSet()).isNotEmpty()) return
        }
        downloadManager().deleteChapters(listOf(target))
    }
}
