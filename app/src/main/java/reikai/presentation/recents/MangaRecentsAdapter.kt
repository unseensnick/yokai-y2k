package reikai.presentation.recents

import android.content.Context
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.expandToUnits
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.reader.ChapterProgress
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedRepository
import reikai.domain.recents.RecentsUnreadRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.decideAdd
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * Adapts Mihon's two live models to the neutral [RecentsProvider]. Both stay live and upstream-tracked
 * (never made to implement a Reikai interface); this maps their rows and forwards each verb on.
 *
 * A model is absent where the surface renders no lane needing it, which keeps a History tab from
 * building the updates model and running its query. Nothing reaches an absent one: the engine asks
 * only for the lanes it renders, and [chapterActions] is null without the updates model.
 */
@AssistedInject
class MangaRecentsAdapter(
    // Assisted: the models belong to the surface that is composing, so only the call site has them.
    @Assisted private val updatesModel: UpdatesViewModel?,
    @Assisted private val historyModel: HistoryViewModel?,
    @Assisted private val surface: RecentsSurface,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val recentlyAdded: RecentlyAddedRepository,
    private val recentsUnread: RecentsUnreadRepository,
    private val getNextChapters: GetNextChapters,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val downloadManager: DownloadManager,
    // Read from the preference rather than off the model, whose copy is a Compose State the engine
    // cannot collect.
    private val libraryPreferences: LibraryPreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val mergeManager: MangaMergeManager,
    private val mergedChapterProvider: MergedChapterProvider,
    private val getManga: GetManga,
    private val mangaLibraryAdder: MangaLibraryAdder,
    private val application: Context,
) : RecentsProvider {

    /**
     * One entry point per surface, so the models a surface holds and the surface it says it is cannot
     * disagree, and neither can a caller ask for an adapter with no models at all. [create] is only
     * public because Metro's assisted factories cannot hide it; call the three named ones.
     */
    @AssistedFactory
    interface Factory {
        fun create(
            updatesModel: UpdatesViewModel?,
            historyModel: HistoryViewModel?,
            surface: RecentsSurface,
        ): MangaRecentsAdapter

        fun forUpdates(updatesModel: UpdatesViewModel) =
            create(updatesModel, historyModel = null, surface = RecentsSurface.UPDATES)

        fun forHistory(historyModel: HistoryViewModel) =
            create(updatesModel = null, historyModel = historyModel, surface = RecentsSurface.HISTORY)

        fun forRecents(updatesModel: UpdatesViewModel, historyModel: HistoryViewModel) =
            create(updatesModel, historyModel, surface = RecentsSurface.RECENTS)
    }

    override val contentType = ContentType.MANGA

    // A null list is this model's "no emission yet", where the updates model carries a loading flag.
    // Lazy so a surface that renders neither lane never touches the model it was not given.
    override val readLane: Flow<RecentsLaneRows> by lazy {
        historyRows().state.map { state ->
            RecentsLaneRows(
                items = state.list.orEmpty().map { it.toRecentsItem() },
                loaded = state.list != null,
            )
        }
    }

    override val updatedLane: Flow<RecentsLaneRows> by lazy {
        updatesRows().state.map { state ->
            RecentsLaneRows(items = state.items.map { it.toRecentsItem() }, loaded = !state.isLoading)
        }
    }

    private fun historyRows() = requireNotNull(historyModel) { "$surface renders no read lane" }

    private fun updatesRows() = requireNotNull(updatesModel) { "$surface renders no updated lane" }

    // The only lane with no model behind it: nothing rendered a newly-added feed before this surface.
    override val addedLane: Flow<RecentsLaneRows> =
        sourcePreferences.recentsCategoryFilterFlow(surface).flatMapLatest { categories ->
            recentlyAdded.subscribeManga(
                after = addedLaneCutoff(),
                limit = ADDED_LANE_LIMIT,
                includedCategories = categories.include,
                excludedCategories = categories.exclude,
            ).map { rows -> rows.map { it.toRecentsItem() } }
        }.asLane()

    override val unreadEntries: Flow<Set<EntryId>> =
        recentsUnread.subscribeMangaIdsWithUnread().map { ids -> ids.mapTo(HashSet(), EntryId::Manga) }

    override val lastUpdated: Flow<Long> = libraryPreferences.lastUpdatedTimestamp.changes()

    override val updating: Flow<Boolean> = LibraryUpdateJob.isRunningFlow(application)

    override val membership: Flow<Map<EntryId, Long>> =
        mergeManager.membershipFlow(reikaiLibraryPreferences.seriesMergingEnabled, EntryId::Manga)

    override suspend fun targetChapter(item: RecentsItem): ChapterRef? =
        resolveTarget(item)?.let { ChapterRef(item.entryId, it.chapterId) }

    override suspend fun targetRow(item: RecentsItem): RecentsTargetRow? {
        val resolved = resolveTarget(item) ?: return null
        val chapter = resolved.chapters[resolved.chapterId] ?: return null
        // Not necessarily this row's manga: a merged row resolves across the group, and the download
        // lookup is keyed by the owner's stored title and source.
        val owner = resolved.mangaById[chapter.mangaId] ?: return null
        return RecentsTargetRow(
            ref = ChapterRef(EntryId.Manga(owner.id), chapter.id),
            chapter = RecentsChapterUi.Number(chapter.chapterNumber),
            state = chapterState(
                read = chapter.read || chapter.id in resolved.readElsewhere,
                bookmark = chapter.bookmark || chapter.id in resolved.bookmarkedElsewhere,
                progress = ChapterProgress.Pages(chapter.lastPageRead, chapter.pageCount),
            ),
            download = chapterDownloadUi(
                chapterId = chapter.id,
                chapterName = chapter.name,
                scanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                storedTitle = owner.title,
                sourceId = owner.source,
            ),
        )
    }

    /** The chapter a lane's rule picked, with everything a row needs to be drawn from it. */
    private class TargetResolution(
        val chapterId: Long,
        val chapters: Map<Long, Chapter>,
        val mangaById: Map<Long, Manga>,
        /** Read or bookmarked on another source of the group, so the row says what the details list
         *  says rather than what the one copy the target rule picked happens to hold. */
        val readElsewhere: Set<Long> = emptySet(),
        val bookmarkedElsewhere: Set<Long> = emptySet(),
    )

    /**
     * Merge-aware on all three lanes: a collapsed row stands for the whole group, so it must not reopen
     * what another of its sources already read. An unmerged entry gets its own list back, so this is
     * the plain path too. Resolved per rendered row rather than at assembly, which is what keeps a
     * five-hundred-row feed from paying a chapter query per row on every emission.
     */
    private suspend fun resolveTarget(item: RecentsItem): TargetResolution? {
        val mangaId = item.entryId.rawId
        val manga = getManga.await(mangaId)
        val group = manga?.let { mergedChapterProvider.load(it) }
        val readElsewhere = group?.readInOtherSources.orEmpty()
        val groupChapters = readingOrder(manga, group?.chapters)
        // Every chapter a rule below could name, so the id it returns can be projected back into a
        // row. The own-source list is not a subset of the group's: the cross-source stitch drops the
        // copies another source stands in for.
        val chapters = groupChapters.associateByTo(mutableMapOf()) { it.id }
        suspend fun ownSource(): List<Chapter> =
            readingOrder(manga, getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true))
                .onEach { chapters[it.id] = it }

        val chapterId = when (val lane = item.lane) {
            is RecentsLane.Read -> resumeTarget(
                groupChapters.map { it.toRecentsChapter(readElsewhere) },
                lane.chapter.chapterId,
            ) { ownSource().map { it.toRecentsChapter(readElsewhere) } }
            is RecentsLane.Updated -> firstUnreadInBurst(
                // The burst is one source's: fetch times do not line up across sources, so only the
                // read-elsewhere carry-over crosses the group here.
                chapters = ownSource().map { it.toRecentsChapter(readElsewhere) },
                rowChapterId = lane.chapter.chapterId,
            )
            RecentsLane.Added -> firstUnreadOf(groupChapters.map { it.toRecentsChapter(readElsewhere) })
                ?: getNextChapters.await(mangaId, onlyUnread = true)
                    .firstOrNull { it.id !in readElsewhere }
                    ?.also { chapters[it.id] = it }
                    ?.id
        } ?: return null
        return TargetResolution(
            chapterId = chapterId,
            chapters = chapters,
            mangaById = group?.mangaById.orEmpty(),
            readElsewhere = readElsewhere,
            bookmarkedElsewhere = group?.let {
                flaggedOnAnotherSource(it.pooledChapters, it.chapters, it.stitch, { c -> c.id }, { c -> c.bookmark })
            }.orEmpty(),
        )
    }

    /**
     * Ascending reading order, which every shared target rule expects. A merged list is ordered by the
     * position the stitch restamped onto it, which runs newest-first as a manga source's own order
     * does; a chapter number is not comparable across sources, and sorting by one here reached a
     * different "first unread" than the library did for the same series. An unmerged list takes
     * Mihon's own comparator, the one `GetNextChapters` applies.
     */
    private fun readingOrder(manga: Manga?, chapters: List<Chapter>?): List<Chapter> = when {
        manga == null || chapters == null -> chapters.orEmpty()
        chapters.distinctBy { it.mangaId }.size > 1 -> chapters.sortedByDescending { it.sourceOrder }
        else -> chapters.sortedWith(getChapterSort(manga, sortDescending = false))
    }

    private fun Chapter.toRecentsChapter(readInOtherSources: Set<Long>) = RecentsChapter(
        id = id,
        fetchedAt = dateFetch,
        read = read || id in readInOtherSources,
    )

    override suspend fun latestRead(): RecentsItem? = historyModel?.getLast()?.toRecentsItem()

    /** Present only where the updates model is: every verb behind it acts on that model's rows. */
    override val chapterActions: RecentsChapterActions? = updatesModel?.let(::ModelChapterActions)

    private inner class ModelChapterActions(private val model: UpdatesViewModel) : RecentsChapterActions {

        // Each verb takes the neutral set and hands the model only its own content type's chapters,
        // so a mixed selection never reaches a provider that cannot act on it. Keyed by chapter id
        // rather than resolved against the rendered updates feed, which holds no read-lane row.
        private fun Set<ChapterRef>.ownIds(): List<Long> =
            filter { it.entryId is EntryId.Manga }.map { it.chapterId }

        // The group's copies of the same merged chapters, off the stored stitch, so a collapsed row's
        // verb reaches every source the way the details list's does.
        private suspend fun Set<ChapterRef>.groupChapterIds(): List<Long> = withIOContext {
            filter { it.entryId is EntryId.Manga }
                .groupBy { it.entryId.rawId }
                .flatMap { (mangaId, refs) ->
                    expandToUnits(refs.mapTo(HashSet()) { it.chapterId }, mergedChapterProvider.stitchOf(mangaId))
                }
                .distinct()
        }

        override suspend fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
            model.markUpdatesRead(chapters.groupChapterIds(), read)
        }

        override suspend fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
            model.bookmarkUpdates(chapters.groupChapterIds(), bookmarked)
        }

        override suspend fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction) {
            model.downloadChapters(chapters.ownIds(), action)
        }

        override suspend fun deleteDownloads(chapters: Set<ChapterRef>) {
            model.deleteChapters(chapters.groupChapterIds())
        }
    }

    override fun removeFromHistory(entries: Set<EntryId>) {
        entries.filterIsInstance<EntryId.Manga>().forEach { historyModel?.removeAllFromHistory(it.rawId) }
    }

    override fun removeHistoryRecord(item: RecentsItem) {
        val record = item.payload as? HistoryWithRelations ?: return
        historyModel?.removeFromHistory(record)
    }

    // The add flow answers rather than prompts: the engine owns the surface's one dialog slot, so
    // everything below reports what it found and lets the engine decide what to ask.
    private suspend fun mangaOf(entry: EntryId): Manga? =
        (entry as? EntryId.Manga)?.let { getManga.await(it.rawId) }

    override suspend fun addDecision(entry: EntryId): AddDecision<RecentsDuplicates>? {
        val manga = mangaOf(entry) ?: return null
        return decideAdd(inLibrary = manga.favorite) {
            val duplicates = mangaLibraryAdder.getDuplicates(manga)
            if (duplicates.isEmpty()) return@decideAdd null

            val labels = mangaLibraryAdder.duplicateSourceLabels(duplicates)
            RecentsDuplicates(
                duplicates = duplicates.map {
                    RecentsDuplicate(EntryId.Manga(it.manga.id), it.toDuplicateCard(labels))
                },
                groupIdByRawId = mangaLibraryAdder.getDuplicateGroupIds(duplicates),
                suggestGroup = mangaLibraryAdder.suggestGrouping,
            )
        }
    }

    override suspend fun addToLibrary(entry: EntryId): AddFavoriteResult {
        val manga = mangaOf(entry) ?: return AddFavoriteResult.Failed
        // Already there: the shared add toggles the favorite, so running it again would remove the
        // entry and reset its dateAdded. The engine's remove branch normally catches this first.
        if (manga.favorite) return AddFavoriteResult.Added
        return mangaLibraryAdder.resolveAddFavorite(manga)
    }

    override suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>) {
        val manga = mangaOf(entry) ?: return
        mangaLibraryAdder.confirmAddCategories(manga.id, categoryIds)
    }

    override suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult {
        val manga = mangaOf(entry) ?: return AddFavoriteResult.Failed
        return mangaLibraryAdder.addToExistingGroup(manga, duplicates.map { it.rawId })
    }

    override suspend fun clearHistory(): Boolean = historyModel?.removeAllHistory() == true

    // Straight to the job rather than through the updates model, which only wraps this same call in a
    // snackbar event the shell now owns. It also lets a surface with no updated lane still refresh.
    override fun refresh(): Boolean = LibraryUpdateJob.startNow(application.workManager)

    override suspend fun detailsScreen(entry: EntryId): Screen? =
        (entry as? EntryId.Manga)?.let { MangaScreen(it.rawId) }

    override suspend fun open(item: RecentsItem): RecentsOpen? {
        val target = targetChapter(item) ?: return null
        return RecentsOpen.ReaderIntent(
            ReaderActivity.newIntent(
                application,
                item.entryId.rawId,
                target.chapterId,
                sourceScoped = item.lane.sourceScoped,
            ),
        )
    }

    override fun rowUi(item: RecentsItem): RecentsRowUi = mangaRowUi(item)

    override fun downloadUi(item: RecentsItem): RecentsDownloadUi? = when (val payload = item.payload) {
        is HistoryWithRelations -> historyDownloadUi(payload)
        else -> mangaDownloadUi(item)
    }

    /**
     * The read lane has no model computing this per row, so it asks the same two sources the updates
     * model does: the live queue first, then the on-disk index. Resolved on call rather than carried
     * on the row, because combining the whole history feed with the download queue would re-map every
     * row of it on each download tick. The combined modes' download indicator now calls it per drawn
     * row, alongside the selection's download verbs and the downloaded filter while it is on.
     */
    private fun historyDownloadUi(payload: HistoryWithRelations) = chapterDownloadUi(
        chapterId = payload.chapterId,
        chapterName = payload.chapterName,
        scanlator = payload.scanlator,
        chapterUrl = payload.chapterUrl,
        // The stored title, never the displayed one: a download folder is named from the former and
        // the history row carries the user's custom title in the latter.
        storedTitle = payload.storedTitle,
        sourceId = payload.sourceId,
    )

    /** One definition of a chapter's download state for this type, whichever chapter is asking. */
    private fun chapterDownloadUi(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        storedTitle: String,
        sourceId: Long,
    ) = RecentsDownloadUi(
        state = {
            val active = downloadManager.getQueuedDownloadOrNull(chapterId)
            when {
                active != null -> active.status
                downloadManager.isChapterDownloaded(
                    chapterName = chapterName,
                    chapterScanlator = scanlator,
                    chapterUrl = chapterUrl,
                    mangaTitle = storedTitle,
                    sourceId = sourceId,
                ) -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }
        },
        progress = RecentsDownloadProgress.Live {
            downloadManager.getQueuedDownloadOrNull(chapterId)?.progress ?: 0
        },
    )
}

/** The updates model already builds both providers per row, so this only hands them over. */
internal fun mangaDownloadUi(item: RecentsItem): RecentsDownloadUi? = when (val payload = item.payload) {
    is UpdatesItem -> RecentsDownloadUi(
        state = payload.downloadStateProvider,
        progress = RecentsDownloadProgress.Live(payload.downloadProgressProvider),
    )
    else -> null
}

/**
 * Free rather than a member for the same reason the item mappers below are: an adapter carries the
 * surface's live models, so a test can reach this without standing one up.
 */
internal fun mangaRowUi(item: RecentsItem): RecentsRowUi = when (val payload = item.payload) {
    is UpdatesItem -> RecentsRowUi(
        cover = payload.update.coverData,
        title = payload.update.mangaTitle,
        // updatesView is favorite-gated, so a row on this lane is always in the library.
        isFavorite = true,
        chapter = RecentsChapterUi.Named(payload.update.chapterName),
        state = chapterState(
            read = payload.update.read,
            bookmark = payload.update.bookmark,
            progress = ChapterProgress.Pages(payload.update.lastPageRead, payload.update.pageCount),
        ),
    )
    is HistoryWithRelations -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        // historyView is not favorite-gated: a read entry may never have been added.
        isFavorite = payload.coverData.isMangaFavorite,
        chapter = RecentsChapterUi.Number(payload.chapterNumber),
        state = chapterState(
            read = payload.read,
            bookmark = payload.bookmark,
            progress = ChapterProgress.Pages(payload.lastPageRead, payload.pageCount),
        ),
    )
    is RecentlyAddedManga -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        isFavorite = true,
        chapter = null,
        state = null,
    )
    else -> EMPTY_RECENTS_ROW
}

internal fun UpdatesItem.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Manga(update.mangaId),
    timestamp = update.dateFetch,
    lane = RecentsLane.Updated(ChapterRef(EntryId.Manga(update.mangaId), update.chapterId)),
    payload = this,
)

// readAt is a java.util.Date here and a Long on the novel side; the divergence dies at this seam.
internal fun HistoryWithRelations.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Manga(mangaId),
    timestamp = readAt?.time ?: 0L,
    lane = RecentsLane.Read(ChapterRef(EntryId.Manga(mangaId), chapterId)),
    payload = this,
)

internal fun RecentlyAddedManga.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Manga(mangaId),
    timestamp = dateAdded,
    lane = RecentsLane.Added,
    payload = this,
)
