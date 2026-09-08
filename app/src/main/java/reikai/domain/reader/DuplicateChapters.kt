package reikai.domain.reader

/**
 * Drop same-numbered duplicate chapters WITHIN one entry, which a source produces by listing a chapter
 * twice or under several scanlators. Of each set the chapter being read wins, then one from the same
 * origin as it (a scanlator for manga, a source for novels), then the first. Dropping them rather than
 * stepping over them keeps the chapter sheet, download-ahead and delete-after-read counting the
 * chapters the reader will stop on. [ownerOf] keeps the pass inside one entry: across a merge group a
 * number identifies nothing, and the stitch has already decided what is one chapter there.
 */
fun <T> List<T>.removeDuplicateChapters(
    current: T,
    numberOf: (T) -> Double,
    idOf: (T) -> Long,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
): List<T> {
    val currentId = idOf(current)
    val currentOrigin = originOf(current)
    return groupBy { ownerOf(it) to numberOf(it) }.map { (_, chapters) ->
        chapters.find { idOf(it) == currentId }
            ?: chapters.find { originOf(it) == currentOrigin }
            ?: chapters.first()
    }
}
