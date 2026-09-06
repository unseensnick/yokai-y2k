package reikai.presentation.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The reader's "view all chapters" sheet, both content types. Tap to jump, swipe to run the configured
 * chapter-swipe action, and start, cancel or delete a download from the row. Ported from Komikku for
 * manga and grown into the shared one when novels joined the same reader; Mihon has no such sheet.
 */
@Composable
fun ReaderChapterListDialog(
    onDismissRequest: () -> Unit,
    rows: List<ReaderChapterRow>,
    currentChapterId: Long,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onClickChapter: (Long) -> Unit,
    onMarkRead: (Long, Boolean) -> Unit,
    onBookmark: (Long, Boolean) -> Unit,
    onDownloadAction: (Long, ChapterDownloadAction) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        // The rows arrive asynchronously, and the list state below captures where to open scrolled to on
        // the composition that first builds it. Waiting here is what keeps that from being row zero.
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@AdaptiveSheet
        }
        val listState = rememberLazyListState(rows.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0))
        // Optimistic per-row overrides so a swipe lands immediately: every one of these writes is async,
        // and a deletion in particular is only visible once the cache catches up.
        val stateOverrides = remember { mutableStateMapOf<Long, Download.State>() }
        val readOverrides = remember { mutableStateMapOf<Long, Boolean>() }
        val bookmarkOverrides = remember { mutableStateMapOf<Long, Boolean>() }
        fun runDownloadAction(chapterId: Long, action: ChapterDownloadAction) {
            when (action) {
                ChapterDownloadAction.DELETE -> stateOverrides[chapterId] = Download.State.NOT_DOWNLOADED
                else -> stateOverrides.remove(chapterId)
            }
            onDownloadAction(chapterId, action)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(min = 200.dp, max = 500.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            items(items = rows, key = { "reader-chapter-${it.id}" }) { row ->
                // A row the queue is actually working on outranks an override, so a download started
                // elsewhere while the sheet is open is not masked by an earlier delete-swipe.
                val downloadState = if (row.downloadState.isQueued) {
                    row.downloadState
                } else {
                    stateOverrides[row.id] ?: row.downloadState
                }
                val read = readOverrides[row.id] ?: row.read
                val bookmark = bookmarkOverrides[row.id] ?: row.bookmark
                MangaChapterListItem(
                    title = row.title,
                    date = row.dateUpload.takeIf { it > 0L }?.let { relativeDateText(it) },
                    readProgress = row.readProgress.takeIf { !read },
                    scanlator = row.subtitle,
                    read = read,
                    bookmark = bookmark,
                    selected = false,
                    downloadIndicatorEnabled = true,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { row.downloadProgress },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {},
                    onClick = { onClickChapter(row.id) },
                    onDownloadClick = { action -> runDownloadAction(row.id, action) },
                    onChapterSwipe = { action ->
                        when (action) {
                            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                                readOverrides[row.id] = !read
                                onMarkRead(row.id, !read)
                            }
                            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                                bookmarkOverrides[row.id] = !bookmark
                                onBookmark(row.id, !bookmark)
                            }
                            LibraryPreferences.ChapterSwipeAction.Download ->
                                runDownloadAction(row.id, downloadState.toSwipeDownloadAction())
                            LibraryPreferences.ChapterSwipeAction.Disabled -> {}
                        }
                    },
                )
            }
        }
    }
}

/** Which download action a Download-configured swipe runs, given the row's state: start now when it is
 *  absent, cancel while it is queued or running, delete when it is on disk. Matches the details list. */
internal fun Download.State.toSwipeDownloadAction(): ChapterDownloadAction = when (this) {
    Download.State.ERROR, Download.State.NOT_DOWNLOADED -> ChapterDownloadAction.START_NOW
    Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
}

/** Whether the queue is holding this chapter, which is the state a row must not let an override mask. */
private val Download.State.isQueued: Boolean
    get() = this == Download.State.QUEUE || this == Download.State.DOWNLOADING || this == Download.State.ERROR
