package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The rolling download buffer behind the reader. Which chapter gets retired for a given slot count is
 * the whole rule: an off-by-one here deletes the chapter the reader is sitting on.
 */
class DeleteNovelChaptersBehindReaderTest {

    private val order = listOf(1L, 2L, 3L, 4L, 5L)

    private fun chapter(id: Long, read: Boolean = true, bookmark: Boolean = false) = NovelChapter(
        id = id,
        novelId = 7L,
        url = "c$id",
        name = "Chapter $id",
        read = read,
        bookmark = bookmark,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private fun subject(
        slots: Int,
        chapters: Map<Long, NovelChapter> = order.associateWith { chapter(it) },
    ): Pair<DeleteNovelChaptersBehindReader, NovelDownloadManager> {
        // Seeded rather than set(): InMemoryPreferenceStore holds an immutable map, so a set() never
        // reaches the next read.
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("novel_remove_after_read_slots", slots, -1),
            ),
        )
        val repo = mockk<NovelChapterRepository>()
        coEvery { repo.getById(any()) } answers { chapters[firstArg<Long>()] }
        val manager = mockk<NovelDownloadManager>(relaxed = true)
        val interactor = DeleteNovelChaptersBehindReader(
            novelPreferences = NovelPreferences(store),
            getNovelCategories = mockk(relaxed = true),
            downloadManager = { manager },
            chapterRepository = repo,
        )
        return interactor to manager
    }

    @Test
    fun `a buffer of one retires the chapter one position back`() = runTest {
        val (interactor, manager) = subject(slots = 1)
        val deleted = slot<List<NovelChapter>>()
        interactor.await(novelId = 7L, orderedIds = order, readChapterId = 4L)
        coVerify { manager.deleteChapters(capture(deleted)) }
        deleted.captured.single().id shouldBe 3L
    }

    @Test
    fun `a buffer of zero retires the chapter just read`() = runTest {
        val (interactor, manager) = subject(slots = 0)
        val deleted = slot<List<NovelChapter>>()
        interactor.await(novelId = 7L, orderedIds = order, readChapterId = 4L)
        coVerify { manager.deleteChapters(capture(deleted)) }
        deleted.captured.single().id shouldBe 4L
    }

    @Test
    fun `a negative slot count deletes nothing at all`() = runTest {
        val (interactor, manager) = subject(slots = -1)
        interactor.await(novelId = 7L, orderedIds = order, readChapterId = 4L)
        coVerify(exactly = 0) { manager.deleteChapters(any()) }
    }

    @Test
    fun `nothing is retired before the buffer has filled`() = runTest {
        val (interactor, manager) = subject(slots = 3)
        interactor.await(novelId = 7L, orderedIds = order, readChapterId = 2L)
        coVerify(exactly = 0) { manager.deleteChapters(any()) }
    }

    @Test
    fun `a chapter that is not read yet is left on disk`() = runTest {
        val chapters = order.associateWith { chapter(it, read = it != 3L) }
        val (interactor, manager) = subject(slots = 1, chapters = chapters)
        interactor.await(novelId = 7L, orderedIds = order, readChapterId = 4L)
        coVerify(exactly = 0) { manager.deleteChapters(any()) }
    }

    @Test
    fun `a bookmarked chapter is kept unless the user allowed removing them`() = runTest {
        val chapters = order.associateWith { chapter(it, bookmark = it == 3L) }
        val (interactor, manager) = subject(slots = 1, chapters = chapters)
        interactor.await(novelId = 7L, orderedIds = order, readChapterId = 4L)
        coVerify(exactly = 0) { manager.deleteChapters(any()) }
    }
}
