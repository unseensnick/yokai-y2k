package reikai.presentation.reader

import android.content.Context
import android.util.Log
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Where a text reader loses its place: an item inserted above the viewport, or a block inside the
 * visible one that grows after layout. An item stands in for a whole chapter. Laid out offscreen on
 * purpose, because `measure` plus `layout` drive `onLayoutChildren`, which is where anchoring runs.
 * Items carry a stable tag so a measurement follows the same content across an insert. Numbers go to
 * logcat tag "PrependSpike"; the assertions only pin the conclusions. Findings and what they
 * decided: docs/dev/plans/content-layer-reader-surface.md.
 */
@RunWith(AndroidJUnit4::class)
class RecyclerPrependPositionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private companion object {
        const val TAG = "PrependSpike"
        const val WIDTH = 1080
        const val HEIGHT = 1920

        /** A rendered chapter: several screens tall. */
        const val CHAPTER = HEIGHT * 4

        /** What a chapter reports before its text has been laid out. */
        const val UNMEASURED = HEIGHT / 2

        /** Where that chapter lands once the text is measured. */
        const val MEASURED = HEIGHT * 6

        const val CURRENT = "current"
        const val PREVIOUS = "previous"
    }

    private class Item(val tag: String, var height: Int, var body: String? = null)

    private class ChapterAdapter(val items: MutableList<Item>) : RecyclerView.Adapter<ChapterAdapter.VH>() {
        class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val text = TextView(parent.context)
            text.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            text.textSize = 16f
            return VH(text)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.text.tag = item.tag
            val body = item.body
            if (body != null) {
                holder.text.text = body
                holder.text.layoutParams = holder.text.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            } else {
                holder.text.text = ""
                holder.text.layoutParams = holder.text.layoutParams.apply { height = item.height }
            }
        }
    }

    private fun recycler(adapter: RecyclerView.Adapter<*>): RecyclerView =
        RecyclerView(context).also {
            it.layoutManager = LinearLayoutManager(context)
            // Matches WebtoonViewer, which disables the animator so predictive animations stay out of it.
            it.itemAnimator = null
            it.adapter = adapter
        }

    private fun RecyclerView.relayout() {
        measure(
            MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(HEIGHT, MeasureSpec.EXACTLY),
        )
        layout(0, 0, WIDTH, HEIGHT)
    }

    /** Screen-space top of the laid-out child carrying [tag], or null when it is not attached. */
    private fun RecyclerView.topOfTag(tag: String): Int? =
        children.firstOrNull { it.tag == tag }?.top

    private fun paragraphs(count: Int, marker: String) =
        (1..count).joinToString("\n\n") { "$marker $it. " + "lorem ipsum dolor sit amet consectetur ".repeat(10) }

    /**
     * Runs one prepend and reports how far the current chapter moved on screen.
     *
     * [intoCurrent] is how many pixels into the current chapter the viewport starts, so 0 means its
     * first line sits at the top of the screen. [growAfterLayout] switches between the two cases that
     * matter: an insert whose height is final when it is laid out, and one that only reaches its real
     * height afterwards, which is what an asynchronous text layout does.
     */
    private fun measurePrepend(
        label: String,
        intoCurrent: Int,
        growAfterLayout: Boolean,
        compensate: Boolean = false,
    ): Int? {
        var drift: Int? = null
        instrumentation.runOnMainSync {
            val items = mutableListOf(Item(CURRENT, CHAPTER), Item("next", CHAPTER))
            val adapter = ChapterAdapter(items)
            val rv = recycler(adapter)
            rv.relayout()
            if (intoCurrent > 0) rv.scrollBy(0, intoCurrent)

            val before = rv.topOfTag(CURRENT)
            if (before == null) {
                Log.w(TAG, "$label: current chapter not attached before the insert, skipping")
                return@runOnMainSync
            }

            val insertedHeight = if (growAfterLayout) UNMEASURED else MEASURED
            items.add(0, Item(PREVIOUS, insertedHeight))
            adapter.notifyItemInserted(0)
            rv.relayout()
            val afterInsert = rv.topOfTag(CURRENT)

            var afterGrowth = afterInsert
            if (growAfterLayout) {
                items[0].height = MEASURED
                adapter.notifyItemChanged(0)
                rv.relayout()
                afterGrowth = rv.topOfTag(CURRENT)
            }

            var corrected = afterGrowth
            if (compensate && afterInsert != null && afterGrowth != null) {
                // Take back exactly what the growth added above the viewport.
                rv.scrollBy(0, afterGrowth - afterInsert)
                rv.relayout()
                corrected = rv.topOfTag(CURRENT)
            }

            drift = corrected?.minus(before)
            Log.i(
                TAG,
                "$label: intoCurrent=$intoCurrent before=$before afterInsert=$afterInsert " +
                    "afterGrowth=$afterGrowth corrected=$corrected drift=$drift",
            )
        }
        return drift
    }

    /** The image case: the inserted chapter's height is final the moment it is laid out. */
    @Test
    fun heightFinalAtLayout_atTopOfCurrentChapter() {
        val drift = measurePrepend("final-height, at top", intoCurrent = 0, growAfterLayout = false)
        assertNotNull("the current chapter should stay attached", drift)
        Log.i(TAG, "RESULT final-height at top: drift=$drift")
        assertTrue("drift=$drift", abs(drift!!) <= 2)
    }

    /** The same, but a little way into the chapter, so the anchor is not the very first child. */
    @Test
    fun heightFinalAtLayout_insideCurrentChapter() {
        val drift = measurePrepend("final-height, inside", intoCurrent = HEIGHT, growAfterLayout = false)
        assertNotNull("the current chapter should stay attached", drift)
        Log.i(TAG, "RESULT final-height inside: drift=$drift")
        assertTrue("drift=$drift", abs(drift!!) <= 2)
    }

    /** The text case: the insert is laid out short and only reaches its real height afterwards. */
    @Test
    fun heightGrowsAfterLayout_atTopOfCurrentChapter() {
        val drift = measurePrepend("grows-after, at top", intoCurrent = 0, growAfterLayout = true)
        assertNotNull("the current chapter should stay attached", drift)
        Log.i(TAG, "RESULT grows-after at top: drift=$drift (growth was ${MEASURED - UNMEASURED})")
    }

    /** The text case from inside the chapter, which is where seamless reading actually starts. */
    @Test
    fun heightGrowsAfterLayout_insideCurrentChapter() {
        val drift = measurePrepend("grows-after, inside", intoCurrent = HEIGHT, growAfterLayout = true)
        assertNotNull("the current chapter should stay attached", drift)
        Log.i(TAG, "RESULT grows-after inside: drift=$drift (growth was ${MEASURED - UNMEASURED})")
    }

    /** Measure what the growth added and scroll it back out, the way tsundoku compensates on prune. */
    @Test
    fun heightGrowsAfterLayout_withCompensation() {
        val drift = measurePrepend(
            "grows-after, compensated",
            intoCurrent = HEIGHT,
            growAfterLayout = true,
            compensate = true,
        )
        assertNotNull("the current chapter should stay attached", drift)
        Log.i(TAG, "RESULT compensated: drift=$drift")
        assertTrue("compensation should restore the position, drift=$drift", abs(drift!!) <= 2)
    }

    /** Real text rather than synthetic heights, so the conclusion does not rest on the simulation. */
    @Test
    fun realTextGrowsAfterLayout() {
        var drift: Int? = null
        instrumentation.runOnMainSync {
            val items = mutableListOf(
                Item(CURRENT, 0, paragraphs(40, "current")),
                Item("next", 0, paragraphs(40, "next")),
            )
            val adapter = ChapterAdapter(items)
            val rv = recycler(adapter)
            rv.relayout()
            rv.scrollBy(0, HEIGHT)

            val before = rv.topOfTag(CURRENT)
            items.add(0, Item(PREVIOUS, 0, "previous chapter, still measuring"))
            adapter.notifyItemInserted(0)
            rv.relayout()
            val afterInsert = rv.topOfTag(CURRENT)

            items[0].body = paragraphs(60, "previous")
            adapter.notifyItemChanged(0)
            rv.relayout()
            val afterGrowth = rv.topOfTag(CURRENT)

            drift = if (before != null && afterGrowth != null) afterGrowth - before else null
            Log.i(
                TAG,
                "real-text: before=$before afterInsert=$afterInsert afterGrowth=$afterGrowth drift=$drift",
            )
        }
        Log.i(TAG, "RESULT real-text: drift=$drift")
        assertNotNull("the current chapter should stay attached", drift)
    }

    /**
     * A chapter is not one view: tsundoku splits it into stacked chunk views because a single
     * TextView makes layout, span lookup and hit-testing order-of-chapter. That stack is a plain
     * LinearLayout inside one recycler item, and a LinearLayout has no anchoring of its own, so this
     * is where a late height change can actually move the reader. Growth ABOVE the reading position
     * is the case that matters; growth below should be invisible.
     */
    private fun measureChunkGrowth(label: String, growAtChunk: Int, compensate: Boolean): Int? {
        var drift: Int? = null
        val chunkHeight = HEIGHT / 2
        val chunkCount = 12
        val readingAt = chunkHeight * 6
        instrumentation.runOnMainSync {
            val stack = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            repeat(chunkCount) { index ->
                val chunk = View(context).apply {
                    tag = "chunk$index"
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, chunkHeight)
                }
                stack.addView(chunk)
            }
            val scroller = androidx.core.widget.NestedScrollView(context).apply { addView(stack) }
            scroller.measure(
                MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(HEIGHT, MeasureSpec.EXACTLY),
            )
            scroller.layout(0, 0, WIDTH, HEIGHT)
            scroller.scrollTo(0, readingAt)

            // The chunk the reader is looking at, in screen space.
            val watched = stack.getChildAt(7)
            val before = watched.top - scroller.scrollY

            val grown = chunkHeight * 3
            val delta = grown - chunkHeight
            val target = stack.getChildAt(growAtChunk)
            target.layoutParams = target.layoutParams.apply { height = grown }
            stack.measure(
                MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            scroller.measure(
                MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(HEIGHT, MeasureSpec.EXACTLY),
            )
            scroller.layout(0, 0, WIDTH, HEIGHT)
            var after = watched.top - scroller.scrollY

            if (compensate) {
                scroller.scrollBy(0, after - before)
                after = watched.top - scroller.scrollY
            }

            drift = after - before
            Log.i(
                TAG,
                "$label: readingAt=$readingAt grewChunk=$growAtChunk by=$delta before=$before " +
                    "after=$after drift=$drift",
            )
        }
        return drift
    }

    /** A chunk above the reading position finishes laying out and gets taller. */
    @Test
    fun chunkGrowsAboveReadingPosition() {
        val drift = measureChunkGrowth("chunk-above", growAtChunk = 2, compensate = false)
        Log.i(TAG, "RESULT chunk grows above: drift=$drift")
        assertNotNull(drift)
    }

    /** A chunk below the reading position gets taller, which should be invisible. */
    @Test
    fun chunkGrowsBelowReadingPosition() {
        val drift = measureChunkGrowth("chunk-below", growAtChunk = 10, compensate = false)
        Log.i(TAG, "RESULT chunk grows below: drift=$drift")
        assertNotNull(drift)
    }

    /** The same growth above, with the compensating scroll tsundoku uses on prune. */
    @Test
    fun chunkGrowsAboveReadingPosition_withCompensation() {
        val drift = measureChunkGrowth("chunk-above-compensated", growAtChunk = 2, compensate = true)
        Log.i(TAG, "RESULT chunk grows above, compensated: drift=$drift")
        assertNotNull(drift)
        assertTrue("compensation should restore the reading position, drift=$drift", abs(drift!!) <= 2)
    }

    /**
     * The configuration actually proposed: a recycler of chapters where each item is a stack of chunk
     * views, so the between-chapter and within-chapter cases are exercised together rather than
     * separately. Growth above the reading position inside the visible chapter is the one that moves
     * the reader; a prepended chapter behind it does not.
     */
    @Test
    fun recyclerOfChunkStacks_prependIsFreeAndChunkGrowthIsNot() {
        var prependDrift: Int? = null
        var chunkDrift: Int? = null
        val chunkHeight = HEIGHT / 2
        instrumentation.runOnMainSync {
            val stacks = mutableListOf("first", CURRENT)
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = stacks.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val stack = android.widget.LinearLayout(parent.context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    }
                    repeat(10) {
                        stack.addView(
                            View(parent.context).apply {
                                layoutParams =
                                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, chunkHeight)
                            },
                        )
                    }
                    return object : RecyclerView.ViewHolder(stack) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    holder.itemView.tag = stacks[position]
                }
            }
            val rv = recycler(adapter)
            rv.relayout()
            rv.scrollBy(0, chunkHeight * 10 + chunkHeight * 5)
            rv.relayout()

            val stack = rv.children.firstOrNull { it.tag == CURRENT } as? android.widget.LinearLayout
            if (stack == null) {
                Log.w(TAG, "composite: current stack not attached, skipping")
                return@runOnMainSync
            }
            val watched = stack.getChildAt(6)
            fun screenTop() = stack.top + watched.top

            // A chapter arrives above and then grows; the reader should not feel it.
            val before = screenTop()
            stacks.add(0, PREVIOUS)
            adapter.notifyItemInserted(0)
            rv.relayout()
            prependDrift = screenTop() - before
            Log.i(TAG, "composite prepend: before=$before after=${screenTop()} drift=$prependDrift")

            // A chunk above the reading position finishes measuring inside the visible chapter.
            val beforeChunk = screenTop()
            val target = stack.getChildAt(2)
            target.layoutParams = target.layoutParams.apply { height = chunkHeight * 3 }
            target.requestLayout()
            rv.relayout()
            chunkDrift = screenTop() - beforeChunk
            Log.i(TAG, "composite chunk-above: before=$beforeChunk after=${screenTop()} drift=$chunkDrift")
        }
        Log.i(TAG, "RESULT composite: prependDrift=$prependDrift chunkDrift=$chunkDrift")
        assertNotNull(prependDrift)
        assertTrue("a prepended chapter should not move the reader, drift=$prependDrift", abs(prependDrift!!) <= 2)
    }

    /** How wide the window actually is: how many chapter-sized children stay laid out at once. */
    @Test
    fun laidOutChildCountWithChapterSizedItems() {
        var attached = -1
        var range = ""
        instrumentation.runOnMainSync {
            val items = mutableListOf(
                Item(PREVIOUS, CHAPTER),
                Item(CURRENT, CHAPTER),
                Item("next", CHAPTER),
            )
            val rv = recycler(ChapterAdapter(items))
            rv.relayout()
            rv.scrollBy(0, CHAPTER + HEIGHT)
            rv.relayout()
            val lm = rv.layoutManager as LinearLayoutManager
            attached = rv.childCount
            range = "${lm.findFirstVisibleItemPosition()}..${lm.findLastVisibleItemPosition()}"
        }
        Log.i(TAG, "RESULT window: attached=$attached visible=$range")
        assertTrue("at least one child should be laid out", attached >= 1)
    }

    /**
     * The other half of a bounded window: dropping the chapter behind the reader. The prepend case
     * proved an insert above costs nothing; a removal above is the same anchoring question and had
     * never been measured, and the shape being replaced compensated for it by hand, because a
     * NestedScrollView does need that.
     */
    @Test
    fun evictAboveReadingPositionIsFree() {
        var drift: Int? = null
        instrumentation.runOnMainSync {
            val items = mutableListOf(
                Item(PREVIOUS, CHAPTER),
                Item(CURRENT, CHAPTER),
                Item("next", CHAPTER),
            )
            val rv = recycler(ChapterAdapter(items))
            rv.relayout()
            // Into the middle of the current chapter, so the one being dropped is entirely above.
            rv.scrollBy(0, CHAPTER + HEIGHT)
            rv.relayout()

            val before = rv.topOfTag(CURRENT)
            if (before == null) {
                Log.w(TAG, "evict: current chapter not attached, skipping")
                return@runOnMainSync
            }
            items.removeAt(0)
            rv.adapter?.notifyItemRemoved(0)
            rv.relayout()

            val after = rv.topOfTag(CURRENT)
            if (after == null) {
                Log.w(TAG, "evict: current chapter detached after removal, skipping")
                return@runOnMainSync
            }
            drift = after - before
            Log.i(TAG, "evict-above: before=$before after=$after drift=$drift")
        }
        Log.i(TAG, "RESULT evict: drift=$drift")
        assertNotNull(drift)
        assertTrue("dropping a chapter above should not move the reader, drift=$drift", abs(drift!!) <= 2)
    }
}
