package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.MergedChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.expandToUnits
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ReaderPosition
import reikai.domain.reader.isChapterComplete
import reikai.domain.reader.neighbourChapter
import reikai.domain.reader.removeDuplicateChapters
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.withCustomInfo
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.util.Date
import kotlin.time.Clock

/**
 * Presenter used by the activity to perform background operations.
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val imageSaver: ImageSaver,
    val readerPreferences: ReaderPreferences,
    private val basePreferences: BasePreferences,
    private val downloadPreferences: DownloadPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackChapter: TrackChapter,
    private val getManga: GetManga,
    // RK: Edit info overrides, so auto-webtoon can classify from edited genres.
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getNextChapters: GetNextChapters,
    private val upsertHistory: UpsertHistory,
    private val updateChapter: UpdateChapter,
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val getIncognitoState: GetIncognitoState,
    private val libraryPreferences: LibraryPreferences,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    private val chapterCache: ChapterCache,
    private val downloadCache: DownloadCache,
    // RK -->
    private val mergedChapterProvider: MergedChapterProvider,
    private val mangaPreferences: MangaPreferences,
    private val setReadStatus: SetReadStatus,
    // RK <--
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(ReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): ReaderViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): ReaderViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    /**
     * Ids of the manga and chapter the reader was launched with, taken from the activity intent.
     */
    val mangaId = savedState.get<Long>("manga") ?: -1L
    private val initialChapterId = savedState.get<Long>("chapter") ?: -1L

    val hasValidArgs = mangaId != -1L && initialChapterId != -1L

    // RK: buffered rather than upstream's rendezvous, which drops from the two trySend senders
    // (preload and onPageSelected) whenever the single collector is not parked at receive. A lost
    // PageChanged does not heal: DisplayRefreshHost counts calls and flashes every nth, so one drop
    // shifts the e-ink flash phase for the rest of the session. A lost ReloadViewerChapters leaves a
    // preloaded chapter that never reaches the viewer. Upstream has the same shape.
    private val eventChannel = Channel<Event>(Channel.UNLIMITED)
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    // RK -->

    /**
     * The page a launch extra asked to open at, for example a page preview jumping into a chapter.
     * It has its own key because it shares nothing but a shape with the restore index above: this is
     * a one-off jump, that is a position being kept up to date, and one slot could not be both.
     */
    private var launchPageIndex: Int? = savedState.get<Int>("launch_page")
        set(value) {
            savedState["launch_page"] = value
            field = value
        }

    /**
     * A restored index belongs to the chapter that was open when the process died, so only the first
     * chapter to load may take it. Without this the index was re-applied to every chapter that became
     * current, so each one briefly opened at the previous chapter's page.
     */
    private var restorePending = chapterPageIndex >= 0
    // RK <--

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    // RK: a merge-aware loader that routes each chapter to its own source's loader (a plain
    // single-source manga just gets one delegate, same behaviour as before).
    private var loader: MergedChapterLoader? = null

    // RK: the resolved merge group (member manga + unified chapter list). Null until [init] resolves
    // it; for an unmerged manga it holds just that manga and its own chapters.
    private var mergedGroup: MergedChapterProvider.Group? = null

    // RK: source scope narrows chapterList to the opened source's own chapters (Updates / a specific
    // source chip); group scope (default) shows the whole merge group. Read from the launching intent
    // like the ids above, so it survives a configuration change. mergedGroup stays full either way, so
    // the mark-duplicates-read pass over unfilteredChapterList still reaches sibling sources.
    private val sourceScoped = savedState.get<Boolean>("source_scoped") ?: false

    // RK: resolve a chapter's own manga within the merge group, falling back to the opened manga when
    // unmerged or when the id isn't in the group. Per-chapter side effects (downloads, tracker,
    // delete-on-read) target the chapter's real source instead of the manga the reader was opened from.
    private fun mangaForChapterId(chapterMangaId: Long?): Manga =
        chapterMangaId?.let { mergedGroup?.mangaById?.get(it) } ?: manga!!

    // RK: per-source "is this chapter downloaded" check for the transition card. Resolves the
    // chapter's OWN merged source, and uses the in-memory download cache (skipCache = false) instead
    // of a storage-access probe, so the card binds once with the right value: no main-thread stutter
    // and no late layout reflow (an icon popping in) when crossing into another merged source.
    fun isChapterDownloaded(readerChapter: ReaderChapter?): Boolean {
        val chapter = readerChapter?.chapter ?: return false
        val mangaForChapter = mangaForChapterId(chapter.manga_id)
        if (mangaForChapter.isLocal()) return true
        return downloadManager.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            mangaForChapter.title,
            mangaForChapter.source,
        )
    }

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        // RK: span the whole merge group so "mark same-number duplicates read" reaches sibling
        // sources too; for an unmerged manga this is just its own chapters, as before.
        val ids = mergedGroup?.mangaById?.keys ?: setOf(manga!!.id)
        runBlocking { ids.flatMap { getChaptersByMangaId.await(it, applyScanlatorFilter = false) } }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    // RK: the skip-filtered set. It is no longer what the reader pages over (see [fullChapterList]); it
    // only says which chapters a FORWARD step is allowed to land on.
    private val chapterList by lazy { buildChapterList(applyReadFilter = true) }

    // RK: every chapter, and the list the reader actually navigates and the chapter sheet renders.
    // "Skip chapters marked read" means do not stop on a read chapter as you move forward, not make it
    // unreachable, so going back must always reach the chapter you just finished. Sharing
    // [buildChapterList] keeps this the same deduplicated cross-source list, so the sheet, the reader and
    // the merged details list all agree on what exists.
    private val fullChapterList by lazy { buildChapterList(applyReadFilter = false) }

    private fun buildChapterList(applyReadFilter: Boolean): List<ReaderChapter> {
        val manga = manga!!
        // RK: source scope shows only the opened source's own chapters; group scope (default) shows
        // the unified cross-source list resolved in init (falling back to the single-source list if
        // accessed before init). A group-scoped chapter opened from outside the merged view (history)
        // can be deduped out of the unified list, so it is re-added via withOpenedChapter below, which
        // restamps it into the list's own sourceOrder scale; appending it raw would misplace it once
        // sorted, breaking prev/next. In source scope the opened chapter is always present, so that
        // re-add is a no-op.
        val merged = if (sourceScoped) {
            runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }
        } else {
            mergedGroup?.chapters
                ?: runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }
        }
        val chapters = mergedChapterProvider.withOpenedChapter(
            merged,
            merged.find { it.id == chapterId }
                ?: runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }
                    .find { it.id == chapterId },
        )

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            applyReadFilter &&
                (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    // RK: the filter PREFS stay the opened manga's (the user's current context), but
                    // the downloaded check resolves to each chapter's OWN source so a merged chapter
                    // is probed in the right download folder.
                    val chapterManga = mangaForChapterId(it.mangaId)
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            chapterManga.title,
                                            chapterManga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            chapterManga.title,
                                            chapterManga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        return chaptersForReader
            // RK --> drop user-hidden chapters so reader navigation skips them (twin of the details
            // filter); keep the opened chapter so opening a hidden one directly still resolves.
            .run {
                val hidden = mangaPreferences.hiddenChapters().get()
                if (hidden.isEmpty()) {
                    this
                } else {
                    val filtered = filterNot { "${mangaForChapterId(it.mangaId).source}|${it.url}" in hidden }
                    if (filtered.any { it.id == chapterId }) filtered else filtered + selectedChapter
                }
            }
            // RK <--
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    // RK: the shared dedup kernel, so novels drop duplicates by the same rule.
                    removeDuplicateChapters(
                        selectedChapter,
                        numberOf = { it.chapterNumber },
                        idOf = { it.id },
                        originOf = { it.scanlator },
                    )
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga, downloadCache)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private var incognitoMode: Boolean = false
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        // RK: this collector has to be subscribed before the first chapter arrives, because the
        // three image viewers read currChapter.requestedPage directly the moment setChapters reaches
        // them. A page stamped after that is a page they never see, and the chapter silently opens at
        // its start instead of where the reader left it. It wins today only because launchIn runs
        // here during construction, ahead of the init() call below, so do not move it behind a lazy
        // or WhileSubscribed flow.
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                // RK: each source of an opening page is consumed at most once, so only the chapter it
                // was meant for takes it and every later one resumes from its own stored page.
                val launchPage = launchPageIndex
                when {
                    launchPage != null -> {
                        currentChapter.requestedPage = launchPage
                        launchPageIndex = null
                    }
                    restorePending -> {
                        // Restore from SavedState
                        currentChapter.requestedPage = chapterPageIndex
                        restorePending = false
                    }
                    !currentChapter.chapter.read -> {
                        currentChapter.requestedPage = currentChapter.chapter.last_page_read
                    }
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        if (hasValidArgs) {
            viewModelScope.launch { init() }
        }
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Initializes this presenter with the [mangaId] and [initialChapterId] the reader was launched
     * with. This method will fetch the manga from the database and initialize the initial chapter.
     * Failures are reported through [State.initError].
     */
    private suspend fun init() {
        withIOContext {
            try {
                val manga = getManga.await(mangaId) ?: error("Requested manga of id $mangaId not found")
                // RK: resolve the Edit info overrides before the state update below builds the
                // viewer, since auto-webtoon classifies off them.
                customInfo = getCustomMangaInfo.subscribe(mangaId).first()
                if (chapterId == -1L) chapterId = initialChapterId

                // RK --> resolve the merge group up front so the chapter list spans every grouped
                // source and the loader can route each chapter to its own source. Resolved BEFORE
                // the state update below, because that update is what makes ReaderActivity build
                // the viewer, and auto-webtoon has to see every merged member to classify.
                val group = mergedChapterProvider.load(manga)
                mergedGroup = group
                loader = MergedChapterLoader(
                    context,
                    downloadManager,
                    downloadProvider,
                    chapterCache,
                    readerPreferences,
                    group.mangaById,
                    sourceManager,
                )
                // RK <--

                memberSourceNames = group.mangaById.values
                    .map { it.source }
                    .distinct()
                    .associateWith { sourceManager.getOrStub(it).name }

                val source = sourceManager.getOrStub(manga.source)
                incognitoMode = getIncognitoState.await(manga.source)
                mutableState.update { it.copy(manga = manga, source = source) }

                // RK: from the full list, so the reader pages within one instance space (prev/next
                //     are resolved there too) rather than mixing it with the skip-filtered set.
                loadChapter(loader!!, fullChapterList.first { chapterId == it.chapter.id })
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: MergedChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        // RK: page over the full list so BACK always reaches the chapter just finished, even once it is
        // marked read. Forward still honours the reader's skip filters by stepping to the next chapter
        // that survived them, so "skip chapters marked read" keeps working in the direction it means.
        // Matched by id, not instance: the two lists are built separately and hold different objects.
        val chapterPos = fullChapterList.indexOfFirst { it.chapter.id == chapter.chapter.id }
        val forwardIds = chapterList.mapTo(HashSet()) { it.chapter.id }
        val eligible = { candidate: ReaderChapter -> candidate.chapter.id in forwardIds }
        val newChapters = ViewerChapters(
            chapter,
            fullChapterList.neighbourChapter(chapterPos, forward = false, eligible),
            fullChapterList.neighbourChapter(chapterPos, forward = true, eligible),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            manga ?: return
            val dbChapter = chapter.chapter
            // RK: probe the chapter's own source's download folder, not the opened manga's.
            val chapterManga = mangaForChapterId(dbChapter.manga_id)
            val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                chapterManga.title,
                sourceManager.getOrStub(chapterManga.source),
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            // RK: download-ahead follows the next chapter's OWN source across a merge boundary.
            val nextChapterManga = mangaForChapterId(nextChapter.manga_id)
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                nextChapterManga.title,
                nextChapterManga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(nextChapterManga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicateChapters(
                        nextChapter.toDomainChapter()!!,
                        numberOf = { it.chapterNumber },
                        idOf = { it.id },
                        originOf = { it.scanlator },
                    )
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                nextChapterManga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        // RK: positioned in the full list, matched by id. The reader pages over that list, so indexing
        //     the skip-filtered one would not find the current chapter at all and silently never delete.
        val currentChapterPosition = fullChapterList.indexOfFirst { it.chapter.id == currentChapter.chapter.id }
        val chapterToDelete = if (currentChapterPosition < 0) {
            null
        } else {
            fullChapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)
        }

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        // RK: the position names its own chapter, so the chrome cannot pair this page number with a
        // different chapter's total while the model is still swapping the active chapter across a seam.
        val progress = ChapterProgress.Pages(
            lastPageRead = pageIndex.toLong(),
            pageCount = (readerChapter.pages?.size ?: 0).toLong(),
        )
        mutableState.update {
            it.copy(position = ReaderPosition(chapterId = readerChapter.chapter.id!!, progress = progress))
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            if (progress.isChapterComplete) {
                updateChapterProgressOnComplete(readerChapter)
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                    // RK: the reader is the only thing that ever knows this, so it is written on every
                    // save rather than once, which is also what re-heals a total left stale by a source
                    // re-paginating the chapter.
                    pageCount = readerChapter.pages?.size?.toLong(),
                ),
            )
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        // RK: the group's other copies of THIS chapter, taken from the stored stitch rather than by
        // matching numbers: two sources of one series count differently, so a number match marked a
        // chapter several along on the sibling. Empty when unmerged, which is the upstream behaviour.
        val readChapterId = readerChapter.chapter.id ?: return
        val copies = groupCopyIds(readChapterId).toSet()
        val duplicateUnreadChapters = unfilteredChapterList
            .filter { !it.read && it.id in copies && it.id != readChapterId }
            .map { ChapterUpdate(id = it.id, read = true) }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    // RK --> R12: mark the chapter the user skipped past (forward only) as read, opt-in.
    // Reuses updateChapterProgressOnComplete for tracker sync / delete-on-read / duplicates;
    // it does not persist the read flag itself (its normal caller does during a page-progress
    // save, which a forward skip never reaches), so we persist it here.
    fun markChapterReadOnSkip(readerChapter: ReaderChapter) {
        if (readerChapter.chapter.read || incognitoMode || !readerPreferences.markReadOnSkip.get()) return
        viewModelScope.launchNonCancellable {
            updateChapterProgressOnComplete(readerChapter)
            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = true,
                ),
            )
        }
    }
    // RK <--

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            chapterReadStartTime = null
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        // RK --> mark-read-on-skip (R12): the chapter being left behind on a forward skip
        val departedChapter = getCurrentChapter()
        // RK <--
        loadAdjacent(nextChapter)
        // RK -->
        departedChapter?.let { markChapterReadOnSkip(it) }
        // RK <--
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = state.value.source as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            // RK: auto-webtoon only fills in for a series the user never chose a mode for, so it
            // rides the existing DEFAULT branch and inherits its resolveDefault guard.
            resolveDefault && readingMode == ReadingMode.DEFAULT -> autoWebtoonMode() ?: default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    // RK -->
    private var autoWebtoonMemo: Pair<Set<Long>, Int?>? = null

    // RK: source names for every merged member, resolved once in init. The classifier runs on every
    // app-bar recomposition and resolving a source now suspends, so the names are read where the
    // merge group already is rather than blocking the render path.
    private var memberSourceNames: Map<Long, String> = emptyMap()

    /**
     * The user's Edit info overrides, snapshotted in [init]. Editing needs the details screen, so
     * these cannot change while the reader is open.
     */
    private var customInfo: CustomMangaInfo? = null

    /**
     * The mode auto-webtoon picks for this series, or null when it does not apply: the preference
     * is off, the user picked a mode for this series, or no merged source calls it long strip.
     *
     * The toast reads this too, so both it and the viewer resolve from one predicate and cannot
     * disagree. Memoized on the member ids, because [getMangaReadingMode] runs on every app-bar
     * recomposition while a group's genres and sources cannot change mid-session.
     */
    fun autoWebtoonMode(): Int? {
        if (!readerPreferences.autoWebtoonMode.get()) return null
        val manga = manga ?: return null
        if (ReadingMode.fromPreference(manga.readingMode.toInt()) != ReadingMode.DEFAULT) return null
        val members = mergedGroup?.mangaById?.values?.takeIf { it.isNotEmpty() } ?: listOf(manga)
        val memberIds = members.mapTo(mutableSetOf()) { it.id }
        autoWebtoonMemo?.takeIf { it.first == memberIds }?.let { return it.second }
        // Edited genres win over the source's, so a source that never tags its series type can be
        // fixed by hand in Edit info. They belong to the opened entry, not to its siblings.
        val entries = members.map { if (it.id == manga.id) it.withCustomInfo(customInfo) else it }
        return defaultReaderType(entries) { memberSourceNames[it.source] }
            .also { autoWebtoonMemo = memberIds to it }
    }
    // RK <--

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // RK: the update below re-emits this same ViewerChapters instance, which the
                // distinctUntilChanged in front of both the stamping collector above and the
                // activity's viewerChapters collector swallows. So the event sent afterwards is the
                // only thing that reaches the viewer, and the page has to be stamped by hand here
                // because the collector that normally does it never runs.
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // RK: same re-emit and hand-stamp as setMangaReadingMode, for the same reason.
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    /** Snapshot of the reader's chapter list for the in-reader chapter dialog (Y10). */
    fun getChapters(): List<ReaderChapterItem> {
        manga ?: return emptyList()
        // RK: in a merged group each row carries its OWN source's manga (so the download indicator is
        // correct) and a source-name label; an unmerged manga gets no label, as before.
        val merged = mergedGroup?.isMerged == true
        // RK: the sheet lists every chapter, including ones the reader's skip-read navigation steps
        // over. Filtering them out here made already-read chapters look like they did not exist.
        return fullChapterList.map {
            val dbChapter = it.chapter
            ReaderChapterItem(
                chapter = dbChapter.toDomainChapter()!!,
                manga = mangaForChapterId(dbChapter.manga_id),
                sourceName = if (merged) mergedGroup?.sourceNameByMangaId?.get(dbChapter.manga_id) else null,
            )
        }
    }

    /** Jump to an arbitrary chapter chosen in the chapter dialog (Y10). */
    fun loadNewChapterFromDialog(chapter: Chapter) {
        viewModelScope.launchIO {
            // RK: resolve against the sheet's list, so tapping an already-read chapter actually opens it
            // instead of silently doing nothing when skip-read has removed it from the navigation list.
            val newChapter = fullChapterList.firstOrNull { it.chapter.id == chapter.id }
                ?: return@launchIO
            loadAdjacent(newChapter)
        }
    }

    // RK: the chapter-list swipe actions follow the same prefs as the details list, with start/end crossed
    // to match those screens so a given swipe direction does the same thing everywhere.
    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()

    // RK: the sheet's list and the navigation list are built separately, so they hold different chapter
    // instances for the same row, and a chapter the navigation list skipped (read, with skip-read on)
    // is only in the sheet's. A dialog action therefore has to update every copy it finds, or the change
    // shows in one list and not the other, or silently does nothing for a read chapter.
    private fun chapterCopies(chapterId: Long) = listOfNotNull(
        chapterList.find { it.chapter.id == chapterId }?.chapter,
        fullChapterList.find { it.chapter.id == chapterId }?.chapter,
    )

    // RK: every source's copy of a chapter within the merge group, so a read or bookmark from the chapter
    // sheet applies group-wide like the details screen's does, instead of only to the source the row came
    // from. Reads the stored stitch, which is the one place that decides what "the same chapter" means:
    // matching on the number instead reached a chapter several along on a sibling source, and collapsed
    // every gallery source's chapter 1 into one. Falls back to the chapter alone when unmerged.
    private fun groupCopyIds(chapterId: Long): List<Long> {
        val stitch = mergedGroup?.takeIf { it.isMerged }?.stitch ?: return listOf(chapterId)
        return expandToUnits(setOf(chapterId), stitch).toList().ifEmpty { listOf(chapterId) }
    }

    /** Toggle the bookmark of an arbitrary chapter from the chapter dialog (Y10). */
    fun toggleBookmark(chapterId: Long, bookmarked: Boolean) {
        val ids = groupCopyIds(chapterId)
        val copies = ids.flatMap { chapterCopies(it) }
        if (copies.isEmpty()) return
        copies.forEach { it.bookmark = bookmarked }
        viewModelScope.launchNonCancellable {
            updateChapter.awaitAll(ids.map { ChapterUpdate(id = it, bookmark = bookmarked) })
        }
    }

    /** Set the read state of an arbitrary chapter from the chapter dialog. Uses SetReadStatus so tracker
     *  sync + delete-after-read fire like the details "mark as read", not just a raw read-flag write. */
    fun setChapterReadStatus(chapter: Chapter, read: Boolean) {
        val ids = groupCopyIds(chapter.id)
        ids.forEach { id -> chapterCopies(id).forEach { it.read = read } }
        viewModelScope.launchNonCancellable {
            val targets = ids.mapNotNull { id -> unfilteredChapterList.find { it.id == id } }
                .ifEmpty { listOf(chapter) }
            setReadStatus.await(read, *targets.toTypedArray())
        }
    }

    /** Start/cancel/delete a chapter download from the chapter dialog (Y10). */
    fun handleChapterDownload(chapter: Chapter, action: ChapterDownloadAction) {
        manga ?: return
        viewModelScope.launchIO {
            // RK: act on the chapter's own source so download/delete hit the right folder for a
            // merged chapter.
            val chapterManga = mangaForChapterId(chapter.mangaId)
            when (action) {
                ChapterDownloadAction.START -> downloadManager.downloadChapters(chapterManga, listOf(chapter))
                ChapterDownloadAction.START_NOW -> downloadManager.startDownloadNow(chapter.id)
                ChapterDownloadAction.CANCEL -> {
                    val download = downloadManager.getQueuedDownloadOrNull(chapter.id) ?: return@launchIO
                    downloadManager.cancelQueuedDownloads(listOf(download))
                }
                ChapterDownloadAction.DELETE -> {
                    downloadManager.deleteChapters(
                        listOf(chapter),
                        chapterManga,
                        sourceManager.getOrStub(chapterManga.source),
                    )
                }
            }
        }
    }
    // RK <--

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(page: ReaderPage) {
        if (page.status != Page.State.Ready) return
        val manga = manga ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(page: ReaderPage, copyToClipboard: Boolean) {
        if (page.status != Page.State.Ready) return
        val manga = manga ?: return

        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover(page: ReaderPage) {
        if (page.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(coverManager, stream(), updateManga, coverCache)
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        manga ?: return
        // RK: sync the tracker of the chapter's OWN source (per-source; trackers are propagated across
        // the merge group anyway, so this stays consistent after an unmerge).
        val chapterManga = mangaForChapterId(readerChapter.chapter.manga_id)

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, chapterManga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        manga ?: return
        // RK: delete-after-read targets the chapter's own source's download.
        val chapterManga = mangaForChapterId(chapter.chapter.manga_id)

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), chapterManga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val source: Source? = null,
        val initError: Throwable? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        // RK -->
        val position: ReaderPosition? = null,
        // RK <--

        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        // RK -->

        /**
         * The chapter the chrome describes, which is not always [currentChapter]: the viewer crosses
         * into the next chapter before the model swaps that asynchronously, so reading the title from
         * it used to pair one chapter's page number with another's title and total.
         */
        val visibleChapter: ReaderChapter?
            get() {
                val id = position?.chapterId
                val chapters = viewerChapters ?: return null
                return when (id) {
                    chapters.currChapter.chapter.id -> chapters.currChapter
                    chapters.prevChapter?.chapter?.id -> chapters.prevChapter
                    chapters.nextChapter?.chapter?.id -> chapters.nextChapter
                    else -> null
                } ?: currentChapter
            }
        // RK <--
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}
