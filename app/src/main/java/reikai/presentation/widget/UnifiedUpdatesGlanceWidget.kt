package reikai.presentation.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.annotation.ExperimentalCoilApi
import coil3.asDrawable
import coil3.executeBlocking
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.transform.RoundedCornersTransformation
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.flow.combine
import mihon.app.di.appGraph
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.dedupeByMergeGroup
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.NovelUpdateWithRelations
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.BaseUpdatesGridGlanceWidget
import tachiyomi.presentation.widget.R
import tachiyomi.presentation.widget.components.LockedWidget
import tachiyomi.presentation.widget.util.appWidgetBackgroundRadius
import tachiyomi.presentation.widget.util.appWidgetInnerRadius

// Slightly larger than the stock manga widget's 58x87 so the covers fill more of a taller widget.
private val CoverWidth = 78.dp
private val CoverHeight = 117.dp

/**
 * Home-screen widget showing recent updates for BOTH content types in one grid, split into a labeled
 * "Novels" strip and a "Manga" strip (the section labels disambiguate the otherwise identical cover
 * cells). Reikai-net-new, so it lives in the app module rather than presentation-widget: it needs the
 * novel query + cover model that live here, and reuses presentation-widget's public widget pieces.
 *
 * Mihon's [BaseUpdatesGridGlanceWidget] stays the manga-only widget; this is added alongside it.
 */
class UnifiedUpdatesGlanceWidget : GlanceAppWidget() {

    @Inject private lateinit var getUpdates: GetUpdates

    @Inject private lateinit var novelRepository: NovelRepository

    @Inject private lateinit var preferences: SecurityPreferences

    // Per-entry custom cover overrides, overlaid on the widget's cover cells (display-only).
    @Inject private lateinit var getCustomMangaInfo: GetCustomMangaInfo

    @Inject private lateinit var getCustomNovelInfo: GetCustomNovelInfo

    // A merge group is one series, so it gets one cover here as it gets one library row.
    @Inject private lateinit var mergeGroupRepository: MergeGroupRepository

    @Inject private lateinit var reikaiLibraryPreferences: ReikaiLibraryPreferences

    override val sizeMode = SizeMode.Exact

    private val foreground = ColorProvider(R.color.appwidget_on_secondary_container)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.appGraph.inject(this)
        val locked = preferences.useAuthenticator.get()
        val containerModifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.appwidget_background))
            .appWidgetBackground()
            .appWidgetBackgroundRadius()

        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(javaClass)
        val size = ids
            .flatMap { manager.getAppWidgetSizes(it) }
            .maxByOrNull { it.height.value * it.width.value }
        // How many cover cells fit, sized for this widget's larger covers (cover + cell padding).
        val rowCount = ((size?.height?.value ?: 0f) / (CoverHeight.value + 12)).toInt().coerceIn(1, 10)
        val columnCount = ((size?.width?.value ?: 0f) / (CoverWidth.value + 6)).toInt().coerceIn(1, 10)

        // Resolve strings here, not in the composition: Glance has no Compose LocalContext, so the
        // @Composable stringResource() helper crashes inside provideContent.
        val novelsLabel = context.stringResource(MR.strings.content_type_novels)
        val mangaLabel = context.stringResource(MR.strings.content_type_manga)
        val emptyLabel = context.stringResource(MR.strings.information_no_recent)

        provideContent {
            if (locked) {
                LockedWidget(foreground = foreground, modifier = containerModifier)
                return@provideContent
            }

            val flow = remember {
                val after = BaseUpdatesGridGlanceWidget.DateLimit.toEpochMilliseconds()
                combine(
                    getUpdates.subscribe(read = false, after = after),
                    novelRepository.getRecentNovelUpdatesAsFlow(after, ROW_LIMIT),
                    getCustomMangaInfo.subscribeAll(),
                    getCustomNovelInfo.subscribeAll(),
                ) { manga, novel, customManga, customNovel ->
                    prepareSections(context, manga, novel, customManga, customNovel, rowCount, columnCount)
                }
            }
            val data by flow.collectAsState(initial = null)
            UnifiedUpdatesContent(
                data = data,
                contentColor = foreground,
                columnCount = columnCount,
                novelsLabel = novelsLabel,
                mangaLabel = mangaLabel,
                emptyLabel = emptyLabel,
                modifier = containerModifier,
            )
        }
    }

    /**
     * Loads the cover grid for each type. Both sections are always shown with equal height (the rows are
     * split evenly); a type with no updates renders a skeleton grid instead of collapsing, so the widget
     * stays balanced whatever is new.
     */
    @OptIn(ExperimentalCoilApi::class)
    private suspend fun prepareSections(
        context: Context,
        manga: List<UpdatesWithRelations>,
        novel: List<NovelUpdateWithRelations>,
        customManga: List<CustomMangaInfo>,
        customNovel: List<CustomNovelInfo>,
        rowCount: Int,
        columnCount: Int,
    ): SectionData {
        // Display-only custom-cover overlay (cover url only; the widget shows no titles), keyed by real id.
        val novelCoverOverlay = customNovel.associateBy { it.novelId }
        val mangaCoverOverlay = customManga.associateBy { it.mangaId }
        // The manga query already returns unread only (getUpdates read=false); filter the novel side to
        // match. Dedupe per series, then per merge group so a series grouped across sources draws one
        // cover, then give each section half the rows.
        val (mangaGroups, novelGroups) = withIOContext {
            if (reikaiLibraryPreferences.seriesMergingEnabled.get()) {
                mergeGroupRepository.getAllMemberships(ContentType.MANGA) to
                    mergeGroupRepository.getAllMemberships(ContentType.NOVELS)
            } else {
                emptyMap<Long, Long>() to emptyMap()
            }
        }
        val novelRows = novel.filter { !it.read }.distinctBy { it.novelId }
            .dedupeByMergeGroup(novelGroups) { it.novelId }
        val mangaRows = manga.distinctBy { it.mangaId }.dedupeByMergeGroup(mangaGroups) { it.mangaId }
        val perSectionRows = (rowCount / 2).coerceAtLeast(1)
        val cap = perSectionRows * columnCount

        val widthPx = CoverWidth.value.toInt().dpToPx
        val heightPx = CoverHeight.value.toInt().dpToPx
        val roundPx = context.resources.getDimension(R.dimen.appwidget_inner_radius)

        return withIOContext {
            val novelCovers = novelRows
                .take(cap)
                .map { row ->
                    val cover = row.coverData.copy(
                        url = novelCoverOverlay[row.novelId]?.thumbnailUrl ?: row.coverData.url,
                    )
                    WidgetCover(
                        bitmap = loadCover(context, cover, widthPx, heightPx, roundPx),
                        intent = novelIntent(context, row),
                    )
                }
            val mangaCovers = mangaRows
                .take(cap)
                .map { row ->
                    val cover = MangaCover(
                        mangaId = row.mangaId,
                        sourceId = row.sourceId,
                        isMangaFavorite = true,
                        url = mangaCoverOverlay[row.mangaId]?.thumbnailUrl ?: row.coverData.url,
                        lastModified = row.coverData.lastModified,
                    )
                    WidgetCover(
                        bitmap = loadCover(context, cover, widthPx, heightPx, roundPx),
                        intent = mangaIntent(context, row.mangaId),
                    )
                }
            SectionData(novel = novelCovers, manga = mangaCovers)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun loadCover(context: Context, model: Any, widthPx: Int, heightPx: Int, roundPx: Float): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(model)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .precision(Precision.EXACT)
            .size(widthPx, heightPx)
            .scale(Scale.FILL)
            .let {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    it.transformations(RoundedCornersTransformation(roundPx))
                } else {
                    it
                }
            }
            .build()
        return context.imageLoader.executeBlocking(request)
            .image
            ?.asDrawable(context.resources)
            ?.toBitmap()
    }

    private fun mangaIntent(context: Context, mangaId: Long) =
        Intent(context, Class.forName(Constants.MAIN_ACTIVITY)).apply {
            action = Constants.SHORTCUT_MANGA
            putExtra(Constants.MANGA_EXTRA, mangaId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Distinct PendingIntents per series, https://issuetracker.google.com/issues/238793260
            addCategory("manga:$mangaId")
        }

    private fun novelIntent(context: Context, row: NovelUpdateWithRelations) =
        Intent(context, Class.forName(Constants.MAIN_ACTIVITY)).apply {
            action = Constants.SHORTCUT_NOVEL
            putExtra(Constants.NOVEL_SOURCE_EXTRA, row.source)
            putExtra(Constants.NOVEL_URL_EXTRA, row.novelUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Distinct PendingIntents, namespaced so a novel id never collides with a manga id.
            addCategory("novel:${row.novelId}")
        }

    companion object {
        private const val ROW_LIMIT = 500L
    }
}

private data class WidgetCover(val bitmap: Bitmap?, val intent: Intent)

private data class SectionData(val novel: List<WidgetCover>, val manga: List<WidgetCover>)

@Composable
private fun UnifiedUpdatesContent(
    data: SectionData?,
    contentColor: ColorProvider,
    columnCount: Int,
    novelsLabel: String,
    mangaLabel: String,
    emptyLabel: String,
    modifier: GlanceModifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        when {
            data == null -> CircularProgressIndicator(color = contentColor)
            data.novel.isEmpty() && data.manga.isEmpty() -> Text(
                text = emptyLabel,
                style = TextStyle(color = contentColor),
            )
            else -> Column(
                modifier = GlanceModifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start,
            ) {
                // Both sections always render; a type with no updates keeps a cover row's height under its
                // label, so the layout looks the same whether or not that type has anything new.
                CoverSection(novelsLabel, data.novel, emptyLabel, columnCount, contentColor)
                CoverSection(mangaLabel, data.manga, emptyLabel, columnCount, contentColor)
            }
        }
    }
}

@Composable
private fun CoverSection(
    header: String,
    covers: List<WidgetCover>,
    emptyLabel: String,
    columnCount: Int,
    contentColor: ColorProvider,
) {
    Text(
        text = header,
        style = TextStyle(color = contentColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.Start),
        modifier = GlanceModifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
    if (covers.isEmpty()) {
        // Reserve a cover row's height (matching CellRow) so the empty section occupies the same space a
        // populated one would, and sit a "no updates" caption where the covers would be.
        Box(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).height(CoverHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyLabel,
                style = TextStyle(color = contentColor),
            )
        }
        return
    }
    val coverRows = (covers.size + columnCount - 1) / columnCount
    (0 until coverRows).forEach { i ->
        val rowItems = (0 until columnCount).mapNotNull { j -> covers.getOrNull(j + (i * columnCount)) }
        if (rowItems.isNotEmpty()) {
            CellRow {
                rowItems.forEach { item ->
                    Box(modifier = GlanceModifier.padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
                        WidgetCover(
                            cover = item.bitmap,
                            modifier = GlanceModifier.clickable(actionStartActivity(item.intent)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CellRow(content: @Composable RowScope.() -> Unit) {
    // No fillMaxWidth: the row wraps to its covers so the Column wraps too, letting the outer Box center
    // the whole block (equal left/right margins) while labels and covers stay left-aligned within it.
    Row(
        modifier = GlanceModifier.padding(vertical = 4.dp, horizontal = 9.dp),
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A single cover cell at this widget's [CoverWidth] x [CoverHeight]; the stock UpdatesMangaCover is locked to 58x87. */
@Composable
private fun WidgetCover(cover: Bitmap?, modifier: GlanceModifier) {
    Box(
        modifier = modifier
            .size(width = CoverWidth, height = CoverHeight)
            .appWidgetInnerRadius(),
    ) {
        Image(
            provider = cover?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.appwidget_cover_error),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize().appWidgetInnerRadius(),
            contentScale = ContentScale.Crop,
        )
    }
}
