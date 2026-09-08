package reikai.domain.merge

import kotlin.math.floor

/**
 * How many chapters are missing between two neighbouring rows of a chapter list, or 0 when that
 * cannot be known. One rule for both content types.
 *
 * Subtracting two chapter numbers only answers the question when both numbers mean the same thing.
 * Two guards decide when they do not, and both err towards showing nothing: a marker that guesses is
 * worse than no marker.
 */
object ChapterGap {

    /** One side of a gap. [ownerId] is the library row the chapter belongs to, which for a grouped
     *  entry differs between sources. */
    data class Neighbour(val number: Double, val name: String, val ownerId: Long)

    fun between(higher: Neighbour?, lower: Neighbour?): Int {
        if (higher == null || !numberIsTrustworthy(higher)) return 0
        // The list edge: everything below the last chapter's number is missing.
        if (lower == null) return floor(higher.number).toInt().minus(1).coerceAtLeast(0)
        if (!numberIsTrustworthy(lower)) return 0
        // Two sources of one entry count differently, so the difference measures nothing.
        if (higher.ownerId != lower.ownerId) return 0
        if (higher.number < 0.0 || lower.number < 0.0) return 0
        // Never negative: a pair the list order puts the wrong way round is missing nothing, not a
        // negative something, and every caller only asks whether the answer is above zero.
        return (floor(higher.number).toInt() - floor(lower.number).toInt() - 1).coerceAtLeast(0)
    }

    /**
     * Every gap the list would mark, added up, for the header that summarises them. Shares [between]
     * so the total and the inline markers cannot disagree. [ordered] is the list as displayed.
     */
    fun total(ordered: List<Neighbour>, descending: Boolean): Int =
        ordered.zipWithNext { first, second ->
            if (descending) between(first, second) else between(second, first)
        }.sum()

    /**
     * Whether the recognized number can be believed, which it can only be when the name labels the
     * chapter with a plain number. A volume extra or epilogue has no chapter number of its own, but
     * its name still hands the recognizer a digit: "Chapter v11ex2: Vol 11 Extra 2" reads as chapter
     * 2, which under chapter 483 claimed 480 were missing.
     */
    private fun numberIsTrustworthy(neighbour: Neighbour): Boolean {
        val tokens = neighbour.name.lowercase().split(nonAlphanumeric).filter { it.isNotEmpty() }
        val first = tokens.firstOrNull() ?: return false
        val candidate = if (first in labelWords) tokens.getOrNull(1) else first
        return candidate != null && plainNumber.matches(candidate)
    }

    private val labelWords = setOf("chapter", "ch", "chap", "episode", "ep")
    private val plainNumber = Regex("""^[0-9]+(\.[0-9]+)?$""")
    private val nonAlphanumeric = Regex("""[^a-z0-9]+""")
}
