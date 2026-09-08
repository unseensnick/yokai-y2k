package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.download.DownloadCache
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal

/**
 * Returns a copy of the list with not downloaded chapters removed.
 */
// RK: [mangaFor] resolves each chapter's OWN manga. A merged series' list spans several sources and a
// chapter is stored under the source it came from, so probing them all against one manga's folder
// reported every sibling's chapter as missing.
fun List<Chapter>.filterDownloaded(
    downloadCache: DownloadCache,
    mangaFor: (Chapter) -> Manga,
): List<Chapter> = filter { chapter ->
    val manga = mangaFor(chapter)
    manga.isLocal() ||
        downloadCache.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source)
}
