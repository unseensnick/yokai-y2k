package reikai.presentation.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.method.ArrowKeyMovementMethod
import android.view.Choreographer
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
import reikai.presentation.reader.text.ParagraphShape
import kotlin.math.roundToInt

/**
 * The native light-novel viewport: the chapter as real text views rather than a WebView.
 *
 * The container is a recycler holding one chapter today, which is the shape the seamless window
 * needs, so that step extends this rather than re-hosting it.
 */
class NovelTextViewport(
    private val context: Context,
    /** Selection and clickable links are exclusive: the movement method that drags cannot click. */
    private val textSelectable: Boolean,
    private val volumeKeysEnabled: Boolean,
    private val volumeKeysInverted: Boolean,
    private val volumeKeyScrollFraction: Float,
    private val onProgressChanged: (Int) -> Unit,
    private val onProgressSettled: (Int) -> Unit,
    private val onToggleMenu: () -> Unit,
) : ReaderViewport, TextViewport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = NovelTextRenderer(context, scope)

    private var settings: NovelReaderSettings? = null
    private var block: ChapterTextBlock? = null

    /** Held so a setting the spans are built from can draw the same chapter again. */
    private var loaded: NovelReaderViewModel.LoadedChapter? = null

    /** Applied once the rendered text has a height to seek within, then cleared. */
    private var pendingProgress: Float? = null

    /** Gates progress reporting: before the text is set there is nothing to be a percentage of. */
    private var rendered = false

    /** Auto-scroll in pixels a second, zero when it is off. Carry and timestamp belong to the frame
     *  callback below and are held here so stopping can reset them. */
    private var autoScrollRate = 0f
    private var autoScrollCarry = 0f
    private var autoScrollLastFrameNanos = 0L

    private val adapter = BlockAdapter()

    /**
     * `viewer_container` blocks descendant focus (`reader_activity.xml:14`, upstream's, so the image
     * viewers keep it), and an unfocusable TextView never initialises the Editor that draws
     * selection handles. Lifted only while this viewport is attached, and only when selection is on.
     * The parent is held rather than re-read, because a detach can arrive after it is gone.
     * Declared above the recycler that registers it, or it is null when that runs.
     */
    private val focusableWhileAttached = object : View.OnAttachStateChangeListener {

        private var host: ViewGroup? = null
        private var blocked = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        override fun onViewAttachedToWindow(v: View) {
            val parent = v.parent as? ViewGroup ?: return
            host = parent
            blocked = parent.descendantFocusability
            parent.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        override fun onViewDetachedFromWindow(v: View) {
            host?.descendantFocusability = blocked
            host = null
        }
    }

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
        if (textSelectable) addOnAttachStateChangeListener(focusableWhileAttached)
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
        loaded = chapter
        draw(chapter, settings, startFraction = chapter.progressPercent / 100f)
    }

    override fun applySettings(settings: NovelReaderSettings) {
        val previous = this.settings
        this.settings = settings
        recycler.setBackgroundColor(NovelTextStyle.parseColor(settings.backgroundColor, Color.WHITE))

        val chapter = loaded
        if (chapter != null && previous != null &&
            previous.paragraphShape().needsRedrawFor(settings.paragraphShape())
        ) {
            draw(chapter, settings, startFraction = percent() / 100f)
            return
        }
        block?.let { NovelTextStyle.applyMargins(it.container, settings, context) }
        block?.chunkViews?.forEach { view ->
            // A precomputed layout was measured against the old paint, and the framework's own
            // long-press drag path re-sets it without checking, which throws. Copying rather than
            // flattening is what keeps the chapter's emphasis, links, images and paragraph spans.
            view.text = SpannableStringBuilder(view.text)
            NovelTextStyle.apply(view, settings, context)
        }
    }

    /**
     * Auto-scroll, driven by a frame callback so it moves the recycler the same way a drag does
     * rather than competing with it. [pixelsPerFrame] is the WebView renderer's unit, a CSS pixel
     * per frame at 60Hz, so it becomes a rate and crosses into the device pixels a recycler scrolls
     * in. Without the density the same setting would move three times as far over there.
     */
    override fun setAutoScroll(running: Boolean, pixelsPerFrame: Float) {
        val density = context.resources.displayMetrics.density
        val rate = if (running) pixelsPerFrame * FRAMES_PER_SECOND * density else 0f
        if (rate == autoScrollRate) return
        val wasRunning = autoScrollRate > 0f
        autoScrollRate = rate
        if (rate <= 0f) {
            Choreographer.getInstance().removeFrameCallback(autoScrollFrames)
        } else if (!wasRunning) {
            autoScrollLastFrameNanos = 0L
            autoScrollCarry = 0f
            Choreographer.getInstance().postFrameCallback(autoScrollFrames)
        }
    }

    /** The fraction is carried between frames, or a speed below one pixel a frame never moves at all.
     *  The first frame only takes a timestamp, since there is no interval to scroll over yet. */
    private val autoScrollFrames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (autoScrollRate <= 0f) return
            val previous = autoScrollLastFrameNanos
            autoScrollLastFrameNanos = frameTimeNanos
            if (previous != 0L) {
                autoScrollCarry += autoScrollRate * ((frameTimeNanos - previous) / NANOS_PER_SECOND)
                val whole = autoScrollCarry.toInt()
                if (whole != 0) {
                    autoScrollCarry -= whole
                    recycler.scrollBy(0, whole)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Indent and spacing are spans measured in pixels when the text is built, so neither they nor a
     * font size they are a multiple of can be restyled in place. Redrawing from [startFraction] is
     * what keeps the reader where it was.
     */
    private fun draw(
        chapter: NovelReaderViewModel.LoadedChapter,
        settings: NovelReaderSettings,
        startFraction: Float,
    ) {
        this.settings = settings
        recycler.setBackgroundColor(NovelTextStyle.parseColor(settings.backgroundColor, Color.WHITE))
        val block = ChapterTextBlock(context) { createChunkView(settings) }
        NovelTextStyle.applyMargins(block.container, settings, context)
        this.block = block
        rendered = false
        adapter.show(block)
        pendingProgress = startFraction
        renderer.render(
            block = block,
            html = chapter.html,
            fontSize = settings.fontSize,
            paragraphSpacing = settings.paragraphSpacing,
            paragraphIndent = settings.paragraphIndent,
            selectable = textSelectable,
            bionic = settings.bionicReading,
            refererUrl = chapter.baseUrl?.let { it.trimEnd('/') + "/" },
            onTextSet = ::applyPendingProgress,
        )
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
        setAutoScroll(running = false, pixelsPerFrame = 0f)
        scope.cancel()
        adapter.show(null)
        block = null
        loaded = null
    }

    /** The same contract [NovelWebViewport] answers, so a volume press behaves the same in either. */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!volumeKeysEnabled || !isVolumeKey) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != volumeKeysInverted
            val fraction = volumeKeyScrollFraction.coerceIn(0.1f, 1f)
            val step = (recycler.height * fraction).roundToInt()
            recycler.smoothScrollBy(0, if (forward) step else -step)
        }
        // Consume the key-up too, so the system volume UI never shows during a press.
        return true
    }

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

    private companion object {
        /** The frame rate the WebView renderer's per-frame speed was written against. */
        const val FRAMES_PER_SECOND = 60f
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}

private fun NovelReaderSettings.paragraphShape() =
    ParagraphShape(paragraphIndent, paragraphSpacing, fontSize, bionicReading)
