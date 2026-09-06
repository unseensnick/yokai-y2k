package reikai.presentation.novel.reader

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.appbars.ReaderTopBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.tts.TtsPlayback
import reikai.novel.download.toDownloadState
import reikai.presentation.reader.ReaderActionRow
import reikai.presentation.reader.ReaderChapterListDialog
import reikai.presentation.reader.ReaderChapterRow
import reikai.presentation.reader.ReaderOrientationDialog
import reikai.presentation.reader.ReaderTextSizeDialog
import reikai.presentation.reader.ReaderThemeDialog
import reikai.presentation.reader.VerticalReaderRail
import reikai.presentation.reader.readerBarEnter
import reikai.presentation.reader.readerBarExit
import reikai.presentation.reader.readerChromeColor
import tachiyomi.core.common.util.lang.launchNonCancellable
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/**
 * Light-novel reader: a WebView text canvas with Compose chrome. A single tap hides/shows the top +
 * bottom bars *and* the system bars together (immersive), so the reading area is unobstructed. The
 * reader theme follows the app by default ("Auto"); sepia/dark/etc. apply when chosen. Holds only
 * serializable args (Voyager serializes the screen into saved state).
 */
class NovelReaderScreen(
    private val novelId: Long,
    private val initialChapterId: Long,
    private val sourceScoped: Boolean = false,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { NovelReaderScreenModel(novelId, initialChapterId, sourceScoped) }
        val state by screenModel.state.collectAsState()
        val rawSettings by screenModel.settings.collectAsState()
        val overlay by screenModel.overlaySettings.collectAsState()

        // Resolve "Auto" into the effective light/dark preset; otherwise use the chosen preset as-is.
        val systemDark = isSystemInDarkTheme()
        val settings = remember(rawSettings, systemDark) { rawSettings.resolvedForSystemTheme(systemDark) }

        var menuVisible by rememberSaveable { mutableStateOf(true) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var chaptersOpen by rememberSaveable { mutableStateOf(false) }
        var orientationOpen by rememberSaveable { mutableStateOf(false) }
        var themeOpen by rememberSaveable { mutableStateOf(false) }
        var textSizeOpen by rememberSaveable { mutableStateOf(false) }

        // Translucent chrome background matching the manga reader's toolbars (and the seekbar pill).
        val chromeColor = readerChromeColor()

        // Reading progress (whole percent) for the vertical seekbar, and a handle the WebView registers
        // so the seekbar can scrub. Auto-scroll runs only while reading (chrome hidden).
        var progressPercent by remember { mutableStateOf(0) }
        var scrollToPercent by remember { mutableStateOf<((Int) -> Unit)?>(null) }
        // A signed-fraction scroll handle the WebView registers, driven by hardware volume-key navigation.
        var scrollByFraction by remember { mutableStateOf<((Float) -> Unit)?>(null) }
        val autoScrollActive = settings.autoScroll && !menuVisible

        // Web actions (open in WebView / browser, share the chapter URL), shared by the top bar overflow
        // and the bottom action row. Null until a chapter with a source URL is loaded.
        val loadedState = state as? NovelReaderState.Loaded
        val webUrl = loadedState?.webUrl
        val webTitle = loadedState?.chapterTitle
        val onOpenInWebView: (() -> Unit)? = webUrl?.let { url ->
            { navigator.push(WebViewScreen(url = url, initialTitle = webTitle, sourceId = null)) }
        }
        val onOpenInBrowser: (() -> Unit)? = webUrl?.let { url -> { context.openInBrowser(url) } }
        val onShare: (() -> Unit)? = webUrl?.let { url ->
            {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        },
                        null,
                    ),
                )
            }
        }
        val bottomButtons by screenModel.bottomButtons.collectAsState()

        // Immersive: hide the system bars while reading, reveal them with the chrome on tap. Restore
        // them when leaving the reader so the rest of the app is unaffected.
        DisposableEffect(menuVisible) {
            val window = (context as? Activity)?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (menuVisible) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {}
        }
        DisposableEffect(Unit) {
            onDispose {
                (context as? Activity)?.window?.let {
                    WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        // Hold the screen awake while reading when the pref is on; always clear the flag on leave so
        // the rest of the app keeps its normal timeout (the manga reader does this in ReaderActivity).
        DisposableEffect(settings.keepScreenOn) {
            val window = (context as? Activity)?.window
            if (settings.keepScreenOn) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        // Custom reader brightness: positive sets the window brightness, negative dims below the system
        // minimum via the overlay (handled by ReaderContentOverlay), 0 / off follows the system. Restore
        // system brightness on leave (the manga reader does this in ReaderActivity).
        DisposableEffect(overlay.customBrightness, overlay.customBrightnessValue) {
            (context as? Activity)?.window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = when {
                        !overlay.customBrightness || overlay.customBrightnessValue == 0 ->
                            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        overlay.customBrightnessValue > 0 -> overlay.customBrightnessValue / 100f
                        else -> 0.01f
                    }
                }
            }
            onDispose {
                (context as? Activity)?.window?.let { w ->
                    w.attributes = w.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }

        // Lock the screen to this novel's resolved orientation while reading: apply on every change,
        // and restore free rotation once on leave via a separate DisposableEffect(Unit) (the same
        // idiom as the system-bars restore above). MainActivity has no fixed orientation, so
        // UNSPECIFIED is the correct restore. NOTE: Android 16 (targetSdk 36) ignores app orientation
        // requests on large screens (foldables unfolded, tablets), so this is a no-op there by OS
        // policy; it takes effect on phones and folded covers.
        LaunchedEffect(settings.resolvedOrientation) {
            (context as? Activity)?.requestedOrientation =
                ReaderOrientation.fromPreference(settings.resolvedOrientation).flag
        }
        DisposableEffect(Unit) {
            onDispose { (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }

        // Hardware volume-key navigation: register a handler on the host window while the reader is on
        // screen and clear it on dispose, so volume keys scroll the chapter here and behave normally
        // everywhere else. The single registration reads live state via rememberUpdatedState. Volume
        // down scrolls forward (up = back), swapped by the invert pref; while the chrome is up or the
        // pref is off the handler declines the key so the system volume UI works as usual.
        val volumeEnabled = rememberUpdatedState(settings.useVolumeButtons)
        val volumeInverted = rememberUpdatedState(settings.volumeButtonsInverted)
        val volumeFraction = rememberUpdatedState(settings.volumeButtonsFraction)
        val volumeMenuVisible = rememberUpdatedState(menuVisible)
        val volumeScrollBy = rememberUpdatedState(scrollByFraction)
        DisposableEffect(context) {
            val host = context as? NovelVolumeKeyHost
            host?.novelVolumeKeyHandler = handler@{ event ->
                if (!volumeEnabled.value || volumeMenuVisible.value) return@handler false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != volumeInverted.value
                    val fraction = volumeFraction.value.coerceIn(0.1f, 1f)
                    volumeScrollBy.value?.invoke(if (forward) fraction else -fraction)
                }
                // Swallow both down and up so the system volume UI never shows during a press.
                true
            }
            onDispose { host?.novelVolumeKeyHandler = null }
        }

        // Record reading history when the reader is backgrounded (ON_PAUSE) or left (screen pop): the
        // novel twin of ReaderActivity.onPause -> updateHistory. The host Activity's lifecycleScope is
        // used (not this screen's, which is cancelled on pop) and the write is non-cancellable so it
        // survives teardown. Chapter switches are already recorded in the ScreenModel's goTo.
        val activity = context as? ComponentActivity
        DisposableEffect(activity) {
            val observer = object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    activity?.lifecycleScope?.launchNonCancellable { screenModel.updateHistory() }
                }
            }
            activity?.lifecycle?.addObserver(observer)
            onDispose {
                activity?.lifecycle?.removeObserver(observer)
                activity?.lifecycleScope?.launchNonCancellable { screenModel.updateHistory() }
            }
        }

        val title = when (val s = state) {
            is NovelReaderState.Loaded -> s.chapterTitle
            is NovelReaderState.Failed -> "Error"
            NovelReaderState.Loading -> "Loading…"
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                NovelReaderState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is NovelReaderState.Failed -> TextButton(
                    onClick = { screenModel.retry() },
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text("${s.message}\n\nTap to retry")
                }
                is NovelReaderState.Loaded -> {
                    // Seed the seekbar from the chapter's resume position on (re)load.
                    LaunchedEffect(s.chapterTitle, s.initialProgressPercent) {
                        progressPercent = s.initialProgressPercent
                    }
                    NovelReaderWebView(
                        html = s.html,
                        baseUrl = s.baseUrl,
                        settings = settings,
                        chapterTitle = s.chapterTitle,
                        initialProgressPercent = s.initialProgressPercent,
                        hasPrev = s.hasPrev,
                        hasNext = s.hasNext,
                        onToggleMenu = { menuVisible = !menuVisible },
                        onSaveProgress = { pct ->
                            // core.js's save formula can exceed 100 at the very bottom (screen height >
                            // the content viewport), so clamp before display and persistence.
                            val clamped = pct.coerceIn(0, 100)
                            progressPercent = clamped
                            screenModel.saveProgress(clamped)
                        },
                        onNavigate = { forward -> if (forward) screenModel.next() else screenModel.prev() },
                        autoScrollActive = autoScrollActive,
                        autoScrollSpeed = settings.autoScrollSpeed,
                        onScrollHandleReady = { scrollToPercent = it },
                        onScrollByFractionReady = { scrollByFraction = it },
                        onProgressChanged = { progressPercent = it.coerceIn(0, 100) },
                        ttsController = screenModel.ttsController,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Brightness dim + colour-filter tint, above the WebView but below the chrome so the
            // toolbars stay crisp. Draw-only (no pointer input), so taps/scroll pass to the WebView.
            ReaderContentOverlay(
                brightness = if (overlay.customBrightness) overlay.customBrightnessValue else 0,
                color = overlay.colorFilterValue.takeIf { overlay.colorFilter },
                colorBlendMode = ColorFilterMode.getOrNull(overlay.colorFilterMode)?.second,
            )

            // Always-on reading percentage while reading (chrome hidden). LNReader's reader-footer look:
            // a full-width strip in the reader background, so text scrolls under it and the percentage
            // always sits on a clean background. Hidden with the chrome, when the vertical rail (which
            // also shows the percent) takes over. Updated live per scroll frame via onProgressChanged.
            if (!menuVisible && settings.showProgressPercentage) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(AndroidColor.parseColor(settings.backgroundColor)))
                        .navigationBarsPadding()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$progressPercent%",
                        color = Color(AndroidColor.parseColor(settings.textColor)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = readerBarEnter(fromBottom = false),
                exit = readerBarExit(fromBottom = false),
            ) {
                // Shared reader top bar (same component the manga reader uses): back, title +
                // chapter subtitle, bookmark, and a text-only overflow (WebView / browser / share).
                ReaderTopBar(
                    modifier = Modifier.background(chromeColor),
                    mangaTitle = title,
                    chapterTitle = webTitle,
                    navigateUp = { navigator.pop() },
                    bookmarked = loadedState?.bookmarked ?: false,
                    onToggleBookmarked = { screenModel.toggleBookmark() },
                    onOpenInWebView = onOpenInWebView,
                    onOpenInBrowser = onOpenInBrowser,
                    onShare = onShare,
                )
            }

            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = readerBarEnter(fromBottom = true),
                exit = readerBarExit(fromBottom = true),
            ) {
                BottomAppBar(
                    containerColor = chromeColor,
                    windowInsets = WindowInsets.navigationBars,
                ) {
                    // Shared reader action row (same component the manga reader delegates to). Manga-only
                    // buttons stay null; the novel-only toggles (auto-scroll / keep-screen-on / bionic)
                    // and web actions are wired here. Which appear is driven by the novel's own pref.
                    ReaderActionRow(
                        modifier = Modifier.fillMaxWidth(),
                        enabledButtons = bottomButtons,
                        onClickChapterList = { chaptersOpen = true },
                        onClickWebView = onOpenInWebView,
                        onClickBrowser = onOpenInBrowser,
                        onClickShare = onShare,
                        orientation = ReaderOrientation.fromPreference(settings.orientation),
                        onClickOrientation = { orientationOpen = true },
                        onClickSettings = { settingsOpen = true },
                        autoScrollActive = settings.autoScroll,
                        onClickAutoScroll = { screenModel.setAutoScroll(!settings.autoScroll) },
                        keepScreenOn = settings.keepScreenOn,
                        onClickKeepScreenOn = { screenModel.setKeepScreenOn(!settings.keepScreenOn) },
                        bionicActive = settings.bionicReading,
                        onClickBionic = { screenModel.setBionicReading(!settings.bionicReading) },
                        onClickTheme = { themeOpen = true },
                        onClickTextSize = { textSizeOpen = true },
                    )
                }
            }

            // Vertical progress rail on the reader edge (shared VerticalReaderRail, same component the
            // manga reader uses): chapter skip buttons flank the scroll-percent slider. Shown whenever
            // the chrome is visible, since it is the novel reader's only chapter navigator. Constrained
            // to the area between the top and bottom bars (reserving the system bars + the app-bar
            // heights) so the height fraction sizes against that region, matching the manga rail, which
            // lives in the weighted space between its bars, instead of covering the chrome at 100%.
            AnimatedVisibility(
                visible = menuVisible,
                modifier = Modifier
                    .align(if (settings.railOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(top = 64.dp, bottom = 80.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val loaded = state as? NovelReaderState.Loaded
                val railInteractionSource = remember { MutableInteractionSource() }
                val railState = rememberSliderState(
                    value = progressPercent.toFloat().coerceIn(0f, SEEKBAR_MAX),
                    steps = SEEKBAR_STEPS,
                    valueRange = 0f..100f,
                )
                railState.value = progressPercent.toFloat().coerceIn(0f, SEEKBAR_MAX)
                railState.onValueChange = { v ->
                    progressPercent = v.roundToInt()
                    scrollToPercent?.invoke(v.roundToInt())
                }
                // Anchor to the bottom of the inter-bar region, matching the manga rail
                // (ReaderAppBars uses Alignment.BottomCenter).
                Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    VerticalReaderRail(
                        modifier = Modifier
                            .fillMaxHeight((settings.railHeightPercent / 100f).coerceIn(0f, 1f))
                            .padding(horizontal = 8.dp),
                        sliderState = railState,
                        topLabel = progressPercent.toString(),
                        bottomLabel = "100",
                        showSlider = true,
                        onPreviousChapter = { screenModel.prev() },
                        enabledPrevious = loaded?.hasPrev == true,
                        onNextChapter = { screenModel.next() },
                        enabledNext = loaded?.hasNext == true,
                        interactionSource = railInteractionSource,
                    )
                }
            }

            // Floating read-aloud puck (shown only when TTS is on). Drawn last so it overlays the
            // text + chrome; empty areas of its container pass taps through to the WebView.
            if (settings.ttsEnabled) {
                val ttsPlayback by screenModel.ttsController.playback.collectAsState()
                val initialPos = remember { screenModel.ttsButtonPosition() }
                NovelTtsFloatingButton(
                    playing = ttsPlayback == TtsPlayback.Playing,
                    initialX = initialPos.first,
                    initialY = initialPos.second,
                    onToggle = { screenModel.ttsController.toggle() },
                    onStop = { screenModel.ttsController.stop() },
                    onMoved = { x, y -> screenModel.setTtsButtonPosition(x, y) },
                )
            }
        }

        if (settingsOpen) {
            NovelReaderSettingsSheet(
                settings = rawSettings,
                onFontSize = screenModel::setFontSize,
                onLineHeight = screenModel::setLineHeight,
                onTextAlign = screenModel::setTextAlign,
                onPadding = screenModel::setPadding,
                onFontFamily = screenModel::setFontFamily,
                onFollowSystem = screenModel::setFollowSystemTheme,
                onPreset = screenModel::setThemePreset,
                onKeepScreenOn = screenModel::setKeepScreenOn,
                onOrientation = screenModel::setOrientation,
                onTtsEnabled = screenModel::setTtsEnabled,
                onTtsRate = screenModel::setTtsRate,
                onTtsPitch = screenModel::setTtsPitch,
                onTtsAutoPageAdvance = screenModel::setTtsAutoPageAdvance,
                onTtsScrollToTop = screenModel::setTtsScrollToTop,
                onTtsEngine = screenModel::setTtsEngine,
                onTtsVoice = screenModel::setTtsVoice,
                onTtsLanguages = screenModel::setTtsLanguages,
                ttsEngines = { screenModel.ttsController.availableEngines() },
                ttsVoices = { screenModel.ttsController.availableVoices() },
                currentTtsEngine = screenModel.ttsEnginePackage(),
                currentTtsVoice = screenModel.ttsVoiceName(),
                currentTtsLanguages = screenModel.ttsLanguages(),
                onBionicReading = screenModel::setBionicReading,
                onRemoveExtraSpacing = screenModel::setRemoveExtraSpacing,
                onTapToScroll = screenModel::setTapToScroll,
                onSwipeGestures = screenModel::setSwipeGestures,
                onAutoScroll = screenModel::setAutoScroll,
                onAutoScrollSpeed = screenModel::setAutoScrollSpeed,
                overlay = overlay,
                onCustomBrightness = screenModel::setCustomBrightness,
                onCustomBrightnessValue = screenModel::setCustomBrightnessValue,
                onColorFilter = screenModel::setColorFilter,
                onColorFilterValue = screenModel::setColorFilterValue,
                onColorFilterMode = screenModel::setColorFilterMode,
                onDismiss = { settingsOpen = false },
            )
        }

        if (chaptersOpen) {
            var chapters by remember { mutableStateOf<List<NovelChapter>?>(null) }
            var sourceNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
            var downloadedChapterIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            val downloadQueue by screenModel.downloadQueue.collectAsState()
            LaunchedEffect(Unit) {
                val list = screenModel.chapterList()
                sourceNames = screenModel.chapterSourceNames(list)
                chapters = list
            }
            // Re-derive the downloaded set whenever the queue changes (a finished download leaves it), so a
            // chapter downloaded from the sheet flips to "downloaded" without reopening, like the manga sheet
            // (whose row re-checks the disk on every recomposition the queue change triggers).
            LaunchedEffect(chapters, downloadQueue) {
                chapters?.let { downloadedChapterIds = screenModel.downloadedChapterIds(it) }
            }
            // The shared reader sheet, over rows this screen maps because its model predates the
            // provider seam. Nothing here decides how a row looks.
            val rows = remember(chapters, downloadQueue, downloadedChapterIds) {
                chapters.orEmpty().map { ch ->
                    ReaderChapterRow(
                        id = ch.id,
                        title = ch.name,
                        subtitle = sourceNames[ch.novelId],
                        dateUpload = ch.dateUpload,
                        readProgress = (ch.lastTextProgress / 100L).toInt().takeIf { it > 0 }?.let { "$it%" },
                        read = ch.read,
                        bookmark = ch.bookmark,
                        downloadState = when {
                            downloadQueue.any { it.chapterId == ch.id } ->
                                downloadQueue.first { it.chapterId == ch.id }.state.toDownloadState()
                            ch.id in downloadedChapterIds -> Download.State.DOWNLOADED
                            else -> Download.State.NOT_DOWNLOADED
                        },
                        downloadProgress = 0,
                    )
                }
            }
            val chaptersById = remember(chapters) { chapters.orEmpty().associateBy { it.id } }
            ReaderChapterListDialog(
                onDismissRequest = { chaptersOpen = false },
                rows = rows,
                currentChapterId = screenModel.currentChapterId(),
                chapterSwipeStartAction = screenModel.chapterSwipeStartAction,
                chapterSwipeEndAction = screenModel.chapterSwipeEndAction,
                onClickChapter = { id ->
                    chaptersOpen = false
                    screenModel.goToChapter(id)
                },
                onMarkRead = { id, read -> chaptersById[id]?.let { screenModel.setChapterReadStatus(it, read) } },
                onBookmark = { id, bookmarked -> screenModel.setChapterBookmark(id, bookmarked) },
                onDownloadAction = { id, action ->
                    chaptersById[id]?.let { screenModel.onChapterDownloadAction(it, action) }
                },
            )
        }

        if (orientationOpen) {
            ReaderOrientationDialog(
                currentOrientation = settings.orientation,
                onChange = screenModel::setOrientation,
                onDismiss = { orientationOpen = false },
            )
        }

        if (themeOpen) {
            ReaderThemeDialog(
                followSystemTheme = rawSettings.followSystemTheme,
                backgroundColor = rawSettings.backgroundColor,
                onFollowSystem = screenModel::setFollowSystemTheme,
                onPreset = screenModel::setThemePreset,
                onDismiss = { themeOpen = false },
            )
        }

        if (textSizeOpen) {
            ReaderTextSizeDialog(
                fontSize = settings.fontSize,
                onFontSize = screenModel::setFontSize,
                onDismiss = { textSizeOpen = false },
            )
        }
    }
}

private const val SEEKBAR_STEPS = 33
private const val SEEKBAR_MAX = 99f
