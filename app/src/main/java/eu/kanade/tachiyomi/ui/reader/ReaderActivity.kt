package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.transition.platform.MaterialContainerTransform
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelPreferences
import reikai.presentation.novel.reader.resolvedForSystemTheme
import reikai.presentation.reader.MangaReaderProvider
import reikai.presentation.reader.MangaViewport
import reikai.presentation.reader.NovelReaderProvider
import reikai.presentation.reader.NovelReaderViewModel
import reikai.presentation.reader.ReaderChapterListDialog
import reikai.presentation.reader.ReaderDialog
import reikai.presentation.reader.ReaderEngine
import reikai.presentation.reader.ReaderLoadState
import reikai.presentation.reader.ReaderOrientationDialog
import reikai.presentation.reader.ReaderTextSizeDialog
import reikai.presentation.reader.ReaderThemeDialog
import reikai.presentation.reader.TextViewport
import reikai.presentation.reader.putEntryId
import reikai.presentation.reader.readEntryId
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.graphics.Color as ComposeColor

class ReaderActivity : BaseActivity() {

    companion object {
        // RK: optional page param jumps the reader to a specific page (gallery page previews); the
        // ViewModel reads it from "launch_page", which is deliberately not the "page_index" key its
        // process-death restore uses. Null keeps Mihon's default behavior.
        // sourceScoped narrows the chapter list to the opened source's own chapters (Updates, a
        // specific source chip); default false = the whole merge group (group scope).
        fun newIntent(
            context: Context,
            mangaId: Long?,
            chapterId: Long?,
            page: Int? = null,
            sourceScoped: Boolean = false,
        ): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                if (page != null) putExtra("launch_page", page)
                if (sourceScoped) putExtra("source_scoped", true)
                // RK: name the entry by type as well, so the host can tell a novel launch from a
                // manga one. The "manga" extra above stays for ReaderViewModel's own saved state.
                if (mangaId != null) putEntryId(EntryId.Manga(mangaId))
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        /**
         * The novel entry into the same host. Deliberately not an overload of [newIntent]: a novel
         * launch writes no `"manga"` extra, so a caller that confuses the two gets a compile error
         * rather than a reader that opens the wrong entry.
         */
        fun newNovelIntent(
            context: Context,
            novelId: Long,
            chapterId: Long,
            sourceScoped: Boolean = false,
        ): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("chapter", chapterId)
                if (sourceScoped) putExtra("source_scoped", true)
                putEntryId(EntryId.Novel(novelId))
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    /**
     * Which entry this launch names, or null when it names none. Falls back to the bare `"manga"`
     * extra so an intent built before the tag existed, a pending notification action for instance,
     * still opens rather than being read as a launch for nothing.
     */
    private fun Intent.entryId(): EntryId? =
        readEntryId() ?: getLongExtra("manga", -1L).takeIf { it != -1L }?.let(EntryId::Manga)

    private val graph: AppGraph by lazy { metroGraph() }

    @Inject private lateinit var readerPreferences: ReaderPreferences

    @Inject private lateinit var preferences: BasePreferences

    // RK -->
    @Inject private lateinit var uiPreferences: UiPreferences

    @Inject private lateinit var novelPreferences: NovelPreferences
    // RK <--

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel> { graph.viewModelFactory }

    // RK --> the engine owns dialog dispatch and the viewport, over a provider per content type.
    // The provider is held here as its own type as well, because building page actions is a manga
    // question the neutral seam does not carry; it is stateless, so the engine surviving a recreate
    // with an earlier instance changes nothing.
    private val mangaProvider by lazy { MangaReaderProvider(viewModel, readerPreferences, graph.downloadManager) }

    // Resolved after viewModel so the provider has a model to wrap. Manual assisted factories are a
    // plain function rather than a ViewModelProvider.Factory, which is what the initializer wraps.

    /**
     * The novel model, resolved only for a novel launch. Reading it on a manga launch would build a
     * model for an entry that does not exist, so everything that touches it goes through
     * [novelSession], which is null for manga.
     */
    private val novelViewModel by viewModels<NovelReaderViewModel> {
        viewModelFactory {
            initializer {
                graph.viewModelFactory
                    .createManuallyAssistedFactory(NovelReaderViewModel.Factory::class)()
                    .create(
                        novelId = intent.entryId()?.rawId ?: -1L,
                        initialChapterId = intent.getLongExtra("chapter", -1L),
                        sourceScoped = intent.getBooleanExtra("source_scoped", false),
                    )
            }
        }
    }

    /** The novel half of the session, or null when this launch is a manga one. */
    private val novelSession: NovelReaderProvider? by lazy {
        (intent.entryId() as? EntryId.Novel)?.let {
            NovelReaderProvider(novelViewModel, novelPreferences)
        }
    }

    val engine by viewModels<ReaderEngine> {
        viewModelFactory {
            initializer {
                graph.viewModelFactory
                    .createManuallyAssistedFactory(ReaderEngine.Factory::class)()
                    .create(novelSession ?: mangaProvider)
            }
        }
    }

    // RK <--
    private var assistUrl: String? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null

    var isScrollingThroughPages = false
        private set

    /** RK: set when the launch named no entry, so teardown does not build a session that never opened. */
    private var launchRejected = false

    /**
     * RK: which viewer implementation is running, for the settings sheet. A manga question, so it
     * unwraps the adapter rather than widening the contract, and it is shared from here rather than
     * from inside the composable, where an abandoned composition would leak the collector.
     */
    private val mangaViewerState by lazy {
        engine.viewport.map { (it as? MangaViewport)?.viewer }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)
    }

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // RK --> asked of the intent rather than of the manga model, which is what lets a launch name a
        // novel, and asked before the overlay is installed, since the overlay renders against a model an
        // unusable launch never fills.
        val launchedEntry = intent.entryId()
        if (launchedEntry == null || intent.getLongExtra("chapter", -1L) == -1L) {
            launchRejected = true
            finish()
            return
        }
        // A novel session installs its viewport here rather than from the manga collector, which is
        // what updateViewer() hangs off and which never fires without a Manga in state.
        novelSession?.let { provider ->
            val viewport = provider.createViewport(this)
            engine.installViewport(viewport)
            updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
            binding.viewerContainer.addView(viewport.view)
            // Asked for rather than cast: which text renderer is running is the provider's choice.
            // A novel viewport that does not answer would render an empty reader in silence, so say so.
            when (viewport) {
                is TextViewport -> loadNovelChapters(provider, viewport)
                else -> logcat(LogPriority.ERROR) { "Novel viewport renders no text: ${viewport::class}" }
            }
            // Manga locks the window from setViewer, deferred behind the shared-element transition;
            // a novel launch runs neither, so it follows its own resolved orientation from here.
            provider.viewModel.settings
                .map { it.resolvedOrientation }
                .distinctUntilChanged()
                .onEach(::setOrientation)
                .launchIn(lifecycleScope)
        }
        // RK <--

        binding.setComposeOverlay()

        NotificationReceiver.dismissNotification(
            this,
            viewModel.mangaId.hashCode(),
            Notifications.ID_NEW_CHAPTERS,
        )

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.initError }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setInitialChapterError)
            .launchIn(lifecycleScope)

        // RK: from the engine, so a novel session gets the spinner and the failure surface too. It
        // had neither: a chapter that failed to load left the previous one on screen in silence.
        engine.loadState
            .onEach { state ->
                setProgressDialog(state is ReaderLoadState.Loading)
                if (state is ReaderLoadState.Failed) setChapterLoadError(state.message)
            }
            .launchIn(lifecycleScope)

        // RK: registration order is load-bearing. This collector is what installs the viewer, and
        // the viewerChapters one below delivers into it. Registered the other way round, setChapters
        // drops the loading indicator and then delivers into a null viewer, leaving a black screen
        // with no spinner and no error. It also rests on init() setting manga before viewerChapters,
        // which nothing enforces.
        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val engineDialog by engine.dialog.collectAsState()
        // RK: the session's own answer, so a novel gets its readout and its own setting decides it.
        val showProgress by engine.showProgress.collectAsState()
        val navigatorState by engine.navigator.collectAsState()
        val settingsViewModel = remember {
            ReaderSettingsViewModel(
                readerState = viewModel.state,
                viewerState = mangaViewerState,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
                preferences = readerPreferences,
                // RK: resolved, so the quick menu highlights the mode actually in use
                resolvedReadingMode = viewModel::getMangaReadingMode,
            )
        }

        // RK -->
        val seedColor = state.manga?.asMangaCover()?.vibrantCoverColor
            ?.takeIf { uiPreferences.themeCoverBased.get() }
            ?.let { ComposeColor(it) }
        TachiyomiTheme(seedColor = seedColor) {
            // RK <--
            Box(modifier = Modifier.fillMaxSize()) {
                // RK: both from the engine. Read off manga's preference and manga's position, the
                // readout never appeared in a novel session and its setting did nothing.
                if (!state.menuVisible && showProgress) {
                    ReaderPageIndicator(
                        progress = navigatorState.progress,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                }

                ContentOverlay(state = state)

                AppBars(state = state)
            }

            val onDismissRequest = engine::dismissDialog
            when (val dialog = engineDialog) {
                // RK -->
                is ReaderDialog.LoadFailed -> {
                    AlertDialog(
                        onDismissRequest = onDismissRequest,
                        title = { Text(stringResource(MR.strings.chapter_load_failed)) },
                        text = dialog.message?.let { { Text(it) } },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onDismissRequest()
                                    engine.retryLoad()
                                },
                            ) {
                                Text(stringResource(MR.strings.action_retry))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = onDismissRequest) {
                                Text(stringResource(MR.strings.action_cancel))
                            }
                        },
                    )
                }
                // RK <--
                is ReaderDialog.Loading -> {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(MR.strings.loading))
                            }
                        },
                    )
                }
                is ReaderDialog.Settings -> {
                    ReaderSettingsDialog(
                        onDismissRequest = onDismissRequest,
                        onShowMenus = { setMenuVisibility(true) },
                        onHideMenus = { setMenuVisibility(false) },
                        viewModel = settingsViewModel,
                    )
                }
                is ReaderDialog.ReadingModeSelect -> {
                    ReadingModeSelectDialog(
                        onDismissRequest = onDismissRequest,
                        viewModel = settingsViewModel,
                        onChange = { stringRes ->
                            menuToggleToast?.cancel()
                            if (!readerPreferences.showReadingMode.get()) {
                                menuToggleToast = toast(stringRes)
                            }
                        },
                    )
                }
                // RK: over the engine, so the flag written belongs to whichever entry is open.
                is ReaderDialog.OrientationSelect -> {
                    ReaderOrientationDialog(
                        currentOrientation = engine.orientation.collectAsState().value,
                        onChange = {
                            engine.setOrientation(it)
                            menuToggleToast?.cancel()
                            menuToggleToast = toast(ReaderOrientation.fromPreference(it).stringRes)
                        },
                        onDismiss = onDismissRequest,
                    )
                }
                is ReaderDialog.PageActions -> {
                    ReaderPageActionsDialog(
                        onDismissRequest = onDismissRequest,
                        onSetAsCover = dialog.actions::setAsCover,
                        onShare = dialog.actions::share,
                        onSave = dialog.actions::save,
                    )
                }
                // RK -->
                is ReaderDialog.TextSize -> {
                    val text by dialog.settings.state.collectAsState(null)
                    text?.let {
                        ReaderTextSizeDialog(
                            fontSize = it.fontSize,
                            onFontSize = dialog.settings::setFontSize,
                            onDismiss = onDismissRequest,
                        )
                    }
                }
                is ReaderDialog.ThemeSelect -> {
                    val text by dialog.settings.state.collectAsState(null)
                    text?.let {
                        ReaderThemeDialog(
                            followSystemTheme = it.followSystemTheme,
                            backgroundColor = it.backgroundColor,
                            onFollowSystem = dialog.settings::followSystemTheme,
                            onPreset = { preset ->
                                dialog.settings.setThemeColors(preset.background, preset.textColor)
                            },
                            onDismiss = onDismissRequest,
                        )
                    }
                }
                // RK: over the session's own chapter list, so a novel gets its chapters here too.
                is ReaderDialog.ChapterList -> {
                    val chapterList = engine.chapterList
                    val rows by chapterList.rows.collectAsState(emptyList())
                    val current by chapterList.currentChapterId.collectAsState(-1L)
                    ReaderChapterListDialog(
                        onDismissRequest = onDismissRequest,
                        rows = rows,
                        currentChapterId = current,
                        chapterSwipeStartAction = viewModel.chapterSwipeStartAction,
                        chapterSwipeEndAction = viewModel.chapterSwipeEndAction,
                        onClickChapter = {
                            chapterList.open(it)
                            onDismissRequest()
                        },
                        onMarkRead = chapterList::setRead,
                        onBookmark = chapterList::setBookmark,
                        onDownloadAction = chapterList::download,
                    )
                }
                // RK <--
                null -> {}
            }
        } // RK: end cover-based theme wrap
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        super.onDestroy()
        // RK: a rejected launch never built the engine, and reading it here would build one (and a model
        // under it) against a store that is already cleared, for a session that never opened.
        if (!launchRejected) engine.destroyViewport()
        config = null
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
    }

    /**
     * Hands each chapter the novel model loads to whichever text renderer is installed. How that
     * becomes pixels is the viewport's business; the host only resolves the theme, which it must,
     * because "Auto" reads this Activity's own night mode.
     */
    private fun loadNovelChapters(provider: NovelReaderProvider, viewport: TextViewport) {
        val model = provider.viewModel
        // "Auto" resolves to a preset here; the stored colours are only what a manual choice left
        // behind, so a document built from them would show the wrong shade.
        val resolvedSettings = model.settings.map { it.resolvedForSystemTheme(isNightMode()) }
        model.chapter
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { chapter ->
                val neighbours = model.chapterNeighbours.value
                viewport.load(
                    chapter = chapter,
                    hasPrevious = neighbours.previous != null,
                    hasNext = neighbours.next != null,
                    settings = resolvedSettings.first(),
                )
            }
            .launchIn(lifecycleScope)

        // Changing a display setting reflows the open chapter in place. The first emission is what the
        // document was just built with, so it is dropped rather than pushed straight back.
        resolvedSettings
            .distinctUntilChanged()
            .drop(1)
            .onEach(viewport::applySettings)
            .launchIn(lifecycleScope)
    }

    /**
     * The cutout inset in dp, so the first line clears a punch-hole in immersive mode. The WebView
     * viewport is initial-scale=1, so its CSS pixels are dp.
     */
    internal fun displayCutoutTopDp(): Int {
        val insets = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.displayCutout())
        return ((insets?.top ?: 0) / resources.displayMetrics.density).roundToInt()
    }

    // RK --> the activity is singleTask, so a launch while it is already alive is delivered here
    // instead of building a new instance. Without this the new intent was dropped and the reader
    // stayed on whatever it was already showing, which a second content type makes far likelier to
    // hit. Restarting is the honest answer: the entry a session is for is read once, at construction.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requested = intent.entryId() ?: return
        if (requested == this.intent.entryId() &&
            intent.getLongExtra("chapter", -1L) == this.intent.getLongExtra("chapter", -1L)
        ) {
            return
        }
        // setIntent BEFORE finishing: if the restart is delivered here again rather than to a fresh
        // instance, which singleTask allows while this one is still dying, the guard above now sees
        // its own intent and stops. Reordering these two lines reintroduces that loop.
        setIntent(intent)
        finish()
        startActivity(intent)
    }
    // RK <--

    override fun onPause() {
        lifecycleScope.launchNonCancellable {
            // RK: whichever session this is writes its own history row; the manga model has nothing
            // to write for a novel launch, where it was never given an entry.
            novelSession?.viewModel?.updateHistory() ?: viewModel.updateHistory()
        }
        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            engine.nextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            engine.previousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = engine.viewport.value?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = engine.viewport.value?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    fun AppBars(state: ReaderViewModel.State) {
        val isHttpSource = state.source is HttpSource
        val viewport by engine.viewport.collectAsState()
        val chrome by engine.chrome.collectAsState()

        val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
        val isPagerType = ReadingMode.isPagerType(viewModel.getMangaReadingMode())
        val cropEnabled = if (isPagerType) cropBorderPaged else cropBorderWebtoon

        // RK: from the engine, so a novel session offers its own actions rather than manga's.
        val bottomButtons by engine.bottomButtons.collectAsState()
        val bookmarked by engine.bookmarked.collectAsState()
        val webUrl by engine.webUrl.collectAsState()
        val navigator by engine.navigator.collectAsState()
        val orientation by engine.orientation.collectAsState()
        val keepScreenOn by engine.keepScreenOn.collectAsState()

        ReaderAppBars(
            visible = state.menuVisible,

            // RK: from the engine, so the bar names the entry whichever content type is open.
            mangaTitle = chrome.entryTitle,
            chapterTitle = chrome.chapterTitle,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            // RK: from the engine, so the control reflects the chapter this session has open. Reading
            // manga's model left it permanently empty and its tap a no-op for a novel.
            bookmarked = bookmarked,
            onToggleBookmarked = engine::toggleBookmark,
            onOpenInWebView = { openChapterInWebView(webUrl) }.takeIf { webUrl != null },
            onOpenInBrowser = { openChapterInBrowser(webUrl) }.takeIf { webUrl != null },
            onShare = { shareChapter(webUrl) }.takeIf { webUrl != null },

            // RK: the shape is the session's answer, and the direction is asked of the viewer
            // contract, so the host neither reads manga's preference nor instance-checks a viewer.
            chapterNavigatorType = if (!navigator.useRail) {
                if (viewport?.isRtl == true) {
                    ChapterNavigatorType.HORIZONTAL_RTL
                } else {
                    ChapterNavigatorType.HORIZONTAL_LTR
                }
            } else {
                if (navigator.railOnLeft) {
                    ChapterNavigatorType.VERTICAL_LEFT
                } else {
                    ChapterNavigatorType.VERTICAL_RIGHT
                }
            },
            verticalNavigatorHeight = navigator.railHeightPercent / 100f,
            onNextChapter = engine::nextChapter,
            enabledNext = navigator.hasNext,
            onPreviousChapter = engine::previousChapter,
            enabledPrevious = navigator.hasPrevious,
            progress = navigator.progress,
            onSeek = {
                isScrollingThroughPages = true
                engine.seek(it)
            },
            onSeekFinished = {
                isScrollingThroughPages = false
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = { engine.openDialog(ReaderDialog.ReadingModeSelect) },
            orientation = ReaderOrientation.fromPreference(orientation),
            onClickOrientation = { engine.openDialog(ReaderDialog.OrientationSelect) },
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            onClickSettings = { engine.openDialog(ReaderDialog.Settings) },
            // RK -->
            bottomButtons = bottomButtons,
            onClickChapterList = { engine.openDialog(ReaderDialog.ChapterList) },
            keepScreenOn = keepScreenOn,
            onClickKeepScreenOn = { engine.setKeepScreenOn(!keepScreenOn) },
            // Null where the session renders images, which is what leaves the two typography buttons
            // off the bar for manga rather than opening a picker over nothing.
            onClickTextSize = engine.textSettings?.let { { engine.openDialog(ReaderDialog.TextSize(it)) } },
            onClickTheme = engine.textSettings?.let { { engine.openDialog(ReaderDialog.ThemeSelect(it)) } },
            // RK <--
        )
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val hadViewer = engine.viewport.value != null
        // RK: built through the provider, so this line stays the same once a second content type has
        // one; the engine owns the swap, including destroying the outgoing viewport.
        val newViewport = engine.provider.createViewport(this)
        engine.installViewport(newViewport)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        if (hadViewer) {
            binding.viewerContainer.removeAllViews()
        }
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewport.view)

        // RK --> auto-webtoon overrode the default, so say why. Deliberately not gated on
        // showReadingMode: muting the routine "here's your mode" readout shouldn't also mute the
        // one notice that explains an override the user didn't ask for. Only when it actually
        // changed something: for a reader whose default is already webtoon it overrides nothing,
        // and announcing one would be a lie on every long-strip series they open.
        val autoWebtoonMode = viewModel.autoWebtoonMode()
        if (autoWebtoonMode != null && autoWebtoonMode != readerPreferences.defaultReadingMode.get()) {
            readingModeToast?.cancel()
            readingModeToast = toast(MR.strings.auto_webtoon_snack)
        } else if (readerPreferences.showReadingMode.get()) {
            // RK <--
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        // RK: tint the initial loading spinner from the cover color (Y11)
        loadingIndicator = ReaderProgressIndicator(this, seedColor = viewModel.manga?.asMangaCover()?.vibrantCoverColor)
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    // RK: the URL is the session's answer rather than manga's, so a novel opens its own chapter page.
    // The source id is manga's only: it lets the WebView reuse that source's headers, and a novel
    // source has no numeric id, so it opens without one exactly as the standalone reader did.
    private fun openChapterInWebView(url: String?) {
        val target = url ?: return
        val title = engine.chrome.value.entryTitle
        startActivity(WebViewActivity.newIntent(this, target, viewModel.getSource()?.id, title))
    }

    private fun openChapterInBrowser(url: String?) {
        url?.let { openInBrowser(it.toUri(), forceDefaultBrowser = false) }
    }

    private fun shareChapter(url: String?) {
        url?.let { startActivity(it.toUri().toShareIntent(this, type = "text/plain")) }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        // RK: the indicator goes whether or not a viewport exists to receive the chapters, so this is
        // safe only under the collector ordering noted where the two are registered. Chapter delivery
        // is unwrapped rather than on the neutral contract, because ViewerChapters is manga-shaped.
        binding.readerContainer.removeView(loadingIndicator)
        (engine.viewport.value as? MangaViewport)?.viewer?.setChapters(viewerChapters)

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    // RK: a chapter that failed after the reader was already open. Unlike initError above, the reader
    // stays: closing it would throw away the chapter the user was reading, and the failure is often
    // one retry away.
    private fun setChapterLoadError(message: String?) {
        engine.openDialog(ReaderDialog.LoadFailed(message))
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            engine.openDialog(ReaderDialog.Loading)
        } else {
            engine.dismissDialog()
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        engine.openDialog(ReaderDialog.PageActions(mangaProvider.pageActions(page)))
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(uri: Uri, page: ReaderPage) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number),
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme.changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(baseContext.readerBackgroundColor(theme))
                }
                .launchIn(lifecycleScope)

            // RK: off the engine, since each content type keeps its own flag and novels can flip it
            // from the bar.
            engine.keepScreenOn
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(0.1.seconds)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
