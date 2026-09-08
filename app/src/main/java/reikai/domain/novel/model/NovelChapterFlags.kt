package reikai.domain.novel.model

import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.util.lang.compareToWithCollator

/**
 * Per-novel chapter sort / filter / display settings, packed into [Novel.chapterFlags]. Self-contained
 * (its own constants rather than borrowing the manga `Manga.CHAPTER_*` layout) because the novel
 * filter/sort sheet is net-new and nothing shares the encoding. A novel either uses its own local
 * settings or falls back to the global defaults in [NovelPreferences], picked by the [SORT_LOCAL] /
 * [FILTER_LOCAL] bits. [Novel] is immutable, so mutation goes through [setFlag].
 */
object NovelChapterFlags {
    // Sort direction (bit 0). DESC = bit clear (newest/highest first), matching the manga default.
    const val SORT_DESC = 0x00000000L
    const val SORT_ASC = 0x00000001L
    const val SORT_DIR_MASK = 0x00000001L

    // Unread filter (bits 1-2). 0 = show all.
    const val SHOW_UNREAD = 0x00000002L
    const val SHOW_READ = 0x00000004L
    const val READ_MASK = 0x00000006L

    // Bookmarked filter (bits 3-4). 0 = show all.
    const val SHOW_BOOKMARKED = 0x00000008L
    const val SHOW_NOT_BOOKMARKED = 0x00000010L
    const val BOOKMARKED_MASK = 0x00000018L

    // Downloaded filter (bits 5-6). 0 = show all.
    const val SHOW_DOWNLOADED = 0x00000020L
    const val SHOW_NOT_DOWNLOADED = 0x00000040L
    const val DOWNLOADED_MASK = 0x00000060L

    // Sort method (bits 8-9). The values match the manga side's Manga.CHAPTER_SORTING_* bit for bit,
    // so the two flag layouts can be read against each other without a translation table.
    const val SORTING_SOURCE = 0x00000000L
    const val SORTING_NUMBER = 0x00000100L
    const val SORTING_UPLOAD_DATE = 0x00000200L
    const val SORTING_ALPHABET = 0x00000300L
    const val SORTING_MASK = 0x00000300L

    // Display (bit 20): source title vs "Chapter N".
    const val DISPLAY_NAME = 0x00000000L
    const val DISPLAY_NUMBER = 0x00100000L
    const val DISPLAY_MASK = 0x00100000L

    // Local-override bits: when set, the novel uses its own sort / filter instead of the global default.
    const val SORT_LOCAL = 0x01000000L
    const val SORT_LOCAL_MASK = 0x01000000L
    const val FILTER_LOCAL = 0x02000000L
    const val FILTER_LOCAL_MASK = 0x02000000L
}

val Novel.sorting: Long get() = chapterFlags and NovelChapterFlags.SORTING_MASK
val Novel.sortDescending: Boolean
    get() = chapterFlags and NovelChapterFlags.SORT_DIR_MASK == NovelChapterFlags.SORT_DESC
val Novel.readFilter: Long get() = chapterFlags and NovelChapterFlags.READ_MASK
val Novel.bookmarkedFilter: Long get() = chapterFlags and NovelChapterFlags.BOOKMARKED_MASK
val Novel.downloadedFilter: Long get() = chapterFlags and NovelChapterFlags.DOWNLOADED_MASK
val Novel.hideChapterTitles: Boolean
    get() = chapterFlags and NovelChapterFlags.DISPLAY_MASK == NovelChapterFlags.DISPLAY_NUMBER
val Novel.usesLocalSort: Boolean
    get() = chapterFlags and NovelChapterFlags.SORT_LOCAL_MASK == NovelChapterFlags.SORT_LOCAL
val Novel.usesLocalFilter: Boolean
    get() = chapterFlags and NovelChapterFlags.FILTER_LOCAL_MASK == NovelChapterFlags.FILTER_LOCAL

// Effective values: the novel's own setting when its local bit is set, else the global default.
fun Novel.effectiveSorting(prefs: NovelPreferences): Long =
    if (usesLocalSort) sorting else prefs.defaultChapterSortOrder().get()
fun Novel.effectiveSortDescending(prefs: NovelPreferences): Boolean =
    if (usesLocalSort) sortDescending else prefs.defaultChapterSortDescending().get()
fun Novel.effectiveReadFilter(prefs: NovelPreferences): Long =
    if (usesLocalFilter) readFilter else prefs.defaultChapterFilterUnread().get()
fun Novel.effectiveBookmarkedFilter(prefs: NovelPreferences): Long =
    if (usesLocalFilter) bookmarkedFilter else prefs.defaultChapterFilterBookmarked().get()
fun Novel.effectiveDownloadedFilter(prefs: NovelPreferences): Long =
    if (usesLocalFilter) downloadedFilter else prefs.defaultChapterFilterDownloaded().get()
fun Novel.effectiveHideChapterTitles(prefs: NovelPreferences): Boolean =
    if (usesLocalSort) hideChapterTitles else prefs.defaultChapterHideTitles().get()

/** Replace the [mask] bits of [flags] with [flag]. */
fun setNovelFlag(flags: Long, flag: Long, mask: Long): Long = (flags and mask.inv()) or (flag and mask)

/**
 * The novel's chosen chapter order, as a comparator. Separate from [sortedAndFiltered] because the
 * reader needs the order without the display filters and always ascending: reading runs first to last
 * whichever way the chapter list happens to be shown, which is the same call the manga reader makes
 * with `getChapterSort(manga, sortDescending = false)`.
 *
 * Source order breaks every other key's ties, and for a merged group it is the stitched position.
 */
fun readingOrderComparator(novel: Novel, prefs: NovelPreferences): Comparator<NovelChapter> =
    when (novel.effectiveSorting(prefs)) {
        NovelChapterFlags.SORTING_NUMBER -> compareBy<NovelChapter> { it.chapterNumber }.thenBy { it.sourceOrder }
        NovelChapterFlags.SORTING_UPLOAD_DATE -> compareBy<NovelChapter> { it.dateUpload }.thenBy { it.sourceOrder }
        NovelChapterFlags.SORTING_ALPHABET ->
            Comparator<NovelChapter> { a, b -> a.name.compareToWithCollator(b.name) }.thenBy { it.sourceOrder }
        else -> compareBy { it.sourceOrder }
    }

/**
 * Sort + filter a chapter list for display, using the novel's effective settings. [downloadedChapterIds]
 * carries the disk-download membership (from NovelDownloadCache) so the downloaded filter has no DB flag.
 */
fun List<NovelChapter>.sortedAndFiltered(
    novel: Novel,
    prefs: NovelPreferences,
    downloadedChapterIds: Set<Long>,
): List<NovelChapter> {
    val read = novel.effectiveReadFilter(prefs)
    val bookmarked = novel.effectiveBookmarkedFilter(prefs)
    val downloaded = novel.effectiveDownloadedFilter(prefs)
    val filtered = filter { ch ->
        val readOk = when (read) {
            NovelChapterFlags.SHOW_UNREAD -> !ch.read
            NovelChapterFlags.SHOW_READ -> ch.read
            else -> true
        }
        val bookmarkOk = when (bookmarked) {
            NovelChapterFlags.SHOW_BOOKMARKED -> ch.bookmark
            NovelChapterFlags.SHOW_NOT_BOOKMARKED -> !ch.bookmark
            else -> true
        }
        val downloadOk = when (downloaded) {
            NovelChapterFlags.SHOW_DOWNLOADED -> ch.id in downloadedChapterIds
            NovelChapterFlags.SHOW_NOT_DOWNLOADED -> ch.id !in downloadedChapterIds
            else -> true
        }
        readOk && bookmarkOk && downloadOk
    }
    val sorted = filtered.sortedWith(readingOrderComparator(novel, prefs))
    return if (novel.effectiveSortDescending(prefs)) sorted.reversed() else sorted
}
