package reikai.presentation.novel.reader

import org.json.JSONArray
import org.json.JSONObject

/** Asset root for the bundled LNReader web layer (CSS/JS copied verbatim from lnreader-main). */
private const val ASSET_BASE = "file:///android_asset/lnreader-web"

/** Material theme colors the LNReader stylesheet expects as `--theme-*` CSS variables. Built from
 *  the app's `MaterialTheme.colorScheme` at the call site so the reading surface (links, selection)
 *  matches the app theme. */
data class ReaderThemeColors(
    val primary: String,
    val onPrimary: String,
    val secondary: String,
    val onSecondary: String,
    val tertiary: String,
    val onTertiary: String,
    val surface: String,
    val surface09: String,
    val onSurface: String,
    val surfaceVariant: String,
    val onSurfaceVariant: String,
    val outline: String,
    val rippleColor: String,
)

/**
 * Build the reader document for a single chapter: the `#LNReader-chapter` scaffold, the `:root`
 * settings and theme CSS variables, the injected `initialReaderConfig`, and the bundled `index.css`
 * plus `core.js`. The in-page chrome (`index.js` ToolWrapper, scrollbar, buttons) and its CSS are
 * deliberately NOT loaded: every piece of chrome is Compose, and only the text canvas lives in the
 * WebView. The native bridge replaces upstream's react-native-webview `postMessage` with a shim
 * forwarding to `NativeReader`, so the vendored `core.js` stays byte-identical to upstream.
 */
fun buildReaderHtml(
    chapterHtml: String,
    chapterName: String,
    progressPercent: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    settings: NovelReaderSettings,
    colors: ReaderThemeColors,
    statusBarHeightPx: Int,
    debug: Boolean,
): String {
    val readerSettings = readerSettingsJson(settings)
    val generalSettings = generalSettingsJson(settings)

    val config = JSONObject().apply {
        put("readerSettings", readerSettings)
        put("chapterGeneralSettings", generalSettings)
        put("novel", JSONObject.NULL)
        put(
            "chapter",
            JSONObject().apply {
                put("name", chapterName)
                put("progress", progressPercent)
            },
        )
        put("nextChapter", if (hasNext) JSONObject().apply { put("name", "") } else JSONObject.NULL)
        put("prevChapter", if (hasPrev) JSONObject().apply { put("name", "") } else JSONObject.NULL)
        put("batteryLevel", 1.0)
        put("autoSaveInterval", 2222)
        put("DEBUG", debug)
        put(
            "strings",
            JSONObject().apply {
                put("finished", "Finished: ${chapterName.trim()}")
                put("nextChapter", "Next chapter")
                put("noNextChapter", "No next chapter")
            },
        )
    }

    val pageConfig = JSONObject().apply { put("nextChapterScreenVisible", false) }

    return """
        <!DOCTYPE html>
        <html dir="ltr">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
        <link rel="stylesheet" href="$ASSET_BASE/css/index.css">
        <style>
        :root {
          --StatusBar-currentHeight: ${statusBarHeightPx}px;
          --readerSettings-theme: ${settings.backgroundColor};
          --readerSettings-padding: ${settings.padding}px;
          --readerSettings-textSize: ${settings.fontSize}px;
          --readerSettings-textColor: ${settings.textColor};
          --readerSettings-textAlign: ${settings.textAlign};
          --readerSettings-lineHeight: ${settings.lineHeight};
          --readerSettings-fontFamily: ${settings.fontFamily};
          --readerSettings-paragraphIndent: ${settings.paragraphIndent}em;
          --readerSettings-paragraphSpacing: ${settings.paragraphSpacing}em;
          --theme-primary: ${colors.primary};
          --theme-onPrimary: ${colors.onPrimary};
          --theme-secondary: ${colors.secondary};
          --theme-tertiary: ${colors.tertiary};
          --theme-onTertiary: ${colors.onTertiary};
          --theme-onSecondary: ${colors.onSecondary};
          --theme-surface: ${colors.surface};
          --theme-surface-0-9: ${colors.surface09};
          --theme-onSurface: ${colors.onSurface};
          --theme-surfaceVariant: ${colors.surfaceVariant};
          --theme-onSurfaceVariant: ${colors.onSurfaceVariant};
          --theme-outline: ${colors.outline};
          --theme-rippleColor: ${colors.rippleColor};
        }
        /* Spacing is added to the browser's own 1em paragraph margin, which is the gap both readers
           had before this setting existed, so zero leaves the page exactly as it was. */
        p {
          text-indent: var(--readerSettings-paragraphIndent);
          margin-bottom: calc(1em + var(--readerSettings-paragraphSpacing));
        }
        </style>
        </head>
        <body>
        <div class="transition-chapter" style="display: none">${escapeHtml(chapterName)}</div>
        <div id="LNReader-chapter">$chapterHtml</div>
        <div id="reader-ui"></div>
        </body>
        <script>
        window.ReactNativeWebView = { postMessage: function (m) { NativeReader.postMessage(m); } };
        var initialPageReaderConfig = $pageConfig;
        var initialReaderConfig = $config;
        </script>
        <script src="$ASSET_BASE/js/polyfill-onscrollend.js"></script>
        <script src="$ASSET_BASE/js/icons.js"></script>
        <script src="$ASSET_BASE/js/van.js"></script>
        <script src="$ASSET_BASE/js/text-vibe.js"></script>
        <script src="$ASSET_BASE/js/core.js"></script>
        <script>
        // Reikai TTS: start read-aloud from the first paragraph at/below the viewport top (so play
        // reads from where you are, not the chapter top). core.js owns the element list + highlight.
        window.reikaiTtsStart = function () {
          if (!window.tts || !window.reader) return;
          try {
            var els = tts.getAllReadableElements(reader.chapterElement);
            var start = null;
            for (var i = 0; i < els.length; i++) {
              if (els[i].getBoundingClientRect().bottom > 80) { start = els[i]; break; }
            }
            tts.start(start || undefined);
          } catch (e) { tts.start(); }
        };
        // Auto-scroll: a requestAnimationFrame loop that nudges the page down each frame.
        window.reikaiAutoScroll = (function () {
          var raf = null, speed = 0;
          function step() { window.scrollBy(0, speed); raf = requestAnimationFrame(step); }
          return {
            start: function (px) { speed = px; if (!raf) raf = requestAnimationFrame(step); },
            stop: function () { if (raf) { cancelAnimationFrame(raf); raf = null; } },
          };
        })();
        // Live reading percentage on every scroll frame, the way LNReader's footer does. Uses core.js's
        // own layoutHeight/chapterHeight, so it matches the 'save' value exactly (no jump on scroll-end).
        (function () {
          var ticking = false;
          window.addEventListener('scroll', function () {
            if (ticking) return;
            ticking = true;
            requestAnimationFrame(function () {
              ticking = false;
              if (!window.reader || reader.generalSettings.val.pageReader) return;
              var pct = parseInt(((window.scrollY + reader.layoutHeight) / reader.chapterHeight) * 100, 10);
              if (pct < 0) pct = 0; else if (pct > 100) pct = 100;
              if (window.NativeReader) NativeReader.postMessage(JSON.stringify({ type: 'progress', data: pct }));
            });
          }, { passive: true });
        })();
        // Tell native the document is up (drives TTS auto-advance + state reset on chapter change).
        if (window.NativeReader) NativeReader.postMessage(JSON.stringify({ type: 'reikai-ready' }));
        </script>
        </html>
    """.trimIndent()
}

/**
 * The LNReader `chapterGeneralSettings` object `core.js` reads. Scroll mode only: every in-page UI
 * feature `index.js` would render is off (Reikai drives chrome in Compose). [NovelReaderSettings.ttsEnabled]
 * gates `TTSEnable`, which `core.js` watches to stop read-aloud when switched off. Pushed live (like
 * [readerSettingsJson]) so toggling TTS in settings takes effect without a reload.
 */
fun generalSettingsJson(settings: NovelReaderSettings): JSONObject = JSONObject().apply {
    put("keepScreenOn", true)
    put("fullScreenMode", true)
    put("pageReader", false)
    put("swipeGestures", settings.swipeGestures)
    put("showScrollPercentage", false)
    put("useVolumeButtons", false)
    put("volumeButtonsOffset", JSONObject.NULL)
    put("showBatteryAndTime", false)
    put("autoScroll", false)
    put("autoScrollInterval", 10)
    put("autoScrollOffset", JSONObject.NULL)
    put("verticalSeekbar", false)
    put("removeExtraParagraphSpacing", settings.removeExtraSpacing)
    put("bionicReading", settings.bionicReading)
    put("tapToScroll", settings.tapToScroll)
    put("TTSEnable", settings.ttsEnabled)
}

/**
 * The LNReader `readerSettings` object the web layer reads. Used both for the initial
 * [buildReaderHtml] config and for live updates pushed via `reader.readerSettings.val = ...`, so the
 * two stay in sync.
 */
fun readerSettingsJson(settings: NovelReaderSettings): JSONObject = JSONObject().apply {
    put("theme", settings.backgroundColor)
    put("textColor", settings.textColor)
    put("textSize", settings.fontSize)
    put("textAlign", settings.textAlign)
    put("padding", settings.padding)
    put("fontFamily", settings.fontFamily)
    put("lineHeight", settings.lineHeight.toDouble())
    put("customCSS", "")
    put("customJS", "")
    put("customThemes", JSONArray())
    put(
        "tts",
        JSONObject().apply {
            put("rate", settings.ttsRate.toDouble())
            put("pitch", settings.ttsPitch.toDouble())
            put("autoPageAdvance", settings.ttsAutoPageAdvance)
            put("scrollToTop", settings.ttsScrollToTop)
        },
    )
    put("epubLocation", "")
    put("epubUseAppTheme", false)
    put("epubUseCustomCSS", false)
    put("epubUseCustomJS", false)
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
