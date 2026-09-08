package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The cross-source ordering rule, pinned once for both content types: the manga and novel stitchers
 * differ only in what they key on, so the placement they share is tested here rather than twice.
 */
class MergedChapterOrderTest {

    /** Keyed on the letter, so "a" from either source is the same chapter. */
    private fun order() = MergedChapterOrder<String> { it.substringBefore(':') }

    private fun MergedChapterOrder<String>.addSource(vararg items: String) {
        startSource()
        for (item in items) {
            val existing = positionOf(item)
            if (existing >= 0) followTo(existing, item) else place(item)
        }
    }

    @Test
    @DisplayName("one source keeps its own order")
    fun singleSourceKeepsOrder() {
        val order = order()

        order.addSource("a:1", "b:1", "c:1")

        order.result().merged shouldBe listOf("a:1", "b:1", "c:1")
    }

    @Test
    @DisplayName("a chapter only the second source has lands between its neighbours")
    fun gapFillLandsInPosition() {
        val order = order()

        order.addSource("a:1", "c:1")
        order.addSource("a:2", "b:2", "c:2")

        order.result().merged shouldBe listOf("a:1", "b:2", "c:1")
    }

    @Test
    @DisplayName("a chapter the first source already has is not added again")
    fun matchedChapterIsNotDuplicated() {
        val order = order()

        order.addSource("a:1", "b:1")
        order.addSource("a:2", "b:2")

        order.result().merged shouldBe listOf("a:1", "b:1")
    }

    @Test
    @DisplayName("chapters only the second source has keep their run in order")
    fun trailingRunKeepsOrder() {
        val order = order()

        order.addSource("a:1", "b:1")
        order.addSource("a:2", "b:2", "c:2", "d:2")

        order.result().merged shouldBe listOf("a:1", "b:1", "c:2", "d:2")
    }

    @Test
    @DisplayName("a source starting earlier than the first puts its head in front")
    fun leadingRunGoesToTheFront() {
        val order = order()

        order.addSource("c:1", "d:1")
        order.addSource("a:2", "b:2", "c:2")

        order.result().merged shouldBe listOf("a:2", "b:2", "c:1", "d:1")
    }

    @Test
    @DisplayName("a chapter with no identity is never matched away")
    fun unkeyableChapterIsKept() {
        // Where it lands is not knowable, which is why both aggregations only ever place an
        // unidentifiable chapter from the source laying down the spine. It must not vanish.
        val order = MergedChapterOrder<String> { null }

        order.addSource("a:1")
        order.addSource("a:2")

        order.result().merged.toSet() shouldBe setOf("a:1", "a:2")
    }

    /** The source laying down the spine keeps everything it has, identifiable or not. */
    private fun MergedChapterOrder<String>.addTrunk(vararg items: String) {
        startSource()
        items.forEach(::place)
    }

    /** A later source, whose "?" entries carry no identity, so only their position can place them. */
    private fun MergedChapterOrder<String>.addSourceDeferring(vararg items: String) {
        startSource()
        for (item in items) {
            if (item.startsWith("?")) {
                defer(item)
                continue
            }
            val existing = positionOf(item)
            if (existing >= 0) followTo(existing, item) else place(item)
        }
    }

    @Test
    @DisplayName("an unidentifiable run matching the one already there is the same chapters")
    fun equalRunsBetweenAnchorsAreTheSame() {
        val order = order()

        order.addTrunk("a:1", "?x:1", "?y:1", "b:1")
        order.addSourceDeferring("a:2", "?x:2", "?y:2", "b:2")

        order.result().merged shouldBe listOf("a:1", "?x:1", "?y:1", "b:1")
    }

    @Test
    @DisplayName("an unidentifiable run of a different length is kept whole")
    fun unequalRunsAreBothKept() {
        val order = order()

        order.addTrunk("a:1", "?x:1", "b:1")
        order.addSourceDeferring("a:2", "?x:2", "?y:2", "b:2")

        order.result().merged shouldBe listOf("a:1", "?x:2", "?y:2", "?x:1", "b:1")
    }

    @Test
    @DisplayName("an unidentifiable run with nothing after it is kept")
    fun trailingRunWithNoAnchorIsKept() {
        val order = order()

        order.addTrunk("a:1")
        order.addSourceDeferring("a:2", "?x:2")

        order.result().merged shouldBe listOf("a:1", "?x:2")
    }

    @Test
    @DisplayName("each source starts placing from the top again")
    fun cursorResetsPerSource() {
        val order = order()

        order.addSource("b:1")
        order.addSource("a:2", "b:2")

        order.result().merged shouldBe listOf("a:2", "b:1")
    }

    @Test
    @DisplayName("a later source's copy is recorded against the chapter it matched")
    fun matchedCopiesAreRecorded() {
        val order = order()

        order.addSource("a:1", "b:1")
        order.addSource("a:2", "b:2")

        order.result().copies shouldBe listOf("a:2" to "a:1", "b:2" to "b:1")
    }

    @Test
    @DisplayName("an aligned unidentifiable run records each of its chapters as a copy")
    fun alignedRunRecordsCopies() {
        val order = order()

        order.addTrunk("a:1", "?x:1", "?y:1", "b:1")
        order.addSourceDeferring("a:2", "?x:2", "?y:2", "b:2")

        // The run is dropped from the list, so without this the badge would count it twice.
        order.result().copies shouldBe listOf(
            "a:2" to "a:1",
            "?x:2" to "?x:1",
            "?y:2" to "?y:1",
            "b:2" to "b:1",
        )
    }

    @Test
    @DisplayName("a run that was kept whole contributes no copies")
    fun keptRunRecordsNoCopies() {
        val order = order()

        order.addTrunk("a:1", "?x:1", "b:1")
        order.addSourceDeferring("a:2", "?x:2", "?y:2", "b:2")

        order.result().copies shouldBe listOf("a:2" to "a:1", "b:2" to "b:1")
    }

    @Test
    @DisplayName("every chapter maps to the merged position it belongs to")
    fun unitsCoverCopiesAndPlacements() {
        val order = order()

        order.addTrunk("a:1", "?x:1", "?y:1", "b:1")
        order.addSourceDeferring("a:2", "?x:2", "?y:2", "b:2")

        val units = order.result().units(::id).associateBy { it.chapterId }
        listOf("a:1", "a:2").map { units.getValue(id(it)).unit } shouldBe listOf(0, 0)
        listOf("?y:1", "?y:2").map { units.getValue(id(it)).unit } shouldBe listOf(2, 2)
    }

    @Test
    @DisplayName("the chapter the list shows is ordered ahead of the other sources' copies")
    fun shownChapterLeadsItsCopies() {
        val order = order()

        order.addSource("a:1")
        order.addSource("a:2")
        order.addSource("a:3")

        val units = order.result().units(::id).associateBy { it.chapterId }
        listOf("a:1", "a:2", "a:3").map { units.getValue(id(it)).copyOrder } shouldBe listOf(0, 1, 2)
    }

    /** Ids from the fixture's own naming: the source after the colon, the chapter from the letter. */
    private fun id(item: String): Long = item.substringAfter(':').toLong() * 100 + item[0].code
}
