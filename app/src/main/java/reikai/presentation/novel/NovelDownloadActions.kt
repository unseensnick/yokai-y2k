package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import reikai.domain.novel.model.NovelChapter

/**
 * Resolve a toolbar or library [DownloadAction] to the novel chapters it should enqueue, shared by the
 * novel library's multi-select and the details toolbar. Already-downloaded and already-queued chapters
 * are excluded BEFORE NEXT_N's take(N), as on manga: otherwise, once the first N are downloaded,
 * take(N) keeps returning them and repeated NEXT_N never advances. [excludedChapterIds] is that union,
 * resolved by the caller. The two "in other sources" sets carry a merge group's read and bookmark
 * state, so the next unread is the group's.
 */
fun selectChaptersForDownloadAction(
    chapters: List<NovelChapter>,
    action: DownloadAction,
    excludedChapterIds: Set<Long>,
    readInOtherSources: Set<Long>,
    bookmarkedInOtherSources: Set<Long>,
): List<NovelChapter> {
    val sorted = chapters.sortedBy { it.sourceOrder }
    val unread = sorted.filterNot { it.read || it.id in readInOtherSources || it.id in excludedChapterIds }
    return when (action) {
        DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
        DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
        DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
        DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
        DownloadAction.UNREAD_CHAPTERS -> unread
        DownloadAction.BOOKMARKED_CHAPTERS -> sorted.filter {
            (it.bookmark || it.id in bookmarkedInOtherSources) && it.id !in excludedChapterIds
        }
    }
}
