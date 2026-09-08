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

    /** A chapter with the identity it was placed under. Held rather than recomputed: [keyOf]
     *  normalizes a chapter title, and the search below would otherwise redo that for every placed
     *  chapter on every lookup, which is quadratic in the group's size. */
    private class Placed<T>(val key: Any?, val item: T)

    private val order = mutableListOf<Placed<T>>()

    /** Every identity [order] holds, so a chapter no earlier source had is answered without a scan. */
    private val placedKeys = HashSet<Any>()

    /** Where the last chapter this source contributed or matched sits. */
    private var cursor = -1

    /** Chapters this source offered that carry no identity, waiting on the next one that does. */
    private val deferred = mutableListOf<T>()

    /** Each source's own copy of a chapter another source already placed, paired with that chapter.
     *  The merged list shows one of them; everything that has to count the group once needs the rest. */
    private val copies = mutableListOf<Pair<T, T>>()

    /** Call before walking each source, so it starts placing from the top again. */
    fun startSource() {
        placeDeferred()
        cursor = -1
    }

    /** Where an already-placed chapter shares [item]'s identity, or -1 when none does. */
    fun positionOf(item: T): Int {
        val key = keyOf(item) ?: return -1
        if (key !in placedKeys) return -1
        return order.indexOfFirst { it.key == key }
    }

    /**
     * [item] is this source's copy of the chapter already sitting at [index], which [positionOf]
     * found, so the walk continues from there. That closes any run of unidentifiable chapters since
     * the last one: when this source offered exactly as many as the order already holds between the
     * two, they are the same chapters seen twice, so they become copies too. Any other count means
     * the runs do not correspond and every one is kept.
     */
    fun followTo(index: Int, item: T) {
        val between = index - cursor - 1
        if (deferred.size == between) {
            deferred.forEachIndexed { offset, held -> copies += held to order[cursor + 1 + offset].item }
            deferred.clear()
            cursor = index
        } else {
            // Placing shifts the match down by however many landed above it.
            val added = deferred.size
            placeDeferred()
            cursor = index + added
        }
        copies += item to order[cursor].item
    }

    /** A chapter with no identity of its own. Where it belongs is only knowable once the next
     *  identifiable one arrives, so the decision waits for [followTo]. */
    fun defer(item: T) {
        deferred.add(item)
    }

    fun place(item: T) {
        cursor += 1
        val key = keyOf(item)
        order.add(cursor, Placed(key, item))
        if (key != null) placedKeys.add(key)
    }

    fun result(): Stitched<T> {
        placeDeferred()
        return Stitched(order.map { it.item }, copies)
    }

    /** A run with no closing chapter cannot be aligned against anything, so it is kept. */
    private fun placeDeferred() {
        deferred.forEach(::place)
        deferred.clear()
    }

    /**
     * The stitch's full answer. [merged] is the reading order, one chapter per row. [copies] pairs
     * each other source's copy of one of those chapters with the chapter itself; a chapter the stitch
     * dropped outright appears in neither, so a caller holding the input can tell the two apart.
     */
    class Stitched<T>(val merged: List<T>, val copies: List<Pair<T, T>>) {

        /**
         * Which merged chapter each input chapter belongs to, the shown chapter first and the other
         * sources' copies after it in the order they were walked, which is trunk order. A chapter the
         * stitch dropped is absent. One assembly for both content types, since only reading an id off
         * a chapter is per type.
         */
        fun units(id: (T) -> Long): List<ChapterUnit> {
            val positions = merged.withIndex().associate { (position, item) -> id(item) to position }
            val shown = merged.mapIndexed { position, item -> ChapterUnit(id(item), position, 0) }
            val copyOrders = HashMap<Int, Int>()
            return shown + copies.mapNotNull { (copy, of) ->
                positions[id(of)]?.let { unit ->
                    val order = (copyOrders[unit] ?: 0) + 1
                    copyOrders[unit] = order
                    ChapterUnit(id(copy), unit, order)
                }
            }
        }
    }
}
