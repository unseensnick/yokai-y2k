package eu.kanade.tachiyomi.ui.library

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import mihon.icons.materialsymbols.rounded.TravelExplore
import reikai.data.track.TrackerRefreshJob
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.sortForCategory
import reikai.presentation.browse.globalsearch.EntryGlobalSearchScreen
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.library.LibraryBucket
import reikai.presentation.library.LibraryDialog
import reikai.presentation.library.LibraryEngine
import reikai.presentation.library.LibraryScreenState
import reikai.presentation.library.LibrarySettingsSheet
import reikai.presentation.library.MangaLibraryAdapter
import reikai.presentation.library.NovelLibraryAdapter
import reikai.presentation.library.ReikaiCategoryHopper
import reikai.presentation.library.ReikaiCategoryPickerSheet
import reikai.presentation.library.ReikaiLibraryContent
import reikai.presentation.library.novels.NovelLibraryViewModel
import reikai.presentation.library.reikaiCategoryHeaderIndices
import reikai.presentation.library.reikaiIsCollapsed
import reikai.presentation.library.sortLabelRes
import reikai.presentation.library.updateerror.UpdateErrorsScreen
import reikai.presentation.library.visualLabel
import reikai.presentation.migrate.flow.EntryMigrationSourcePickScreen
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.reader.novelReaderTarget
import reikai.presentation.reader.open
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val viewModel = metroViewModel<LibraryViewModel>()
        val settingsViewModel = metroViewModel<LibrarySettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        // RK --> novels in the library behind the Manga/Novels chip. Both models stay live; a per-type
        // adapter maps each onto the neutral LibraryScreenState / LibraryBehavior, so the tab reads one
        // `libState` and dispatches one `behavior` instead of branching manga-vs-novel per field. The chip
        // picks the active adapter; only navigation stays branched below, because it needs the navigator
        // and the per-type screen types. The `active*` locals are kept as thin aliases over
        // `libState` so every downstream view reads them unchanged.
        val novelModel = metroViewModel<NovelLibraryViewModel>()
        val novelState by novelModel.state.collectAsStateWithLifecycle()
        // The engine owns which provider drives the view and every dialog, so the content type is decided
        // in one place rather than at each call site. It is shaped to merge both providers for an All view
        // later, and is a ViewModel for its scope: building the change-categories dialog reads categories.
        // It also constructs the adapters, so exactly one pair exists for as long as the engine does. They
        // must not be `remember`ed separately: the engine outlives the composition, so a tab switch would
        // hand the tab a second pair while the engine kept dispatching through the first. All three models
        // share this tab host's ViewModelStore, so the pair the factory captures is cleared alongside them.
        val graph = remember { context.appGraph }
        val engine = assistedMetroViewModel<LibraryEngine, LibraryEngine.Factory> {
            create(
                providers = listOf(
                    graph.mangaLibraryAdapterFactory.create(viewModel),
                    graph.novelLibraryAdapterFactory.create(novelModel),
                ),
            )
        }
        val libraryContentType by engine.contentType.collectAsState()
        val libraryDialog by engine.dialog.collectAsState()
        // RK: the library-wide display config, read from the engine rather than off the manga model, so
        // the tab does not reach into one content type for a setting that belongs to neither.
        val display by engine.display.collectAsState()
        // RK: collect BOTH adapters' state and pick synchronously, so flipping the chip switches
        // instantly. Collecting a single `behavior.state` over the switched adapter re-subscribes on the
        // flow change, holding the old value for a frame, which stutters the manga<->novel transition.
        // Both are collected for as long as this tab is on screen, so the inactive side stays current
        // for an instant swap; the lifecycle read is what lets the models stop once it is not.
        val mangaLibState by engine.behaviorFor(ContentType.MANGA).state.collectAsStateWithLifecycle()
        val novelLibState by engine.behaviorFor(ContentType.NOVELS).state.collectAsStateWithLifecycle()
        val libState = when (libraryContentType) {
            ContentType.MANGA -> mangaLibState
            ContentType.NOVELS -> novelLibState
            // RK: the All view's state, combined field by field. The list itself is not in here at all;
            // it comes off the assembly below.
            ContentType.ALL -> LibraryScreenState(
                isLoading = mangaLibState.isLoading || novelLibState.isLoading,
                isLibraryEmpty = mangaLibState.isLibraryEmpty && novelLibState.isLibraryEmpty,
                // The engine fans a search out to both models, so the two queries mirror each other.
                searchQuery = mangaLibState.searchQuery,
                hasActiveFilters = mangaLibState.hasActiveFilters || novelLibState.hasActiveFilters,
                activeCategoryIndex = mangaLibState.activeCategoryIndex,
                showContinueButton = mangaLibState.showContinueButton,
                // Either type's overlay edit must reach the screen, so both identities are carried.
                overlayKey = mangaLibState.overlayKey to novelLibState.overlayKey,
            )
        }
        // RK: the list (the sections, their rows and their counts) renders off the engine's assembly,
        // the only place that can bucket both content types into one list. The assembly lags a chip
        // flip by one emission, so it renders only when its chip matches; the empty defaults below
        // cover that single frame and the cold-start frame, both of which sit behind isLoading.
        val assembled = engine.assembled.collectAsStateWithLifecycle().value
            ?.takeIf { it.chip == libraryContentType }
        val activeBuckets = assembled?.buckets.orEmpty()
        // RK: the selection is the engine's, not a provider's: it can span both content types.
        val activeSelection by engine.selection.collectAsState()
        val activeSearchQuery = libState.searchQuery
        val activeIsLibraryEmpty = libState.isLibraryEmpty
        val activeIsLoading = libState.isLoading
        val activeGetItems: (LibraryBucket) -> List<LibraryItem> =
            assembled?.let { it::itemsFor } ?: { emptyList() }
        val activeGetItemCount: (LibraryBucket) -> Int? =
            assembled?.let { it::countFor } ?: { null }
        val onSearch: (String?) -> Unit = { engine.search(libraryContentType, it) }
        val activeSelectionMode = activeSelection.isNotEmpty()
        val activeHasActiveFilters = libState.hasActiveFilters
        // RK: the toolbar's whole-library count, over the assembled list rather than the manga model's
        // favorites (which under All counted only one of the two content types). Distinct because an
        // entry in several categories sits in several buckets. Remembered: it walks the whole library,
        // and only the tabbed view with counts on ever shows it.
        val showWholeLibraryCount = display.showItemCounts && display.showCategoryTabs
        val wholeLibraryCount = remember(activeBuckets, activeGetItems, showWholeLibraryCount) {
            if (!showWholeLibraryCount) {
                0
            } else {
                activeBuckets.flatMap(activeGetItems).distinctBy(LibraryItem::entryId).size
            }
        }
        // The rows of one section in display order, which is what the engine's range-select and
        // select-all need. Resolved here rather than in the engine so the engine never looks rows up
        // itself and stays free of per-type knowledge.
        val entriesOf: (LibraryBucket?) -> List<EntryId> = { bucket ->
            bucket?.let { activeGetItems(it).map(LibraryItem::entryId) }.orEmpty()
        }
        // RK <--

        val snackbarHostState = remember { SnackbarHostState() }

        // RK --> hopper + jump-to-category picker drive both views through a single `hopperTarget`.
        // Single-list jumps (prev/next and the picker) are instant: categories can hold hundreds of
        // items, so animating across them stutters, while an instant jump stays snappy under rapid
        // taps (this is what Yōkai does). The tabbed pager animates its page transition.
        // RK: one scroll state per content type, so toggling the Manga/Novels chip preserves each
        // view's own position instead of both sharing a single offset (upstream is manga-only). The
        // active pair falls through to the current type, like the other `active*` locals above.
        val mangaSingleListGridState = rememberLazyGridState()
        val novelSingleListGridState = rememberLazyGridState()
        val allSingleListGridState = rememberLazyGridState()
        val singleListGridState = when (libraryContentType) {
            ContentType.MANGA -> mangaSingleListGridState
            ContentType.NOVELS -> novelSingleListGridState
            ContentType.ALL -> allSingleListGridState
        }
        // RK: one pager per chip, each sized from a snapshot taken only while its own chip is up. Pointing
        // all three at activeBuckets.size would make an inactive chip's pager lose its position, since
        // Compose clamps a pager's currentPage whenever its pageCount shrinks. Each pager seeds its own
        // chip's persisted page (the pager clamps the value if the list is shorter).
        val mangaPageCount = remember { mutableIntStateOf(0) }
        val novelPageCount = remember { mutableIntStateOf(0) }
        val allPageCount = remember { mutableIntStateOf(0) }
        when (libraryContentType) {
            ContentType.MANGA -> mangaPageCount.intValue = activeBuckets.size
            ContentType.NOVELS -> novelPageCount.intValue = activeBuckets.size
            ContentType.ALL -> allPageCount.intValue = activeBuckets.size
        }
        val mangaPagerState = rememberPagerState(initialPage = mangaLibState.activeCategoryIndex) {
            mangaPageCount.intValue
        }
        val novelInitialPage = remember { engine.initialPageFor(ContentType.NOVELS) }
        val allInitialPage = remember { engine.initialPageFor(ContentType.ALL) }
        val novelPagerState = rememberPagerState(initialPage = novelInitialPage) { novelPageCount.intValue }
        val allPagerState = rememberPagerState(initialPage = allInitialPage) { allPageCount.intValue }
        val pagerState = when (libraryContentType) {
            ContentType.MANGA -> mangaPagerState
            ContentType.NOVELS -> novelPagerState
            ContentType.ALL -> allPagerState
        }
        var pickerOpen by remember { mutableStateOf(false) }
        var hopperTarget by remember { mutableStateOf<Int?>(null) }
        var hopperDragAccum by remember { mutableFloatStateOf(0f) }
        fun reikaiHeaderIndices(): List<Int> = reikaiCategoryHeaderIndices(
            buckets = activeBuckets,
            hasSearchItem = !activeSearchQuery.isNullOrEmpty(),
            isCollapsed = {
                reikaiIsCollapsed(
                    it,
                    display.reikai.collapsedCategories,
                    display.reikai.collapsedDynamicCategories,
                )
            },
            itemCount = { activeGetItems(it).size },
        )
        fun currentCategoryIndex(): Int = if (display.reikai.showAllCategories) {
            reikaiHeaderIndices().indexOfLast { it <= singleListGridState.firstVisibleItemIndex }.coerceAtLeast(0)
        } else {
            pagerState.currentPage
        }
        // RK: the section Select all / Invert act on. Keyed to the section actually on screen, which in
        // the single-list view means the one scrolled to: there is no pager there, so the stored page
        // index never moves off the first category no matter how far down the list you are.
        val activeCategoryEntries: () -> List<EntryId> = {
            entriesOf(activeBuckets.getOrNull(currentCategoryIndex()))
        }
        // The section the toolbar and hopper actions act on: the one actually on screen. In the
        // single-list view the stored page index never moves, so anything reading it there acted on
        // the first category while the title named the scrolled-to one.
        val currentBucket: () -> LibraryBucket? = { activeBuckets.getOrNull(currentCategoryIndex()) }
        // For actions that need a REAL category: a dynamic group has none, so these fall back to null
        // (the global scope). The section headers disable such affordances outright; the toolbar and
        // hopper have no off state.
        val currentRealCategory: () -> Category? = { currentBucket()?.realCategory }
        LaunchedEffect(hopperTarget) {
            val target = hopperTarget ?: return@LaunchedEffect
            if (display.reikai.showAllCategories) {
                reikaiHeaderIndices().getOrNull(target)?.let { itemIndex ->
                    // Jump instantly to the target category, the way Yōkai's hopper does. Categories
                    // here can hold hundreds of items, so a smooth scroll across them has to compose
                    // everything in between and stutters; an instant jump stays snappy under rapid
                    // taps. Land the header flush at the top: a negative offset to leave a gap would
                    // make firstVisibleItemIndex point at the previous category, so prev/next would
                    // read the current category one too low and stall after the first hop.
                    singleListGridState.scrollToItem(itemIndex)
                }
            } else {
                pagerState.animateScrollToPage(target)
            }
            hopperTarget = null
        }
        // RK <--

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = engine.refresh(libraryContentType, category)
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        // RK: open an entry on its own details screen, routed by the ROW's content type rather than the
        // active chip. Navigation stays per-type (each type has its own screen), but the decision no
        // longer depends on ambient UI state, so a mixed list routes every row correctly.
        val openEntry: (EntryId) -> Unit = { entryId ->
            when (entryId) {
                is EntryId.Novel -> novelState.routeFor(entryId.rawId)?.let {
                    navigator.push(NovelScreen(it.source, it.url))
                }
                is EntryId.Manga -> navigator.push(MangaScreen(entryId.rawId))
            }
        }

        // RK: open a random entry, from the section on screen or from the whole library. The entry comes
        // back neutral and opens through the same routing every other row uses, so the two callers can't
        // drift into opening different content types.
        val onOpenRandom: (String?) -> Unit = { bucketKey ->
            val entry = engine.randomEntry(libraryContentType, bucketKey)
            if (entry == null) {
                scope.launch {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.information_no_entries_found))
                }
            } else {
                openEntry(entry)
            }
        }

        // RK: shared manga continue-reading handler, used by both the pager and the single-list view.
        val onMangaContinueReading: (LibraryManga) -> Unit = { item ->
            scope.launchIO {
                val chapter = viewModel.getNextUnreadChapter(item.manga)
                if (chapter != null) {
                    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }
        // RK: novel continue-reading (both views). Resume opens the next unread chapter from its own
        // source in group scope, so the reader's prev/next spans the whole merge group (the reader
        // resolves it itself).
        val onNovelContinueReading: (LibraryManga) -> Unit = { item ->
            scope.launchIO {
                val resume = novelModel.getResume(item.manga.id)
                if (resume != null) {
                    withUIContext {
                        novelReaderTarget(context, resume.novelId, resume.id).open(context, navigator)
                    }
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }
        // RK: the resume handler is per-type navigation, dispatched on the ROW's own content type rather
        // than the active chip, so a mixed list resumes each row in its own reader. The gate is neutral.
        val onContinueReading: ((LibraryItem) -> Unit)? = { item: LibraryItem ->
            when (item.entryId) {
                is EntryId.Novel -> onNovelContinueReading(item.libraryManga)
                is EntryId.Manga -> onMangaContinueReading(item.libraryManga)
            }
        }.takeIf { libState.showContinueButton }

        Scaffold(
            topBar = { scrollBehavior ->
                // RK: built here over the assembled list, keeping the manga model's rules. It used to be
                // the manga State's own, which knew only manga categories and counted only manga rows.
                val defaultTitle = stringResource(MR.strings.label_library)
                // Single-list tracks the visible section on scroll, so the title follows it.
                val title = when (val bucket = currentBucket()) {
                    null -> LibraryToolbarTitle(defaultTitle)
                    else -> LibraryToolbarTitle(
                        // "Always show current category" forces the section name into the title.
                        text = if (display.reikai.showCategoryInTitle || !display.showCategoryTabs) {
                            bucket.visualLabel
                        } else {
                            defaultTitle
                        },
                        numberOfManga = when {
                            !display.showItemCounts -> null
                            !display.showCategoryTabs -> activeGetItemCount(bucket)
                            else -> wholeLibraryCount
                        },
                    )
                }
                // RK: stack the content-type chip under the toolbar so the Scaffold sizes
                // contentPadding to include it and both library views render below it untouched.
                // RK: match the toolbar's container color so the chip strip reads as part of it.
                // Rest = surfaceColorAtElevation(0/3dp) (3dp in selection, mirroring AppBar's
                // isActionMode); on scroll the toolbar tints to its scrolledContainerColor
                // (M3 default surfaceContainer), only when it actually collapses (no category tabs).
                val chipBackground by animateColorAsState(
                    targetValue = if (!display.showCategoryTabs && scrollBehavior.state.overlappedFraction > 0.01f) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceColorAtElevation(if (activeSelectionMode) 3.dp else 0.dp)
                    },
                    label = "libraryChipBackground",
                )
                Column {
                    LibraryToolbar(
                        // RK: filter indicator + selection actions follow the active (manga/novel) model
                        hasActiveFilters = activeHasActiveFilters,
                        selectedCount = activeSelection.size,
                        title = title,
                        onClickUnselectAll = engine::clearSelection,
                        onClickSelectAll = { engine.selectAll(activeCategoryEntries()) },
                        onClickInvertSelection = { engine.invertSelection(activeCategoryEntries()) },
                        onClickFilter = {
                            // RK: the toolbar sort is GLOBAL (Model A); a null category scopes the sheet to
                            // the global sort, not a stale active category. Per-category overrides are set
                            // from each category header's sort in the single-list view.
                            engine.openSettingsDialog(libraryContentType, categoryId = null, initialTab = 0)
                        },
                        onClickRefresh = { onClickRefresh(currentRealCategory()) },
                        onClickGlobalUpdate = { onClickRefresh(null) },
                        // RK: follows the content-type chip; it used to always open a manga.
                        onClickOpenRandomManga = { onOpenRandom(currentBucket()?.key) },
                        // RK: library-wide tracker refresh, both content types at once, so it does not
                        // follow the chip. A snackbar reports the two states the user can act on.
                        onClickRefreshTrackers = {
                            val started = TrackerRefreshJob.startNow(context)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.stringResource(
                                        if (started) {
                                            MR.strings.tracker_refresh_progress
                                        } else {
                                            MR.strings.tracker_refresh_already_running
                                        },
                                    ),
                                )
                            }
                        },
                        // RK: opt-in Update errors screen (hidden unless the matching Advanced toggle is on);
                        //     opens on the chip for the content type currently shown.
                        onClickUpdateErrors = run {
                            val reikai = display.reikai
                            val enabled = when (libraryContentType) {
                                ContentType.MANGA -> reikai.trackUpdateErrors
                                ContentType.NOVELS -> reikai.trackNovelUpdateErrors
                                ContentType.ALL -> reikai.trackUpdateErrors || reikai.trackNovelUpdateErrors
                            }
                            if (enabled) {
                                // The errors screen already handles ALL with its own chip strip.
                                { navigator.push(UpdateErrorsScreen(libraryContentType)) }
                            } else {
                                null
                            }
                        },
                        searchQuery = activeSearchQuery,
                        onSearchQueryChange = onSearch,
                        // For scroll overlay when no tab
                        scrollBehavior = scrollBehavior.takeIf { !display.showCategoryTabs },
                    )
                    // RK: all three chips since the All view landed; the component's default order is
                    // All, Manga, Novels (ContentType.entries).
                    ContentTypeFilterChips(
                        selected = libraryContentType,
                        onSelect = engine::setContentType,
                        modifier = Modifier.background(chipBackground),
                    )
                }
            },
            bottomBar = {
                // RK: one action bar for both content types (download menu, mark read/unread, change
                // category, delete, merge, unmerge). Only the batch-migrate nav stays per-type: it pushes a
                // per-type source-pick screen over each type's own id space.
                LibraryBottomActionMenu(
                    visible = activeSelectionMode,
                    onChangeCategoryClicked = { engine.openChangeCategoryDialog(libraryContentType) },
                    onMarkAsReadClicked = { engine.markReadSelection(libraryContentType, true) },
                    onMarkAsUnreadClicked = { engine.markReadSelection(libraryContentType, false) },
                    // RK: manga hides Download when every selected entry is local; novels never do.
                    onDownloadClicked = { action: DownloadAction ->
                        engine.performDownloadAction(libraryContentType, action)
                    }
                        .takeIf { engine.canDownloadSelection(libraryContentType) },
                    onDeleteClicked = { engine.openDeleteDialog(libraryContentType) },
                    // RK: migration is per-type (each pushes a screen over its own id space), so it routes
                    // by what the selection actually holds, and a mixed selection hides the action rather
                    // than silently flattening two id spaces into one screen.
                    onMigrateClicked = run {
                        val mangaIds = activeSelection.filterIsInstance<EntryId.Manga>().map { it.rawId }
                        val novelIds = activeSelection.filterIsInstance<EntryId.Novel>().map { it.rawId }
                        when {
                            novelIds.isEmpty() && mangaIds.isNotEmpty() -> {
                                {
                                    engine.clearSelection()
                                    // RK: source picker first (merged-manga member choice).
                                    navigator.push(EntryMigrationSourcePickScreen(ContentType.MANGA, mangaIds))
                                }
                            }
                            mangaIds.isEmpty() && novelIds.isNotEmpty() -> {
                                {
                                    engine.clearSelection()
                                    navigator.push(
                                        EntryMigrationSourcePickScreen(ContentType.NOVELS, novelIds),
                                    )
                                }
                            }
                            else -> null
                        }
                    },
                    // RK: manual merge of the selected entries (needs at least two OF ONE TYPE: a merge
                    // group is per content type, and a mixed gesture would silently create two groups)
                    // + unmerge (only when the selection includes a merged one). Both follow the
                    // grouping switch: with it off the manager refuses the merge and the collapse never
                    // marks a row, so Unmerge could not offer a way back out of what Merge wrote.
                    onMergeClicked = { engine.mergeSelection(libraryContentType) }
                        .takeIf {
                            display.reikai.seriesMergingEnabled &&
                                activeSelection.size >= 2 &&
                                activeSelection.mapTo(mutableSetOf()) { it.contentType }.size == 1
                        },
                    onUnmergeClicked = { engine.unmergeSelection(libraryContentType) }
                        .takeIf { engine.selectionContainsMerged(libraryContentType) },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                activeIsLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                // RK: "empty" is counted after filters run, so the filter guard is what stops a filter
                // that matches nothing from reading as an empty library. Both content types need it.
                // The getting-started guide stays manga-only on purpose: it documents manga extensions,
                // which the novel plugin system does not use.
                activeSearchQuery.isNullOrEmpty() && !activeHasActiveFilters && activeIsLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = when (libraryContentType) {
                            ContentType.NOVELS -> null
                            // Manga and All: the guide documents manga extensions, which still helps an
                            // empty mixed library; the novel plugin system does not use it.
                            else -> listOf(
                                EmptyScreenAction(
                                    stringRes = MR.strings.getting_started_guide,
                                    icon = MaterialSymbols.AutoMirroredRounded.Help,
                                    onClick = { handler.openUri(GETTING_STARTED_URL) },
                                ),
                            )
                        },
                    )
                }
                // RK: a search or filter that matches nothing empties every category, and the
                // assembly always hides empty categories, so the pager would render zero pages: no
                // message, and no way to search globally exactly when the local search failed.
                // (Loading and the truly-empty library are handled above, so reaching here means an
                // active search or filter, or hiding every category, emptied the list.)
                activeBuckets.isEmpty() -> {
                    EmptyScreen(
                        stringRes = MR.strings.no_results_found,
                        modifier = Modifier.padding(contentPadding),
                        actions = activeSearchQuery?.takeIf { it.isNotEmpty() }?.let { query ->
                            listOf(
                                EmptyScreenAction(
                                    stringRes = MR.strings.action_global_search,
                                    icon = MaterialSymbols.Rounded.TravelExplore,
                                    onClick = {
                                        navigator.push(
                                            EntryGlobalSearchScreen(query, scopedContentType = libraryContentType),
                                        )
                                    },
                                ),
                            )
                        },
                    )
                }
                else -> {
                    // RK --> both library views (pager + single-list) with hopper + picker overlaid
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (display.reikai.showAllCategories) {
                            val isLandscape =
                                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                            // Remembered like upstream's pager: each call builds a preference state
                            // whose collector lives in the engine scope, so an un-remembered call
                            // leaked one per recomposition.
                            val columns by remember(isLandscape) { engine.columnsForOrientation(isLandscape) }
                            val displayMode by remember { engine.displayMode() }
                            // RK: the global sort each non-overridden category follows, one library-wide
                            // value since the sort preferences unified, so no chip involved.
                            val globalSort by engine.globalSort.collectAsState()
                            ReikaiLibraryContent(
                                buckets = activeBuckets,
                                getItemsForCategory = activeGetItems,
                                collapsedCategories = display.reikai.collapsedCategories,
                                collapsedDynamicCategories = display.reikai.collapsedDynamicCategories,
                                showItemCounts = display.showItemCounts,
                                displayMode = displayMode,
                                columns = columns,
                                selection = activeSelection,
                                searchQuery = activeSearchQuery,
                                gridState = singleListGridState,
                                contentPadding = contentPadding,
                                onClickManga = { bucket, item ->
                                    if (activeSelectionMode) {
                                        engine.toggleSelection(bucket.key, item.entryId)
                                    } else {
                                        // RK: navigation is per-type, routed by the ROW's own content
                                        // type rather than the active chip, so a mixed list opens each
                                        // row on its own screen.
                                        openEntry(item.entryId)
                                    }
                                },
                                onLongClickManga = { bucket, item ->
                                    // RK: range-select (incl. the in-between) like the tabbed view,
                                    // instead of toggling only the long-pressed manga.
                                    engine.toggleRangeSelection(bucket.key, item.entryId, entriesOf(bucket))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onToggleDefaultCollapse = engine::toggleDefaultCategoryCollapse,
                                onToggleDynamicCollapse = engine::toggleDynamicCategoryCollapse,
                                onGlobalSearchClicked = {
                                    navigator.push(
                                        EntryGlobalSearchScreen(
                                            activeSearchQuery ?: "",
                                            scopedContentType = libraryContentType,
                                        ),
                                    )
                                },
                                // RK: pull-to-refresh on the single-list updates the whole library (= overflow Update library).
                                onRefresh = { onClickRefresh(null) },
                                // RK: per-category header sort (Sort tab scoped to it), refresh, select-all
                                onClickCategorySort = { category ->
                                    engine.openSettingsDialog(libraryContentType, category.id, initialTab = 1)
                                },
                                onRefreshCategory = { category -> onClickRefresh(category) },
                                onSelectAllInCategory = { bucket -> engine.selectAllInCategory(entriesOf(bucket)) },
                                // RK: the header shows each category's EFFECTIVE sort, its own override or
                                // the global sort it follows, decoded the same way on both content types.
                                sortLabelFor = { category -> sortLabelRes(sortForCategory(category, globalSort).type) },
                                sortAscendingFor = { category ->
                                    sortForCategory(category, globalSort).isAscending
                                },
                                onClickContinueReading = onContinueReading,
                            )
                        } else {
                            LibraryContent(
                                buckets = activeBuckets,
                                searchQuery = activeSearchQuery,
                                selection = activeSelection,
                                contentPadding = contentPadding,
                                pagerState = pagerState,
                                hasActiveFilters = activeHasActiveFilters,
                                showPageTabs = display.showCategoryTabs || !activeSearchQuery.isNullOrEmpty(),
                                onChangeCurrentPage = { engine.updateActiveCategoryIndex(libraryContentType, it) },
                                onClickManga = openEntry,
                                onContinueReadingClicked = onContinueReading,
                                onToggleSelection = { bucket, item ->
                                    engine.toggleSelection(bucket.key, item.entryId)
                                },
                                onToggleRangeSelection = { bucket, item ->
                                    engine.toggleRangeSelection(bucket.key, item.entryId, entriesOf(bucket))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onRefresh = { onClickRefresh(currentRealCategory()) },
                                onGlobalSearchClicked = {
                                    navigator.push(
                                        EntryGlobalSearchScreen(
                                            activeSearchQuery ?: "",
                                            scopedContentType = libraryContentType,
                                        ),
                                    )
                                },
                                getItemCountForCategory = activeGetItemCount,
                                getDisplayMode = { engine.displayMode() },
                                getColumnsForOrientation = { engine.columnsForOrientation(it) },
                                getItemsForCategory = activeGetItems,
                            )
                        }

                        if (!display.reikai.hideHopper && activeBuckets.isNotEmpty()) {
                            val hopperAlignment = when (display.reikai.hopperGravity) {
                                0 -> Alignment.BottomStart
                                2 -> Alignment.BottomEnd
                                else -> Alignment.BottomCenter
                            }
                            // Autohide: fade the hopper out while the single-list is scrolling,
                            // bring it back when it settles. No effect in the pager (its grid state
                            // isn't this one), where the hopper stays put.
                            val hopperVisible = !display.reikai.autohideHopper ||
                                !singleListGridState.isScrollInProgress
                            AnimatedVisibility(
                                visible = hopperVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(hopperAlignment)
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
                            ) {
                                ReikaiCategoryHopper(
                                    modifier = Modifier
                                        // Drag the hopper left/right to move it between start / center / end.
                                        .pointerInput(display.reikai.hopperGravity) {
                                            val gravity = display.reikai.hopperGravity
                                            detectHorizontalDragGestures(
                                                onDragStart = { hopperDragAccum = 0f },
                                                onDragEnd = {
                                                    val next = when {
                                                        hopperDragAccum > 48f -> (gravity + 1).coerceAtMost(2)
                                                        hopperDragAccum < -48f -> (gravity - 1).coerceAtLeast(0)
                                                        else -> gravity
                                                    }
                                                    if (next != gravity) viewModel.setHopperGravity(next)
                                                },
                                            ) { change, dragAmount ->
                                                change.consume()
                                                hopperDragAccum += dragAmount
                                            }
                                        },
                                    onUpClick = {
                                        val last = activeBuckets.lastIndex.coerceAtLeast(0)
                                        hopperTarget = ((hopperTarget ?: currentCategoryIndex()) - 1).coerceIn(0, last)
                                    },
                                    onCenterClick = { pickerOpen = true },
                                    // RK: every hopper long-press action follows the content-type chip, which
                                    // the seam decides, so none of them branches on the chip here.
                                    onCenterLongClick = {
                                        when (display.reikai.hopperLongPressAction) {
                                            0 -> onSearch("")
                                            1 -> engine.toggleAllCategoriesCollapsed(activeBuckets)
                                            // The hopper is a category navigator, so its sheet is scoped to
                                            // the category it sits on, the same as a category header's sort.
                                            // The sheet's tabs are swipeable, so the Sort tab is reachable
                                            // from either of these: on a dynamic group the scope has to be
                                            // null (global), or setting a sort there writes the global
                                            // preference while presenting itself as a per-category override.
                                            2 -> engine.openSettingsDialog(
                                                libraryContentType,
                                                currentRealCategory()?.id,
                                                initialTab = 2,
                                            )
                                            3 -> engine.openSettingsDialog(
                                                libraryContentType,
                                                currentRealCategory()?.id,
                                                initialTab = 3,
                                            )
                                            4 -> onOpenRandom(currentBucket()?.key)
                                            5 -> onOpenRandom(null)
                                        }
                                    },
                                    onDownClick = {
                                        val last = activeBuckets.lastIndex.coerceAtLeast(0)
                                        hopperTarget = ((hopperTarget ?: currentCategoryIndex()) + 1).coerceIn(0, last)
                                    },
                                )
                            }
                        }
                    }

                    if (pickerOpen) {
                        ReikaiCategoryPickerSheet(
                            buckets = activeBuckets,
                            getItemCount = activeGetItemCount,
                            showItemCounts = display.showItemCounts,
                            activeIndex = currentCategoryIndex(),
                            onSelect = { index ->
                                hopperTarget = index
                                pickerOpen = false
                            },
                            onDismiss = { pickerOpen = false },
                        )
                    }
                    // RK <--
                }
            }
        }

        // RK --> one dialog stream for both content types, built and owned by the engine. The dialogs
        // themselves dismiss before they confirm, so each confirm acts on the entries its own dialog was
        // built from, never on the live selection.
        val onDismissRequest = engine::dismissDialog
        when (val dialog = libraryDialog) {
            // One sheet for both content types: the dialog's content type picks whose settings it
            // describes, and the sheet itself is the same code either way. A null category id is the
            // global sort scope, not a stale active category.
            is LibraryDialog.Settings -> LibrarySettingsSheet(
                settings = engine.settingsFor(dialog.contentType),
                settingsViewModel = settingsViewModel,
                categoryId = dialog.categoryId,
                initialTab = dialog.initialTab,
                onManageCategories = {
                    onDismissRequest()
                    navigator.push(CategoryScreen())
                },
                onDismissRequest = onDismissRequest,
            )
            is LibraryDialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        engine.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        engine.setCategories(dialog.entries, include, exclude)
                        engine.clearSelection()
                    },
                )
            }
            is LibraryDialog.Delete -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.containsLocal,
                    // RK: offer removing every grouped source when a merged cover is selected
                    groupedSourceCount = dialog.groupedSourceCount,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteEntry, deleteDownloads, removeGrouped ->
                        engine.deleteEntries(dialog.entries, deleteEntry, deleteDownloads, removeGrouped)
                        engine.clearSelection()
                    },
                )
            }
            null -> {}
        }
        // RK <--

        BackHandler(enabled = activeSelectionMode || activeSearchQuery != null) {
            when {
                activeSelectionMode -> engine.clearSelection()
                activeSearchQuery != null -> onSearch(null)
            }
        }

        // RK: keyed on the engine's dialog, so a novel dialog re-asserts the bottom nav the same way a
        // manga one always has.
        LaunchedEffect(activeSelectionMode, libraryDialog) {
            HomeScreen.showBottomNav(!activeSelectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            // RK: through the seam, so a search sent from another screen lands on the library the chip is
            // showing. Both collectors read the chip from its flow rather than the captured composition
            // value, because this effect keys on Unit and would otherwise hold the chip's first value.
            launch {
                queryEvent.receiveAsFlow()
                    .collect { engine.search(engine.contentType.value, it) }
            }
            launch {
                requestSettingsSheetEvent.receiveAsFlow()
                    .collectLatest { engine.openSettingsDialog(engine.contentType.value) }
            }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
