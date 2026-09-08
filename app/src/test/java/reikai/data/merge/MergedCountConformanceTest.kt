package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.manga.ChapterAggregation
import reikai.domain.merge.ChapterMatchKeys
import reikai.domain.merge.crossSourceReadIds
import reikai.domain.merge.storedUnitsOf
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.model.NovelChapter
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.chapter.model.Chapter

/**
 * The library badge and the merged chapter list must report the same chapters. Each case stitches one
 * fixture twice, once rendered as a list and once stored and counted the way the library reads it,
 * and asserts the two agree. The fixture is built once and used by both halves, so they cannot drift
 * apart in the test either. This is what stops a second definition of cross-source chapter identity
 * being written again; the history is in docs/dev/plans/merged-read-state.md.
 */
class MergedCountConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var groups: MergeGroupRepositoryImpl
    private lateinit var units: MergedChapterUnitRepositoryImpl

    /** One chapter, as both halves see it. [owner] is the member entry it belongs to. */
    private data class Row(
        val id: Long,
        val owner: Long,
        val name: String,
        val number: Double,
        val read: Boolean = false,
    )

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = Database(
                driver = driver,
                historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                novelsAdapter = Novels.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
                custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
                custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
            )
            groups = MergeGroupRepositoryImpl(database)
            units = MergedChapterUnitRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    @DisplayName("novels: a title both sources carry is one chapter to the badge and to the list")
    fun novelTitleMatchAgrees() = runTest {
        val rows = listOf(
            Row(id = 10, owner = 1, name = "Chapter 1: Ever His Humble Servant", number = 1.0),
            Row(id = 20, owner = 2, name = "Chapter 8: Ever His Humble Servant", number = 8.0),
        )

        novelBadgeCount(rows) shouldBe novelListCount(rows)
    }

    @Test
    @DisplayName("novels: chapters titled only with numbers count once, not once per source")
    fun novelWordlessTitleAgrees() = runTest {
        // The shape a two-source group takes when one site titles chapters plainly and the other
        // prefixes its own index: between two shared titles both carry the same two chapters, and
        // neither copy's name holds a word to match on.
        val rows = listOf(
            Row(id = 10, owner = 1, name = "Chapter 100: Anchor One", number = 100.0),
            Row(id = 11, owner = 1, name = "Chapter 101", number = 101.0),
            Row(id = 12, owner = 1, name = "Chapter 102", number = 102.0),
            Row(id = 13, owner = 1, name = "Chapter 103: Anchor Two", number = 103.0),
            Row(id = 20, owner = 2, name = "Chapter 200: Anchor One", number = 200.0),
            Row(id = 21, owner = 2, name = "Chapter 201 - 101", number = 201.0),
            Row(id = 22, owner = 2, name = "Chapter 202 - 102", number = 202.0),
            Row(id = 23, owner = 2, name = "Chapter 203: Anchor Two", number = 203.0),
        )

        novelBadgeCount(rows) shouldBe novelListCount(rows)
    }

    @Test
    @DisplayName("novels: a chapter read on one source is not unread on the group")
    fun novelReadOnOneSourceAgrees() = runTest {
        val rows = listOf(
            Row(id = 10, owner = 1, name = "Chapter 1: Ever His Humble Servant", number = 1.0),
            Row(id = 20, owner = 2, name = "Chapter 8: Ever His Humble Servant", number = 8.0, read = true),
        )

        novelBadgeCount(rows) shouldBe novelListCount(rows)
    }

    @Test
    @DisplayName("a stitched group that is fully read reports zero, not nothing")
    fun fullyReadGroupReportsZero() = runTest {
        // The distinction the library depends on: absent means NOT STITCHED YET, so the row falls back
        // to its leading source's own count. Reading absent as zero badged a freshly stitched library
        // as finished until something else made the list rebuild.
        val rows = listOf(
            Row(id = 10, owner = 1, name = "Chapter 1: Anchor", number = 1.0, read = true),
            Row(id = 20, owner = 2, name = "Chapter 8: Anchor", number = 8.0, read = true),
        )

        novelBadgeCount(rows) shouldBe 0
        units.getUnreadCounts(ContentType.NOVELS).size shouldBe 1
    }

    @Test
    @DisplayName("a group nothing has stitched is absent, so a caller can tell it apart")
    fun unstitchedGroupIsAbsent() = runTest {
        insertNovel(1)
        insertNovel(2)
        groups.createGroup(ContentType.NOVELS, listOf(1, 2))!!
        insertNovelChapter(Row(id = 10, owner = 1, name = "Chapter 1: Anchor", number = 1.0))

        units.getUnreadCounts(ContentType.NOVELS).isEmpty() shouldBe true
    }

    @Test
    @DisplayName("manga: a chapter both sources carry is one chapter to the badge and to the list")
    fun mangaNumberMatchAgrees() = runTest {
        val rows = listOf(
            Row(id = 10, owner = 1, name = "Chapter 1", number = 1.0),
            Row(id = 20, owner = 2, name = "Chapter 1", number = 1.0),
        )

        mangaBadgeCount(rows) shouldBe mangaListCount(rows)
    }

    @Test
    @DisplayName("manga: a sibling's unnumbered chapter is counted the way the list treats it")
    fun mangaUnrecognizedSiblingAgrees() = runTest {
        // Only the leading source's unnumbered chapters reach the list, since nothing can place a
        // sibling's. The badge has to agree about that rather than counting it on its own.
        val rows = listOf(
            Row(id = 10, owner = 1, name = "Chapter 1", number = 1.0),
            Row(id = 11, owner = 1, name = "Chapter 2", number = 2.0),
            Row(id = 20, owner = 2, name = "Chapter 1", number = 1.0),
            Row(id = 21, owner = 2, name = "Extras", number = -1.0),
        )

        mangaBadgeCount(rows) shouldBe mangaListCount(rows)
    }

    @Test
    @DisplayName("manga: one source's scanlator variants are one chapter to both")
    fun mangaScanlatorVariantsAgree() = runTest {
        val rows = listOf(
            Row(id = 10, owner = 1, name = "Chapter 1", number = 1.0),
            Row(id = 11, owner = 1, name = "Chapter 1", number = 1.0),
            Row(id = 20, owner = 2, name = "Chapter 1", number = 1.0),
        )

        mangaBadgeCount(rows) shouldBe mangaListCount(rows)
    }

    // The list's answer: one row per chapter the merged list shows, dropping those a member source
    // has already read, which is what the badge claims to count.

    private fun novelListCount(rows: List<Row>): Int {
        val byNovel = rows.groupBy({ it.owner }, { it.toNovelChapter() })
        val unified = NovelChapterAggregation.aggregate(byNovel)
        val readElsewhere = NovelChapterAggregation.readInOtherSources(byNovel, unified)
        return unified.count { !it.read && it.id !in readElsewhere }
    }

    private fun mangaListCount(rows: List<Row>): Int {
        val bySource = rows.groupBy({ it.owner }, { it.toChapter() })
        val unified = ChapterAggregation.aggregate(bySource)
        val readElsewhere = crossSourceReadIds(
            bySource = bySource,
            unified = unified,
            id = { it.id },
            read = { it.read },
            key = { ChapterMatchKeys.manga(it.chapterNumber, isGallerySource = false) },
        )
        return unified.count { !it.read && it.id !in readElsewhere }
    }

    // The badge's answer: the same stitch, stored the way reconciliation stores it, then counted.

    private suspend fun novelBadgeCount(rows: List<Row>): Int {
        val owners = rows.map { it.owner }.distinct()
        owners.forEach { insertNovel(it) }
        val group = groups.createGroup(ContentType.NOVELS, owners)!!
        rows.forEach { insertNovelChapter(it) }
        val chapters = rows.map { it.toNovelChapter() }
        val merged = NovelChapterAggregation.merge(chapters.groupBy { it.novelId })
        units.replaceGroup(
            ContentType.NOVELS,
            group,
            storedUnitsOf(chapters, merged, { it.id }, { it.name }, { it.chapterNumber }),
        )
        return units.getUnreadCounts(ContentType.NOVELS)[group]?.toInt() ?: 0
    }

    private suspend fun mangaBadgeCount(rows: List<Row>): Int {
        val owners = rows.map { it.owner }.distinct()
        owners.forEach { insertManga(it) }
        val group = groups.createGroup(ContentType.MANGA, owners)!!
        rows.forEach { insertChapter(it) }
        val chapters = rows.map { it.toChapter() }
        val merged = ChapterAggregation.merge(chapters.groupBy { it.mangaId })
        units.replaceGroup(
            ContentType.MANGA,
            group,
            storedUnitsOf(chapters, merged, { it.id }, { it.name }, { it.chapterNumber }),
        )
        return units.getUnreadCounts(ContentType.MANGA)[group]?.toInt() ?: 0
    }

    private fun Row.toNovelChapter() = NovelChapter(
        id = id,
        novelId = owner,
        url = "/$owner/$id",
        name = name,
        read = read,
        bookmark = false,
        lastTextProgress = 0,
        chapterNumber = number,
        sourceOrder = id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private fun Row.toChapter() = Chapter.create().copy(
        id = id,
        mangaId = owner,
        url = "/$owner/$id",
        name = name,
        read = read,
        chapterNumber = number,
        sourceOrder = id,
    )

    private suspend fun insertManga(id: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) " +
                "VALUES ($id, 1, 'm-url-$id', 'title', 0, 1, 0, 0, 0, 0, 0)",
            0,
        ).await()
    }

    private suspend fun insertNovel(id: Long) {
        driver.execute(
            null,
            "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                "VALUES ($id, 'src', 'n-url-$id', 'title', 0, 1, 0, 0)",
            0,
        ).await()
    }

    private suspend fun insertChapter(row: Row) {
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, " +
                "last_page_read, chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (${row.id}, ${row.owner}, '/${row.owner}/${row.id}', '${row.name}', NULL, " +
                "${if (row.read) 1 else 0}, 0, 0, ${row.number}, ${row.id}, 0, 0)",
            0,
        ).await()
    }

    private suspend fun insertNovelChapter(row: Row) {
        driver.execute(
            null,
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, " +
                "source_order, date_fetch, date_upload) " +
                "VALUES (${row.id}, ${row.owner}, '/${row.owner}/${row.id}', '${row.name}', " +
                "${if (row.read) 1 else 0}, 0, ${row.number}, ${row.id}, 0, 0)",
            0,
        ).await()
    }
}
