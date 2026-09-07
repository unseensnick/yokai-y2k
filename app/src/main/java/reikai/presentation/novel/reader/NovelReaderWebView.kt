package reikai.presentation.novel.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.logcat
import mihon.app.di.appGraph
import kotlin.math.roundToInt

/**
 * Renders a novel chapter's text in a WebView using the bundled `index.css` and `core.js`. The
 * reader's text canvas ONLY: the toolbar, prev/next and settings chrome are Compose
 * ([NovelReaderScreen]), and a center tap posts a `hide` message over the native bridge, firing
 * [onToggleMenu]. [settings] changes push live through `reader.readerSettings.val` with no reload, so
 * font, spacing, alignment, padding and theme update in place; the document is rebuilt only when the
 * chapter [html] or the app [MaterialTheme] colors change.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelReaderWebView(
    html: String,
    baseUrl: String?,
    settings: NovelReaderSettings,
    chapterTitle: String,
    initialProgressPercent: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onToggleMenu: () -> Unit,
    onSaveProgress: (Int) -> Unit,
    onNavigate: (forward: Boolean) -> Unit,
    autoScrollActive: Boolean,
    autoScrollSpeed: Float,
    onScrollHandleReady: ((Int) -> Unit) -> Unit,
    onScrollByFractionReady: ((Float) -> Unit) -> Unit,
    onProgressChanged: (Int) -> Unit,
    ttsController: NovelTtsController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Top padding so the first line clears the display cutout (the punch-hole otherwise overlaps text
    // in immersive mode). The WebView viewport is initial-scale=1, so its CSS px == dp: convert the
    // cutout inset from device px to dp. It's constant across the chrome show/hide toggle, so it
    // won't trigger a WebView reload.
    val density = LocalDensity.current
    val topInsetDp = with(density) { WindowInsets.displayCutout.getTop(density).toDp() }.value.roundToInt()
    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) {
        ReaderThemeColors(
            primary = colorScheme.primary.toCssHex(),
            onPrimary = colorScheme.onPrimary.toCssHex(),
            secondary = colorScheme.secondary.toCssHex(),
            onSecondary = colorScheme.onSecondary.toCssHex(),
            tertiary = colorScheme.tertiary.toCssHex(),
            onTertiary = colorScheme.onTertiary.toCssHex(),
            surface = colorScheme.surface.toCssHex(),
            surface09 = colorScheme.surface.toCssRgba(0.9f),
            onSurface = colorScheme.onSurface.toCssHex(),
            surfaceVariant = colorScheme.surfaceVariant.toCssHex(),
            onSurfaceVariant = colorScheme.onSurfaceVariant.toCssHex(),
            outline = colorScheme.outline.toCssHex(),
            rippleColor = colorScheme.onSurface.toCssHex(),
        )
    }

    // Keyed on chapter + app theme only (NOT settings): settings changes push live instead of
    // reloading. The initial document bakes in the settings present at build time.
    val currentSettings = rememberUpdatedState(settings)
    val onToggle = rememberUpdatedState(onToggleMenu)
    val onSave = rememberUpdatedState(onSaveProgress)
    val onProgressLive = rememberUpdatedState(onProgressChanged)
    val onNav = rememberUpdatedState(onNavigate)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // Held so the navigation policy can tell a footnote jump from the chapter trying to leave.
    val loadedBaseUrl = remember { mutableStateOf<String?>(null) }
    val webView = remember {
        ProgressWebView(context).apply {
            setDefaultSettings()
            webViewClient = NovelChapterNavigationClient(context) { loadedBaseUrl.value }
            // file:///android_asset bundled CSS/JS + fonts. The dangerous universal/file-from-file
            // access flags stay off (security): the chapter HTML is loaded over an http base URL.
            this.settings.allowFileAccess = true
            addJavascriptInterface(
                NovelReaderWebInterface(
                    onHide = { mainHandler.post { onToggle.value() } },
                    onConsole = { msg -> if (BuildConfig.DEBUG) logcat { msg } },
                    onSave = { percent -> mainHandler.post { onSave.value(percent) } },
                    onProgress = { percent -> mainHandler.post { onProgressLive.value(percent) } },
                    onTtsMessage = { type, json -> ttsController.onWebMessage(type, json) },
                    onReaderReady = { mainHandler.post { ttsController.onReaderReady() } },
                    onNavigate = { forward -> mainHandler.post { onNav.value(forward) } },
                ),
                "NativeReader",
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    // Let the TTS controller drive core.js (tts.start / next / pause / stop) on the main thread.
    DisposableEffect(ttsController, webView) {
        ttsController.setEvalJs { js -> mainHandler.post { webView.evaluateJavascript(js, null) } }
        onDispose { ttsController.clearEvalJs() }
    }

    // Expose a scroll-to-percent handle for the vertical seekbar. Scrolls the WebView natively (the
    // seekbar's onValueChange is already on the main thread), so scrubbing is instant, no JS round-trip.
    DisposableEffect(webView) {
        onScrollHandleReady { percent ->
            webView.scrollTo(0, (webView.maxScroll * percent / 100f).roundToInt())
        }
        onDispose {}
    }

    // Expose a scroll-by-fraction handle for hardware volume-key navigation. Smooth-scrolls the viewport
    // by a signed fraction (positive = forward/down), reusing the WebView's own smooth scroll so it
    // matches tap-to-scroll's feel. Invoked on the main thread from the host window's key dispatch.
    DisposableEffect(webView) {
        onScrollByFractionReady { fraction ->
            webView.evaluateJavascript(
                "window.scrollBy({ top: window.innerHeight * $fraction, behavior: 'smooth' });",
                null,
            )
        }
        onDispose {}
    }

    // Auto-scroll: start/stop the injected scroller. Active only while enabled and the chrome is hidden.
    LaunchedEffect(autoScrollActive, autoScrollSpeed) {
        val js = if (autoScrollActive) {
            "if (window.reikaiAutoScroll) reikaiAutoScroll.start($autoScrollSpeed);"
        } else {
            "if (window.reikaiAutoScroll) reikaiAutoScroll.stop();"
        }
        webView.evaluateJavascript(js, null)
    }

    // Keyed on chapter + app theme only (NOT settings): settings changes push live instead of
    // reloading, and the document bakes in whatever was set when it was built. The build is off the
    // main thread because a downloaded chapter has its images inlined, so the string runs to megabytes.
    LaunchedEffect(html, themeColors, chapterTitle, initialProgressPercent, hasPrev, hasNext, topInsetDp, baseUrl) {
        // Off the main thread with the build: resolving it copies the file out of the user's folder.
        val fontUrl = withContext(Dispatchers.IO) {
            context.appGraph.novelFontManager.webUrl(currentSettings.value.fontFamily)
        }
        val document = withContext(Dispatchers.Default) {
            buildReaderHtml(
                chapterHtml = html,
                chapterName = chapterTitle,
                progressPercent = initialProgressPercent,
                hasPrev = hasPrev,
                hasNext = hasNext,
                settings = currentSettings.value,
                colors = themeColors,
                statusBarHeightPx = topInsetDp,
                debug = BuildConfig.DEBUG,
                customFontUrl = fontUrl,
            )
        }
        // Only trust an http(s) base URL. src.site is plugin-controlled, and with allowFileAccess on
        // a file:// base would give the chapter document a file origin; pin the scheme so the safety
        // doesn't rest solely on the file-from-file WebView flags staying default-off.
        val safeBaseUrl = baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        loadedBaseUrl.value = safeBaseUrl
        webView.loadDataWithBaseURL(safeBaseUrl, document, "text/html", "UTF-8", null)
    }

    // Push settings live once the page is up (guarded so it no-ops before the reader exists). The
    // display block (incl. tts rate/pitch) reassigns freely: its watchers only update CSS variables.
    // The general block (TTSEnable, bionic, remove-spacing) is reassigned only when it actually
    // changes, because a `core.js` watcher rebuilds the chapter DOM on any generalSettings change
    // (intended for bionic/spacing; on a no-op rate/pitch change it would needlessly wipe the
    // read-aloud highlight).
    val lastGeneral = remember { mutableStateOf(generalSettingsJson(settings).toString()) }
    LaunchedEffect(settings) {
        val readerJson = readerSettingsJson(settings).toString()
        val generalJson = generalSettingsJson(settings).toString()
        val pushGeneral = generalJson != lastGeneral.value
        if (pushGeneral) lastGeneral.value = generalJson
        val script = buildString {
            // Outside the reader guard: the margins, indent and spacing reach the page through the
            // document's own variables, which `core.js` knows nothing about.
            append(readerCssVariablesScript(settings))
            append("if (window.reader) { reader.readerSettings.val = ").append(readerJson).append(';')
            if (pushGeneral) append(" reader.generalSettings.val = ").append(generalJson).append(';')
            append(" }")
        }
        webView.evaluateJavascript(script, null)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** WebView that exposes its vertical scroll range so the progress seekbar can scrub it natively
 *  (instant, no JS round-trip), the way the manga reader scrolls its view. */
@SuppressLint("ViewConstructor")
private class ProgressWebView(context: Context) : WebView(context) {
    val maxScroll: Int
        get() = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
}

private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

private fun Color.toCssRgba(alpha: Float): String {
    val argb = toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r, $g, $b, $alpha)"
}
