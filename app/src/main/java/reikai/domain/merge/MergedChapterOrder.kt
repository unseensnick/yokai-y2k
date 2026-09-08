package reikai.domain.merge

/**
 * Places several sources' chapters into one reading order, Komikku's `dedupeByPriority` shape.
 *
 * Neither key a chapter carries is comparable across sources: its number is whatever that site
 * counted, often a position, and its source order indexes its own list. So the order is built as the
 * sources are stitched rather than by sorting the pooled result. Each source is walked in its own
 * order: a chapter an earlier one already placed moves the cursor to it, a new one lands after it.
 */
class MergedChapterOrder<T>(private val keyOf: (T) -> Any?) {

    private val order = mutableListOf<T>()

    /** Where the last chapter this source contributed or matched sits. */
    private var cursor = -1

    /** Call before walking each source, so it starts placing from the top again. */
    fun startSource() {
        cursor = -1
    }

    /** Where an already-placed chapter shares [item]'s identity, or -1 when none does. */
    fun positionOf(item: T): Int {
        val key = keyOf(item) ?: return -1
        return order.indexOfFirst { keyOf(it) == key }
    }

    /** This source's next chapter continues from [index], which [positionOf] found. */
    fun followTo(index: Int) {
        cursor = index
    }

    fun place(item: T) {
        cursor += 1
        order.add(cursor, item)
    }

    fun result(): List<T> = order
}
