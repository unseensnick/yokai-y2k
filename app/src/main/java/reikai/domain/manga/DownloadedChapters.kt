package reikai.domain.manga

import eu.kanade.tachiyomi.data.download.DownloadManager
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal

/**
 * Ids of [chapters] whose own file is on disk, each probed against the source that copy came from
 * rather than the screen's manga: a merged series' chapter is stored under its own source's folder,
 * so probing them all against one manga's reports every sibling's chapter as missing.
 *
 * Resolved as a set because a merged group asks this of the same chapter several times over, and each
 * probe builds names and two digests.
 */
fun DownloadManager.downloadedChapterIds(chapters: List<Chapter>, ownerOf: (Chapter) -> Manga): Set<Long> =
    chapters.asSequence()
        .filter { chapter ->
            val owner = ownerOf(chapter)
            owner.isLocal() ||
                isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, owner.title, owner.source)
        }
        .mapTo(HashSet()) { it.id }
