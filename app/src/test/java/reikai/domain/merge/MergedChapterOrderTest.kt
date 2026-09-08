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
            if (existing >= 0) followTo(existing) else place(item)
        }
    }

    @Test
    @DisplayName("one source keeps its own order")
    fun singleSourceKeepsOrder() {
        val order = order()

        order.addSource("a:1", "b:1", "c:1")

        order.result() shouldBe listOf("a:1", "b:1", "c:1")
    }

    @Test
    @DisplayName("a chapter only the second source has lands between its neighbours")
    fun gapFillLandsInPosition() {
        val order = order()

        order.addSource("a:1", "c:1")
        order.addSource("a:2", "b:2", "c:2")

        order.result() shouldBe listOf("a:1", "b:2", "c:1")
    }

    @Test
    @DisplayName("a chapter the first source already has is not added again")
    fun matchedChapterIsNotDuplicated() {
        val order = order()

        order.addSource("a:1", "b:1")
        order.addSource("a:2", "b:2")

        order.result() shouldBe listOf("a:1", "b:1")
    }

    @Test
    @DisplayName("chapters only the second source has keep their run in order")
    fun trailingRunKeepsOrder() {
        val order = order()

        order.addSource("a:1", "b:1")
        order.addSource("a:2", "b:2", "c:2", "d:2")

        order.result() shouldBe listOf("a:1", "b:1", "c:2", "d:2")
    }

    @Test
    @DisplayName("a source starting earlier than the first puts its head in front")
    fun leadingRunGoesToTheFront() {
        val order = order()

        order.addSource("c:1", "d:1")
        order.addSource("a:2", "b:2", "c:2")

        order.result() shouldBe listOf("a:2", "b:2", "c:1", "d:1")
    }

    @Test
    @DisplayName("a chapter with no identity is never matched away")
    fun unkeyableChapterIsKept() {
        // Where it lands is not knowable, which is why both aggregations only ever place an
        // unidentifiable chapter from the source laying down the spine. It must not vanish.
        val order = MergedChapterOrder<String> { null }

        order.addSource("a:1")
        order.addSource("a:2")

        order.result().toSet() shouldBe setOf("a:1", "a:2")
    }

    @Test
    @DisplayName("each source starts placing from the top again")
    fun cursorResetsPerSource() {
        val order = order()

        order.addSource("b:1")
        order.addSource("a:2", "b:2")

        order.result() shouldBe listOf("a:2", "b:1")
    }
}
