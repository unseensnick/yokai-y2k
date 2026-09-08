package eu.kanade.domain.chapter.model

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.source.local.isLocal

/**
 * Applies the view filters to the list of chapters obtained from the database.
 * @return an observable of the list of chapters filtered and sorted.
 */
// RK: [mangaFor] resolves each chapter's OWN manga. A merged series' list spans several sources and a
// chapter is stored under the source it came from, so probing them all against the opened manga's
// folder reported every sibling's chapter as not downloaded.
fun List<Chapter>.applyFilters(
    manga: Manga,
    downloadManager: DownloadManager,
    mangaFor: (Chapter) -> Manga = { manga },
): List<Chapter> {
    val unreadFilter = manga.unreadFilter
    val downloadedFilter = manga.downloadedFilter
    val bookmarkedFilter = manga.bookmarkedFilter

    return filter { chapter -> applyFilter(unreadFilter) { !chapter.read } }
        .filter { chapter -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
        .filter { chapter ->
            applyFilter(downloadedFilter) {
                val owner = mangaFor(chapter)
                owner.isLocal() || downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    owner.title,
                    owner.source,
                )
            }
        }
        .sortedWith(getChapterSort(manga))
}

/**
 * Applies the view filters to the list of chapters obtained from the database.
 * @return an observable of the list of chapters filtered and sorted.
 */
fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
    val isLocalManga = manga.isLocal()
    val unreadFilter = manga.unreadFilter
    val downloadedFilter = manga.downloadedFilter
    val bookmarkedFilter = manga.bookmarkedFilter
    return asSequence()
        // RK: the item's any-source flags, so this agrees with the details list it filters.
        .filter { applyFilter(unreadFilter) { !it.isRead } }
        .filter { applyFilter(bookmarkedFilter) { it.isBookmarked } }
        .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
        .sortedWith { (chapter1), (chapter2) -> getChapterSort(manga).invoke(chapter1, chapter2) }
}
