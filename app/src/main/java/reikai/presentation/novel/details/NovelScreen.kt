package reikai.presentation.novel.details

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.app.di.appGraph
import reikai.data.coil.NovelCover
import reikai.domain.library.ContentType
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.withCustomInfo
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.globalsearch.EntryGlobalSearchScreen
import reikai.presentation.details.EntryDetailsContent
import reikai.presentation.details.EntryDetailsDialog
import reikai.presentation.details.EntryDetailsDialogHost
import reikai.presentation.details.EntryDetailsNavigation
import reikai.presentation.details.EntryDetailsScreenState
import reikai.presentation.details.EntryEditInfoUi
import reikai.presentation.details.NovelEntryAdapter
import reikai.presentation.migrate.flow.EntryMigrateFor
import reikai.presentation.migrate.flow.EntryMigrationSourcePickScreen
import reikai.presentation.novel.notes.NovelNotesScreen
import reikai.presentation.reader.novelReaderTarget
import reikai.presentation.reader.open
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Light-novel details screen, the novel twin of `MangaScreen`. Builds a [NovelEntryAdapter] over the
 * novel model and delegates the whole body to the shared [EntryDetailsContent], so a details change is
 * written once and reaches both content types. Only novel-specific navigation (the reader, notes, the
 * page selector) and the per-type dialogs live here; the shared dialogs go through [EntryDetailsDialogHost].
 */
class NovelScreen(
    // Public so the migrate flow can identity-check the screen below it before replacing.
    val sourceId: String,
    val novelUrl: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<NovelDetailsViewModel, NovelDetailsViewModel.Factory> {
            create(sourceId = sourceId, novelUrl = novelUrl)
        }
        // Lifecycle-aware so collection pauses when the screen is not resumed (parity with MangaScreen).
        val state by viewModel.state.collectAsStateWithLifecycle()
        val coverViewModelFactory = remember { context.appGraph.novelCoverViewModelFactory }
        val adapter = remember(viewModel) { NovelEntryAdapter(viewModel, coverViewModelFactory) }
        val neutralState by adapter.state.collectAsStateWithLifecycle()

        when (val s = state) {
            NovelDetailsState.Loading -> LoadingScreen()
            is NovelDetailsState.Failed -> Scaffold(
                topBar = { AppBar(title = null, navigateUp = navigator::pop, scrollBehavior = it) },
            ) { padding -> EmptyScreen(message = s.message, modifier = Modifier.padding(padding)) }
            is NovelDetailsState.Loaded -> TachiyomiTheme(seedColor = s.seedColor) {
                // Back clears an active chapter selection before popping the screen (mirrors MangaScreen).
                BackHandler(enabled = s.selectionMode) { viewModel.clearSelection() }

                val onWebView: () -> Unit = {
                    s.novelWebUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        navigator.push(WebViewScreen(url = url, initialTitle = s.sourceName, sourceId = null))
                    }
                }
                val onShare: () -> Unit = {
                    s.novelWebUrl?.takeIf { it.isNotBlank() }?.let { url ->
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

                (neutralState as? EntryDetailsScreenState.Loaded)?.let { loaded ->
                    EntryDetailsContent(
                        behavior = adapter,
                        state = loaded,
                        snackbarHostState = viewModel.snackbarHostState,
                        isTabletUi = isTabletUi(),
                        chapterSwipeStartAction = s.chapterSwipeStartAction,
                        chapterSwipeEndAction = s.chapterSwipeEndAction,
                        nav = EntryDetailsNavigation(
                            navigateUp = navigator::pop,
                            onOpenChapter = { chapterId ->
                                // Route to the chapter's own source (a unified-list row keeps its owning
                                // novelId). The All chip opens group scope; a source chip opens source scope.
                                s.chapters.firstOrNull { it.id == chapterId }?.let { ch ->
                                    novelReaderTarget(
                                        context = context,
                                        novelId = ch.novelId,
                                        chapterId = ch.id,
                                        sourceScoped = s.selectedSourceNovelId != null,
                                    ).open(context, navigator)
                                }
                            },
                            // A non-global search (the source-name tap) scopes to the shown source,
                            // like manga's browse-scoped search; global goes cross-source.
                            onSearch = { query, global ->
                                if (global) {
                                    navigator.push(
                                        EntryGlobalSearchScreen(query, scopedContentType = ContentType.NOVELS),
                                    )
                                } else {
                                    navigator.push(EntryCatalogueScreen(SourceKey.Novel(s.displayNovel.source), query))
                                }
                            },
                            onTagSearch = {
                                navigator.push(EntryGlobalSearchScreen(it, scopedContentType = ContentType.NOVELS))
                            },
                            onCopyTag = { context.copyToClipboard(it, it) },
                            onTracking = {
                                if (viewModel.hasLoggedInTrackers()) {
                                    viewModel.showTrackDialog()
                                } else {
                                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                                }
                            },
                            onEditNotes = {
                                navigator.push(NovelNotesScreen(s.novel.id, s.novel.title, s.novel.notes))
                            },
                            onOpenFilterSettings = viewModel::showChapterSettingsDialog,
                            // Novels keep Share in the action row too (matching LNReader) since the manga
                            // action row's smart-update interval button isn't available for novels yet.
                            onActionRowShare = s.novelWebUrl?.let { { onShare() } },
                            onToolbarShare = s.novelWebUrl?.let { { onShare() } },
                            onOpenWebView = s.novelWebUrl?.let { { onWebView() } },
                            // Long-press copies the URL, matching the manga action row.
                            onOpenWebViewLong = s.novelWebUrl?.let { url ->
                                { context.copyToClipboard(url, url) }
                            },
                            // Migration only re-homes a library novel, so it shows only when favorited.
                            // Anchor-scoped like manga: migrating must re-home the series, not
                            // whichever source chip happens to be selected.
                            onMigrate = if (s.novel.favorite) {
                                {
                                    navigator.push(
                                        EntryMigrationSourcePickScreen(ContentType.NOVELS, listOf(s.novel.id)),
                                    )
                                }
                            } else {
                                null
                            },
                            onOpenPageSelector = viewModel::showPageSelectorDialog,
                        ),
                    )
                }

                EntryDetailsDialogHost(s.toSharedDetailsDialog(), adapter, viewModel::dismissDialog)
                NovelDetailsDialogs(s, viewModel)
            }
        }
    }
}

/** The novel dialogs that stay per-type (their data genuinely diverges); the shared ones go through
 *  [EntryDetailsDialogHost]. A `Screen` extension so the duplicate dialog can resolve the migrate controller. */
@Composable
private fun Screen.NovelDetailsDialogs(state: NovelDetailsState.Loaded, viewModel: NovelDetailsViewModel) {
    val navigator = LocalNavigator.currentOrThrow
    when (val dialog = state.dialog) {
        is NovelDetailsDialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = dialog.initialSelection,
            onDismissRequest = viewModel::dismissDialog,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> viewModel.applyCategories(include) },
        )
        is NovelDetailsDialog.DuplicateNovel -> EntryDuplicateDialog(
            duplicates = dialog.duplicates,
            toUi = { it.toDuplicateCard(dialog.sourceLabels, dialog.sourceSites) },
            onDismissRequest = viewModel::dismissDialog,
            onConfirm = viewModel::addFavoriteAnyway,
            onOpen = { navigator.push(NovelScreen(it.novel.source, it.novel.url)) },
            onMigrate = { viewModel.startMigrate(it.novel.id) },
            groupIdByEntryId = dialog.groupIdByNovelId,
            onAddToGroup = { selectedIds: List<Long> ->
                viewModel.addToExistingGroup(selectedIds)
            }.takeIf { dialog.suggestGroup },
        )
        NovelDetailsDialog.ChapterSettings -> NovelChapterSettingsDialog(
            sorting = state.sorting,
            sortDescending = state.sortDescending,
            readFilter = state.readFilter,
            bookmarkedFilter = state.bookmarkedFilter,
            downloadedFilter = state.downloadedFilter,
            hideChapterTitles = state.hideChapterTitles,
            onDismiss = viewModel::dismissDialog,
            onSortChange = viewModel::setSortOrder,
            onFilterChange = viewModel::setFilters,
            onDisplayChange = viewModel::setHideChapterTitles,
            onSetAsDefault = viewModel::setChapterSettingsAsDefault,
            onReset = viewModel::resetChapterSettings,
        )
        NovelDetailsDialog.PageSelector -> NovelPageSelectorSheet(
            pages = state.pages,
            selectedIndex = state.pageIndex,
            onSelect = viewModel::selectPage,
            onDismiss = viewModel::dismissDialog,
        )
        is NovelDetailsDialog.Migrate -> EntryMigrateFor(
            contentType = ContentType.NOVELS,
            currentId = dialog.currentId,
            targetId = dialog.targetId,
            onDismissRequest = viewModel::dismissDialog,
        )
        else -> {}
    }
}

// Map a novel dialog to the shared union for the dialogs both content types render (EntryDetailsDialogHost);
// the per-type ones (change-category, duplicate, chapter-settings, page-selector) stay in NovelDetailsDialogs.
private fun NovelDetailsState.Loaded.toSharedDetailsDialog(): EntryDetailsDialog? =
    when (val d = dialog) {
        NovelDetailsDialog.EditInfo -> EntryDetailsDialog.EditInfo(
            // Seed from the ANCHOR, not displayNovel: save diffs each field against the anchor row, so
            // seeding from a selected merge sibling would persist its every differing field as an
            // override on an untouched Save. Matches the manga side (seed and diff share one entry).
            initial = novel.withCustomInfo(customInfo).toEntryEditInfoUi(),
            source = novel.toEntryEditInfoUi(),
            seedColor = seedColor,
            coverModel = { url ->
                NovelCover(
                    url = url.ifBlank { null },
                    site = sourceUrl,
                    isNovelFavorite = novel.favorite,
                    lastModified = novel.coverLastModified,
                    novelId = novel.id,
                )
            },
        )
        NovelDetailsDialog.FullCover -> EntryDetailsDialog.Cover
        is NovelDetailsDialog.ManageSources -> EntryDetailsDialog.ManageSources(
            sources = d.sources,
            isOverridden = d.isOverridden,
        )
        NovelDetailsDialog.TrackSheet -> EntryDetailsDialog.TrackSheet(
            entryId = novel.id,
            entryTitle = novel.title,
            sourceId = null,
            isNovel = true,
        )
        is NovelDetailsDialog.DeleteChapters -> EntryDetailsDialog.DeleteChapters(d.chapters.map { it.id })
        else -> null
    }

/** Seed the shared edit-info dialog from a novel's effective (edited) values. */
private fun Novel.toEntryEditInfoUi() = EntryEditInfoUi(
    title = title,
    author = author.orEmpty(),
    artist = artist.orEmpty(),
    description = description.orEmpty(),
    genre = genre.orEmpty(),
    status = status,
    thumbnailUrl = thumbnailUrl.orEmpty(),
)
