package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.flow.Flow

/**
 * One row of the reader's chapter sheet, already resolved by the content type that owns the chapter.
 * Everything the row draws is here, so the sheet never asks what kind of entry it is showing.
 */
@Immutable
data class ReaderChapterRow(
    val id: Long,
    val title: String,
    /** The line under the title: a source name, a scanlator, both, or nothing. */
    val subtitle: String?,
    /** Epoch millis, 0 where the source dated nothing; formatted by the sheet so both types read alike. */
    val dateUpload: Long,
    /** How far into an unread chapter the reader got, already worded ("42%", "Page 3"), or null. */
    val readProgress: String?,
    val read: Boolean,
    val bookmark: Boolean,
    /** Queue state where the chapter is queued, else whether it is on disk. */
    val downloadState: Download.State,
    /** Live percent for the spinner; 0 where the type reports no per-chapter progress. */
    val downloadProgress: Int,
)

/**
 * The chapter list as a capability, so the sheet is one component over both content types. The rows are
 * cold: they are built while the sheet is open and re-emit as downloads move, rather than being held
 * for a session that may never open it.
 */
@Stable
interface ReaderChapterList {

    val rows: Flow<List<ReaderChapterRow>>

    /** The row the reader is on, which is where the sheet opens scrolled to. */
    val currentChapterId: Flow<Long>

    fun open(chapterId: Long)

    fun setRead(chapterId: Long, read: Boolean)

    fun setBookmark(chapterId: Long, bookmarked: Boolean)

    fun download(chapterId: Long, action: ChapterDownloadAction)
}
