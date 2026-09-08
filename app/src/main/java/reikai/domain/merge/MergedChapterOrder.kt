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

    /** Chapters this source offered that carry no identity, waiting on the next one that does. */
    private val deferred = mutableListOf<T>()

    /** Call before walking each source, so it starts placing from the top again. */
    fun startSource() {
        placeDeferred()
        cursor = -1
    }

    /** Where an already-placed chapter shares [item]'s identity, or -1 when none does. */
    fun positionOf(item: T): Int {
        val key = keyOf(item) ?: return -1
        return order.indexOfFirst { keyOf(it) == key }
    }

    /**
     * This source's next chapter continues from [index], which [positionOf] found. That closes any
     * run of unidentifiable chapters since the last one: when this source offered exactly as many as
     * the order already holds between the two, they are the same chapters seen twice, so they go.
     * Any other count means the runs do not correspond and every one is kept.
     */
    fun followTo(index: Int) {
        val between = index - cursor - 1
        cursor = if (deferred.size == between) {
            deferred.clear()
            index
        } else {
            val added = deferred.size
            placeDeferred()
            index + added
        }
    }

    /** A chapter with no identity of its own. Where it belongs is only knowable once the next
     *  identifiable one arrives, so the decision waits for [followTo]. */
    fun defer(item: T) {
        deferred.add(item)
    }

    fun place(item: T) {
        cursor += 1
        order.add(cursor, item)
    }

    fun result(): List<T> {
        placeDeferred()
        return order
    }

    /** A run with no closing chapter cannot be aligned against anything, so it is kept. */
    private fun placeDeferred() {
        deferred.forEach(::place)
        deferred.clear()
    }
}
