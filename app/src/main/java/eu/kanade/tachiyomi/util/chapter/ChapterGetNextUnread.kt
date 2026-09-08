package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<Chapter>.getNextUnread(
    manga: Manga,
    downloadManager: DownloadManager,
    // RK: chapters whose own row is unread but which another grouped source has read. Skipped, so
    // resuming a merged series does not reopen something the library already counts as read.
    readInOtherSources: Set<Long> = emptySet(),
    // RK: each chapter's own manga, so the downloaded filter probes the source that copy came from.
    mangaById: Map<Long, Manga> = emptyMap(),
): Chapter? {
    return applyFilters(manga, downloadManager) { mangaById[it.mangaId] ?: manga }.let { chapters ->
        if (manga.sortDescending()) {
            chapters.findLast { !it.read && it.id !in readInOtherSources }
        } else {
            chapters.find { !it.read && it.id !in readInOtherSources }
        }
    }
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    return applyFilters(manga).let { chapters ->
        if (manga.sortDescending()) {
            chapters.findLast { !it.isRead }
        } else {
            chapters.find { !it.isRead }
        }
    }?.chapter
}
