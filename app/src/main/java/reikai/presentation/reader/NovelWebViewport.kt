package reikai.presentation.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import logcat.logcat
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.fraction
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.novel.reader.NovelReaderWebInterface
import reikai.presentation.novel.reader.buildReaderHtml
import reikai.presentation.novel.reader.generalSettingsJson
import reikai.presentation.novel.reader.readerSettingsJson
import kotlin.math.roundToInt

/**
 * The light-novel adapter under [ReaderViewport], rendering a chapter document with the bundled
 * `index.css` and `core.js` in a WebView, as the novel reader has always done.
 *
 * The volume-key preferences arrive as values rather than a preferences class, so the viewport is
 * constructible without the graph. They are read once at construction, and nothing rebuilds the
 * viewport when they change, so a mid-session change to them takes effect on the next open.
 */
@SuppressLint("SetJavaScriptEnabled")
class NovelWebViewport(
    private val context: Context,
    private val volumeKeysEnabled: Boolean,
    private val volumeKeysInverted: Boolean,
    private val volumeKeyScrollFraction: Float,
    private val onProgressChanged: (Int) -> Unit,
    private val onProgressSettled: (Int) -> Unit,
    private val onToggleMenu: () -> Unit,
    /** Read per load rather than once: the cutout inset is only known after the window has one. */
    private val statusBarHeightPx: () -> Int,
) : ReaderViewport, TextViewport {

    /** The general block the live document was last given, so a push that would rebuild its DOM only
     *  happens when something in that block actually changed. */
    private var lastGeneralSettings: String? = null

    // Bridge messages arrive on a WebView background thread, so UI-affecting callbacks marshal here.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val webView = ProgressWebView(context).apply {
        setDefaultSettings()
        // file:///android_asset bundled CSS/JS + fonts. The dangerous universal/file-from-file access
        // flags stay off (security): the chapter HTML is loaded over an http base URL.
        settings.allowFileAccess = true
        addJavascriptInterface(
            NovelReaderWebInterface(
                // RK: logged because the chrome has been seen stopping responding to taps while the
                // page still scrolled, which means touch reached the WebView. This says whether the
                // tap left `core.js` at all, so the next occurrence is diagnosable rather than a guess.
                onHide = {
                    if (BuildConfig.DEBUG) logcat { "reader: tap-to-toggle reached the host" }
                    mainHandler.post { onToggleMenu() }
                },
                onConsole = { msg -> if (BuildConfig.DEBUG) logcat { msg } },
                // The two carry the same number but mean different things: `progress` is every scroll
                // frame and drives the chrome, `save` fires at scroll-end and is what gets persisted.
                // Collapsing them would either write on every frame or never write at all.
                onSave = { percent -> onProgressSettled(percent) },
                onProgress = { percent -> mainHandler.post { onProgressChanged(percent) } },
                onTtsMessage = { _, _ -> },
                onReaderReady = {},
                onNavigate = {},
            ),
            JS_INTERFACE_NAME,
        )
    }

    override val view: View
        get() = webView

    // A novel chapter is one vertically scrolling document, so there is no right-to-left shape to report.
    override val isRtl: Boolean
        get() = false

    // Scrolled natively rather than through JS, so the thumb and the text move together while the rail
    // is being dragged. A paged progress is not this medium's unit and is ignored.
    override fun seekTo(progress: ChapterProgress) {
        if (progress !is ChapterProgress.Percent) return
        webView.scrollTo(0, (webView.maxScroll * progress.fraction).roundToInt())
    }

    // Nothing to do: a step reloads the document, which starts at that chapter's own stored position.
    override fun onChapterStepped() = Unit

    override fun destroy() {
        webView.stopLoading()
        // The bridge captures the host and is called off the main thread, so drop it before teardown.
        // destroy() on an attached WebView is undefined and pins the hierarchy, hence the detach.
        webView.removeJavascriptInterface(JS_INTERFACE_NAME)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!volumeKeysEnabled || !isVolumeKey) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != volumeKeysInverted
            val fraction = volumeKeyScrollFraction.coerceIn(0.1f, 1f)
            scrollByFraction(if (forward) fraction else -fraction)
        }
        // Consume the key-up too, so the system volume UI never shows during a press.
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * The document is assembled here rather than by the host, because it is this renderer's own
     * format: Material colours come off [context], which must be the Activity carrying the user's app
     * theme (see `resolveReaderThemeColors`), and the cutout inset is a CSS variable only this
     * document has.
     */
    override fun load(
        chapter: NovelReaderViewModel.LoadedChapter,
        hasPrevious: Boolean,
        hasNext: Boolean,
        settings: NovelReaderSettings,
    ) {
        val html = buildReaderHtml(
            chapterHtml = chapter.html,
            chapterName = chapter.title,
            progressPercent = chapter.progressPercent,
            hasPrev = hasPrevious,
            hasNext = hasNext,
            settings = settings,
            colors = context.resolveReaderThemeColors(),
            statusBarHeightPx = statusBarHeightPx(),
            debug = BuildConfig.DEBUG,
        )
        // The document is built with these, so a later push of the same general block is a no-op.
        lastGeneralSettings = generalSettingsJson(settings).toString()
        // Only trust an http(s) base URL. The plugin controls the site URL, and with allowFileAccess on
        // a file:// base would hand the chapter document a file origin.
        val safeBaseUrl = chapter.baseUrl
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        webView.loadDataWithBaseURL(safeBaseUrl, html, "text/html", "UTF-8", null)
    }

    /**
     * Pushes changed display settings into the live document, so a size or colour change reflows in
     * place rather than waiting for the next chapter.
     *
     * The display block is reassigned freely, since its watchers only rewrite CSS variables. The
     * general block is reassigned only when it actually differs, because a `core.js` watcher rebuilds
     * the chapter DOM on any change to it.
     */
    override fun applySettings(settings: NovelReaderSettings) {
        val generalJson = generalSettingsJson(settings).toString()
        val pushGeneral = generalJson != lastGeneralSettings
        if (pushGeneral) lastGeneralSettings = generalJson
        val script = buildString {
            append("if (window.reader) { reader.readerSettings.val = ")
            append(readerSettingsJson(settings).toString()).append(';')
            if (pushGeneral) append(" reader.generalSettings.val = ").append(generalJson).append(';')
            append(" }")
        }
        webView.evaluateJavascript(script, null)
    }

    /** Smooth-scrolls the viewport by a signed fraction (positive = forward), reusing the WebView's own
     *  smooth scroll so a volume press feels like tap-to-scroll. */
    private fun scrollByFraction(fraction: Float) {
        webView.evaluateJavascript(
            "window.scrollBy({ top: window.innerHeight * $fraction, behavior: 'smooth' });",
            null,
        )
    }

    private companion object {
        const val JS_INTERFACE_NAME = "NativeReader"
    }
}

/** Exposes the vertical scroll range, which `WebView` keeps protected, so a scrub can land natively. */
@SuppressLint("ViewConstructor")
private class ProgressWebView(context: Context) : WebView(context) {
    val maxScroll: Int
        get() = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
}
