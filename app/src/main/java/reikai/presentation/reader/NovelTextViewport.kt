package reikai.presentation.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.method.ArrowKeyMovementMethod
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.fraction
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.reader.text.ChapterTextBlock
import reikai.presentation.reader.text.LinkOnlyMovementMethod
import reikai.presentation.reader.text.NovelTextRenderer
import reikai.presentation.reader.text.NovelTextStyle
import kotlin.math.roundToInt

/**
 * The native light-novel viewport: the chapter as real text views rather than a WebView.
 *
 * The container is a recycler holding one chapter today, which is the shape the seamless window
 * needs, so that step extends this rather than re-hosting it. Links render as plain text for now:
 * nothing here makes a URL clickable, so a chapter cannot navigate anywhere at all.
 */
class NovelTextViewport(
    private val context: Context,
    /** Selection and clickable links are exclusive: the movement method that drags cannot click. */
    private val textSelectable: Boolean,
    private val onProgressChanged: (Int) -> Unit,
    private val onProgressSettled: (Int) -> Unit,
    private val onToggleMenu: () -> Unit,
) : ReaderViewport, TextViewport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = NovelTextRenderer(context, scope)

    private var settings: NovelReaderSettings? = null
    private var block: ChapterTextBlock? = null

    /** Applied once the rendered text has a height to seek within, then cleared. */
    private var pendingProgress: Float? = null

    /** Gates progress reporting: before the text is set there is nothing to be a percentage of. */
    private var rendered = false

    private val adapter = BlockAdapter()

    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@NovelTextViewport.adapter
        // The chapter is one item; recycling it would throw away the laid-out text we just built.
        setItemViewCacheSize(1)
        isVerticalScrollBarEnabled = true
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) = onProgressChanged(percent())
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) onProgressSettled(percent())
            }
        })
    }

    override val view: View get() = recycler

    /** A chapter is one vertically scrolling column, so there is no right-to-left shape to report. */
    override val isRtl: Boolean get() = false

    override suspend fun load(
        chapter: NovelReaderViewModel.LoadedChapter,
        hasPrevious: Boolean,
        hasNext: Boolean,
        settings: NovelReaderSettings,
    ) {
        this.settings = settings
        recycler.setBackgroundColor(NovelTextStyle.parseColor(settings.backgroundColor, Color.WHITE))
        val block = ChapterTextBlock(context) { createChunkView(settings) }
        this.block = block
        rendered = false
        adapter.show(block)
        pendingProgress = chapter.progressPercent / 100f
        renderer.render(
            block = block,
            html = chapter.html,
            fontSize = settings.fontSize,
            // Net-new settings; until they have their own preferences the renderer runs unstyled here
            // and paragraph shape comes from the markup, as the WebView reader's does.
            paragraphSpacing = 0f,
            paragraphIndent = 0f,
            selectable = textSelectable,
            refererUrl = chapter.baseUrl?.let { it.trimEnd('/') + "/" },
            onTextSet = ::applyPendingProgress,
        )
    }

    override fun applySettings(settings: NovelReaderSettings) {
        this.settings = settings
        recycler.setBackgroundColor(NovelTextStyle.parseColor(settings.backgroundColor, Color.WHITE))
        // Restyling a view holding PrecomputedText crashes the framework's own long-press drag path,
        // so the text is taken back to a plain CharSequence first (tsundoku NovelViewer:1754).
        block?.chunkViews?.forEach { view ->
            view.text = view.text.toString()
            NovelTextStyle.apply(view, settings, context)
        }
    }

    override fun seekTo(progress: ChapterProgress) {
        val fraction = progress.fraction ?: return
        val range = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent()
        if (range <= 0) {
            pendingProgress = fraction
            return
        }
        recycler.scrollBy(0, (range * fraction).roundToInt() - recycler.computeVerticalScrollOffset())
    }

    /** A step rebuilds the chapter, so there is nothing to tell the viewport until it holds more
     *  than one at a time. */
    override fun onChapterStepped() = Unit

    override fun destroy() {
        scope.cancel()
        adapter.show(null)
        block = null
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean = false

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * Zero rather than a hundred while the range is unknown. The recycler reports no scroll range
     * until the text is measured, and reporting completion there would mark the chapter read and
     * retire its download before it had been seen. A chapter genuinely shorter than the screen also
     * reports zero, matching what the WebView reader reports for one.
     */
    private fun percent(): Int {
        if (!rendered) return 0
        val range = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent()
        if (range <= 0) return 0
        return ((recycler.computeVerticalScrollOffset() * 100f) / range).roundToInt().coerceIn(0, 100)
    }

    private fun applyPendingProgress() {
        rendered = true
        val fraction = pendingProgress ?: return
        pendingProgress = null
        if (fraction <= 0f) return
        // Posted so the freshly set text has been measured; before that the scroll range is zero.
        recycler.post {
            val range = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent()
            if (range > 0) recycler.scrollBy(0, (range * fraction).roundToInt())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createChunkView(settings: NovelReaderSettings): TextView =
        TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            NovelTextStyle.apply(this, settings, context)
            setTextIsSelectable(textSelectable)
            // The chunk views cover the whole chapter, so a tap on the text never reaches the item
            // view underneath. LinkOnlyMovementMethod declines a tap that is not on a link, which is
            // what lets the click through to here.
            setOnClickListener { onToggleMenu() }
            // Off on both branches: the click is dispatched by the movement method below, so leaving
            // it on would let the framework fire its own unchecked intent for the same tap.
            linksClickable = false
            movementMethod = if (textSelectable) {
                ArrowKeyMovementMethod.getInstance()
            } else {
                LinkOnlyMovementMethod
            }
        }

    /** One item, the open chapter. The seamless window turns this into the engine's three slots. */
    private inner class BlockAdapter : RecyclerView.Adapter<BlockAdapter.Holder>() {

        private var shown: ChapterTextBlock? = null

        fun show(block: ChapterTextBlock?) {
            shown = block
            notifyDataSetChanged()
        }

        inner class Holder(val root: ViewGroup) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = android.widget.FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            return Holder(root)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.root.removeAllViews()
            val container = shown?.container ?: return
            (container.parent as? ViewGroup)?.removeView(container)
            holder.root.addView(container)
            holder.root.setOnClickListener { onToggleMenu() }
        }

        override fun getItemCount(): Int = if (shown == null) 0 else 1
    }
}
