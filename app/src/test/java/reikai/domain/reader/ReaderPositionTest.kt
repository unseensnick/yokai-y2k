package reikai.domain.reader

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The reader's position rules, pinned once over both media rather than as a twin pair. The completion
 * rule is the one that matters most: it used to live in the manga view model as an index comparison,
 * which a continuous medium can never satisfy, so a novel would have read to the end of a chapter and
 * never been credited with it.
 */
class ReaderPositionTest {

    // Completion. A paged chapter finishes on its last page; a continuous one at the incumbent 97%,
    // which is the threshold novels already ship with.

    @Test
    fun `a paged chapter open on its last page is complete`() {
        ChapterProgress.Pages(lastPageRead = 37, pageCount = 38).isChapterComplete shouldBe true
    }

    @Test
    fun `a paged chapter open short of the end is not complete`() {
        ChapterProgress.Pages(lastPageRead = 36, pageCount = 38).isChapterComplete shouldBe false
    }

    @Test
    fun `a one-page chapter is complete on its only page`() {
        // Incumbent manga behaviour, kept deliberately: one page read is the whole chapter read. The
        // hazard this used to carry was a continuous chapter modelled as one stub page, which the
        // Percent variant now makes unrepresentable.
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 1).isChapterComplete shouldBe true
    }

    @Test
    fun `a chapter whose length is unknown never completes`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 0).isChapterComplete shouldBe false
    }

    @Test
    fun `a continuous chapter at the threshold is complete`() {
        ChapterProgress.Percent(hundredths = 9700).isChapterComplete shouldBe true
    }

    @Test
    fun `a continuous chapter just under the threshold is not complete`() {
        ChapterProgress.Percent(hundredths = 9699).isChapterComplete shouldBe false
    }

    @Test
    fun `a continuous chapter is not complete the moment it opens`() {
        ChapterProgress.Percent(hundredths = 0).isChapterComplete shouldBe false
    }

    // The derived fraction the navigator's thumb sits at.

    @Test
    fun `a paged chapter at its first page reads as no progress`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 38).fraction shouldBe 0f
    }

    @Test
    fun `a paged chapter at its last page reads as fully through`() {
        ChapterProgress.Pages(lastPageRead = 37, pageCount = 38).fraction shouldBe 1f
    }

    @Test
    fun `a one-page chapter has no span to be part-way through`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 1).fraction shouldBe 0f
    }

    @Test
    fun `a continuous chapter reads its stored hundredths as a fraction`() {
        ChapterProgress.Percent(hundredths = 6200).fraction shouldBe (0.62f plusOrMinus 0.0001f)
    }

    @Test
    fun `a continuous chapter at the end reads as fully through`() {
        ChapterProgress.Percent(hundredths = 10000).fraction shouldBe 1f
    }

    // Slider shape. The count feeds Material's steps, which throws below zero, so the arithmetic that
    // used to produce a negative one is clamped here instead of guarded at the call site.

    @Test
    fun `a paged chapter offers a detent for every page between the ends`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 38).stepCount shouldBe 36
    }

    @Test
    fun `a one-page chapter offers no detents rather than a negative count`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 1).stepCount shouldBe 0
    }

    @Test
    fun `a chapter of unknown length offers no detents rather than a negative count`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 0).stepCount shouldBe 0
    }

    /** The detents are the marks that read as a progress rail; the novel reader has always had them. */
    @Test
    fun `a continuous chapter keeps the rail's detents`() {
        ChapterProgress.Percent(hundredths = 0).stepCount shouldBe 33
    }

    @Test
    fun `a chapter with more than one page can be scrubbed`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 38).isSeekable shouldBe true
    }

    @Test
    fun `a one-page chapter has nowhere to scrub to`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 1).isSeekable shouldBe false
    }

    @Test
    fun `a continuous chapter can always be scrubbed`() {
        ChapterProgress.Percent(hundredths = 0).isSeekable shouldBe true
    }

    // Labels. A page is stored zero-based and reads one-based, the same rule the recents rows use.

    @Test
    fun `a page reads one-based`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 38).leadingLabel shouldBe "1"
    }

    @Test
    fun `a paged chapter names its length at the far end`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 38).trailingLabel shouldBe "38"
    }

    @Test
    fun `a continuous chapter reads as a whole percent`() {
        ChapterProgress.Percent(hundredths = 6250).leadingLabel shouldBe "62%"
    }

    @Test
    fun `a continuous chapter names a full percent at the far end`() {
        ChapterProgress.Percent(hundredths = 0).trailingLabel shouldBe "100%"
    }

    // The page a paged progress names, which is what the paged viewers are driven by.

    @Test
    fun `a paged chapter names the page it sits on`() {
        ChapterProgress.Pages(lastPageRead = 5, pageCount = 38).pageIndex shouldBe 5
    }

    @Test
    fun `a continuous chapter names no page at all`() {
        ChapterProgress.Percent(hundredths = 6200).pageIndex shouldBe null
    }

    // Scrubbing back, the inverse of the fraction.

    @Test
    fun `scrubbing a paged chapter to the middle lands on a page`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 11)
            .seekTo(0.5f) shouldBe ChapterProgress.Pages(lastPageRead = 5, pageCount = 11)
    }

    @Test
    fun `scrubbing a paged chapter past the end stops at the last page`() {
        ChapterProgress.Pages(lastPageRead = 0, pageCount = 11)
            .seekTo(2f) shouldBe ChapterProgress.Pages(lastPageRead = 10, pageCount = 11)
    }

    @Test
    fun `scrubbing a paged chapter before the start stops at the first page`() {
        ChapterProgress.Pages(lastPageRead = 5, pageCount = 11)
            .seekTo(-1f) shouldBe ChapterProgress.Pages(lastPageRead = 0, pageCount = 11)
    }

    @Test
    fun `scrubbing a continuous chapter lands on the stored hundredths`() {
        ChapterProgress.Percent(hundredths = 0)
            .seekTo(0.62f) shouldBe ChapterProgress.Percent(hundredths = 6200)
    }

    @Test
    fun `scrubbing a continuous chapter past the end stops at its end`() {
        ChapterProgress.Percent(hundredths = 0)
            .seekTo(2f) shouldBe ChapterProgress.Percent(hundredths = 10000)
    }

    @Test
    fun `scrubbing a continuous chapter before the start stops at its start`() {
        ChapterProgress.Percent(hundredths = 6200)
            .seekTo(-1f) shouldBe ChapterProgress.Percent(hundredths = 0)
    }
}
