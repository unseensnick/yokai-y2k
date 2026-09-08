package reikai.data.novel.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.refreshNovelFromSource
import reikai.data.updateerror.UpdateErrorEntry
import reikai.data.updateerror.UpdateErrorLog
import reikai.domain.category.GetNovelCategories
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.CollapsedArrivals
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.collapseNewChapters
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.updateerror.DeleteNovelUpdateErrors
import reikai.domain.novel.updateerror.UpsertNovelUpdateError
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.workRunningFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import java.util.concurrent.TimeUnit

/**
 * Periodic background check for new chapters in favorited light novels, the novel analog of Mihon's
 * [eu.kanade.tachiyomi.data.library.LibraryUpdateJob]: a configurable WorkManager schedule that
 * re-parses each favorite, syncs its chapter list, optionally auto-downloads the new chapters, and
 * posts progress and result notifications. Per-novel logic is the shared [refreshNovelFromSource], the
 * same helper the details refresh uses; new chapters come from a before/after chapter-id diff.
 */
class NovelUpdateJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    init {
        graph.inject(this)
    }

    @Inject private lateinit var novelRepo: NovelRepository

    @Inject private lateinit var chapterRepo: NovelChapterRepository

    @Inject private lateinit var database: Database

    @Inject private lateinit var downloadManager: NovelDownloadManager

    @Inject private lateinit var sourceManager: NovelSourceManager

    @Inject private lateinit var installer: LnPluginInstaller

    @Inject private lateinit var getNovelCategories: GetNovelCategories

    @Inject private lateinit var preferences: NovelPreferences

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var reikaiLibraryPreferences: ReikaiLibraryPreferences

    @Inject private lateinit var upsertNovelUpdateError: UpsertNovelUpdateError

    @Inject private lateinit var deleteNovelUpdateErrors: DeleteNovelUpdateErrors
    private val updateErrorLog = UpdateErrorLog(context)

    // Keeps a merged entry's deduplicated unread count in step with newly fetched chapters
    @Inject private lateinit var reconcileMergedChapters: ReconcileMergedChapters

    @Inject private lateinit var mergeGroupRepository: MergeGroupRepository

    @Inject private lateinit var mergedChapterUnitRepository: MergedChapterUnitRepository
    private val notifier = NovelUpdateNotifier(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progress("", 0, 0)
        val id = Notifications.ID_NOVEL_LIBRARY_PROGRESS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        // Stamp the run start for the Updates "Last updated" line (matches manga LibraryUpdateJob).
        preferences.novelLibraryUpdateLastTimestamp().set(System.currentTimeMillis())
        return try {
            val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
            withIOContext { updateNovels(categoryId) }
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.retry()
        } finally {
            notifier.dismissProgress()
        }
    }

    private suspend fun updateNovels(categoryId: Long) {
        // Both outlive the run itself, so a cancelled one still queues what it fetched and still counts
        // it: those chapters are in the database now and are never new again.
        val updates = mutableListOf<Pair<Novel, List<NovelChapter>>>()
        val pendingDownloads = mutableListOf<NovelChapter>()
        var counted: Int? = null
        try {
            counted = runUpdate(categoryId, updates, pendingDownloads)
        } finally {
            withContext(NonCancellable) {
                val arrivals = counted ?: updates.sumOf { it.second.size }
                if (arrivals > 0) libraryPreferences.newUpdatesCount.getAndSet { it + arrivals }
                if (pendingDownloads.isNotEmpty()) downloadManager.downloadChapters(pendingDownloads)
            }
        }
    }

    /** The run itself. Returns the arrivals the badge should carry, null when it did not finish. */
    private suspend fun runUpdate(
        categoryId: Long,
        updates: MutableList<Pair<Novel, List<NovelChapter>>>,
        pendingDownloads: MutableList<NovelChapter>,
    ): Int? {
        // One load brings every installed plugin into the host; per-novel resolution is then cheap.
        runCatching { installer.ensureLoaded() }

        // Category scope + smart-update restrictions both need suspend per-novel lookups, so filter in
        // a loop rather than a plain .filter. An explicit [categoryId] (a manual "update this category")
        // overrides the include/exclude prefs; smart-update restrictions still apply, matching manga.
        val trackErrors = reikaiLibraryPreferences.trackNovelUpdateErrors.get()
        val favorites = buildList {
            for (novel in novelRepo.getFavorites()) {
                val categoryOk = if (categoryId != -1L) {
                    categoryId in getNovelCategories.awaitByNovelId(novel.id).map { it.id }.ifEmpty { listOf(0L) }
                } else {
                    shouldUpdate(novel)
                }
                if (categoryOk && passesSmartUpdate(novel)) add(novel)
            }
        }
        if (favorites.isEmpty()) return null

        val downloadNew = preferences.downloadNewChapters().get()
        val failed = mutableListOf<UpdateErrorEntry>()
        favorites.forEachIndexed { index, novel ->
            currentCoroutineContext().ensureActive()
            // [index] is how many are already done, which is what the bar reports while this one runs.
            notifier.showProgress(novel.title, index, favorites.size)
            val source = sourceManager.get(novel.source) ?: return@forEachIndexed
            try {
                val newChapters = checkNovel(novel, source)
                if (newChapters.isNotEmpty()) {
                    updates.add(novel to newChapters)
                    // Queued after the run rather than here, so a merge group's sources cannot each
                    // fetch the same chapter.
                    if (downloadNew) {
                        pendingDownloads += filterChaptersForDownload(novel, newChapters)
                    }
                }
                // A successful check clears any previously recorded error.
                if (trackErrors) runCatching { deleteNovelUpdateErrors.byNovelIds(listOf(novel.id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Novel update failed: ${novel.title}" }
                val message = e.message ?: context.stringResource(MR.strings.unknown)
                failed += UpdateErrorEntry(novel.title, source.name, message)
                // Record the failure for the Update errors screen.
                if (trackErrors) {
                    runCatching { upsertNovelUpdateError.await(novel.id, message) }
                }
            }
            // Again once the entry is done, so the bar actually reaches its end; the manga job posts
            // the same pair around each entry.
            notifier.showProgress(novel.title, index + 1, favorites.size)
        }
        val announced = if (updates.isEmpty()) {
            updates
        } else {
            // New chapters change what a merged entry's deduplicated unread count should be, so bring
            // the stored cross-source identities back in step first. Cheap when nothing changed, and
            // only covers merged entries. Then count, announce and download one copy per merged
            // chapter: a group's sources each report the same chapter, and counting them apart told
            // the user it had arrived once per source.
            reconcileMergedChapters.await()
            val arrivals = collapseNewUpdates(updates)
            // One download per merged chapter, chosen among the copies this run found eligible rather
            // than by intersecting with the announced set: eligibility is per entry, so the copy that
            // may be downloaded is often not the copy the stitch ranks first.
            val collapsed = pendingDownloads.distinctBy { arrivals.dedupeKey(it.id) }
            pendingDownloads.clear()
            pendingDownloads.addAll(collapsed)
            updates.mapNotNull { (novel, chapters) ->
                chapters.filter { it.id in arrivals.announced }.takeIf { it.isNotEmpty() }?.let { novel to it }
            }
        }
        notifier.showResults(announced)
        // The dump is one file shared with the manga updater, rewritten on every run so a novel that
        // has since updated stops appearing in it.
        val errorFile = updateErrorLog.write(ContentType.NOVELS, failed)
        if (failed.isNotEmpty()) {
            notifier.showUpdateErrors(failed.size, errorFile.getUriCompat(context), trackErrors)
        }
        return announced.sumOf { it.second.size }
    }

    /** The chapter ids this run should act on, one per merged chapter. The twin of the manga job's,
     *  over the same kernel. */
    private suspend fun collapseNewUpdates(updates: List<Pair<Novel, List<NovelChapter>>>): CollapsedArrivals {
        val newByNovel = updates.associate { (novel, chapters) -> novel.id to chapters }
        val groupOf = mergeGroupRepository.getAllMemberships(ContentType.NOVELS)
            .filterKeys { it in newByNovel.keys }
        val stitches = groupOf.values.distinct()
            .associateWith { mergedChapterUnitRepository.getStitch(ContentType.NOVELS, it) }
        return collapseNewChapters(newByNovel, groupOf, stitches) { it.id }
    }

    /** Re-parse the novel, persist metadata edit-lock-safely, sync page 1, and walk any newly-opened
     *  pages. Returns the chapters that did not exist before (the before/after id diff). */
    private suspend fun checkNovel(novel: Novel, source: NovelSource): List<NovelChapter> {
        val before = chapterRepo.getByNovelId(novel.id).map { it.id }.toSet()
        refreshNovelFromSource(novel, source, chapterRepo, novelRepo, database, novelDownloadManager = downloadManager)
        val after = chapterRepo.getByNovelId(novel.id)
        return after.filter { it.id !in before }
    }

    /** Mirror of the manga FilterChaptersForDownload: gate by the novel's categories, then (when
     *  "skip duplicate read" is on) drop new chapters whose number matches an already-read one. */
    private suspend fun filterChaptersForDownload(novel: Novel, newChapters: List<NovelChapter>): List<NovelChapter> {
        if (!shouldDownloadFor(novel)) return emptyList()
        if (!preferences.downloadNewUnreadChaptersOnly().get()) return newChapters
        val readNumbers = chapterRepo.getByNovelId(novel.id)
            .asSequence()
            .filter { it.read && it.chapterNumber >= 0.0 }
            .map { it.chapterNumber }
            .toSet()
        return newChapters.filterNot { it.chapterNumber in readNumbers }
    }

    private suspend fun shouldDownloadFor(novel: Novel): Boolean {
        val included = preferences.downloadNewChapterCategories().get().map { it.toLong() }
        val excluded = preferences.downloadNewChapterCategoriesExclude().get().map { it.toLong() }
        if (included.isEmpty() && excluded.isEmpty()) return true
        val categories = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.ifEmpty { listOf(0L) }
        return categoryGate(categories, included, excluded)
    }

    /** Category scope for the update itself (mirrors the manga global-update Categories filter). */
    private suspend fun shouldUpdate(novel: Novel): Boolean {
        val included = preferences.novelUpdateCategories().get().map { it.toLong() }
        val excluded = preferences.novelUpdateCategoriesExclude().get().map { it.toLong() }
        if (included.isEmpty() && excluded.isEmpty()) return true
        val categories = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.ifEmpty { listOf(0L) }
        return categoryGate(categories, included, excluded)
    }

    /** Include/exclude category predicate shared by the download + update gates: exclude wins; an empty
     *  include set means "all not excluded". Callers short-circuit the no-filter case before the DB read. */
    private fun categoryGate(categories: List<Long>, included: List<Long>, excluded: List<Long>): Boolean = when {
        categories.any { it in excluded } -> false
        included.isEmpty() -> true
        else -> categories.any { it in included }
    }

    /** Smart-update restrictions (the novel twin of the manga Smart update): skip completed / skip with
     *  unread / skip unstarted, per [NovelPreferences.novelUpdateRestrictions]. Empty = update all. */
    private suspend fun passesSmartUpdate(novel: Novel): Boolean {
        val restrictions = preferences.novelUpdateRestrictions().get()
        if (restrictions.isEmpty()) return true
        if (LibraryPreferences.MANGA_NON_COMPLETED in restrictions &&
            novel.status == NovelStatusCode.COMPLETED.toLong()
        ) {
            return false
        }
        if (LibraryPreferences.MANGA_HAS_UNREAD in restrictions || LibraryPreferences.MANGA_NON_READ in restrictions) {
            val chapters = chapterRepo.getByNovelId(novel.id)
            if (LibraryPreferences.MANGA_HAS_UNREAD in restrictions && chapters.any { !it.read }) return false
            if (LibraryPreferences.MANGA_NON_READ in restrictions && chapters.none { it.read }) return false
        }
        return true
    }

    companion object {
        private const val TAG = "NovelLibraryUpdate"
        private const val WORK_NAME_AUTO = "NovelLibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "NovelLibraryUpdate-manual"
        private const val KEY_CATEGORY = "category"

        /** Twin of `LibraryUpdateJob.isRunningFlow`, over the one rule in [workRunningFlow]. */
        fun isRunningFlow(context: Context): Flow<Boolean> = context.workRunningFlow(TAG)

        /** (Re)schedule or cancel the periodic check from the stored interval (0 = off). Idempotent. */
        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = context.appGraph.novelPreferences
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestrictions().get()
                val networkType = if (LibraryPreferences.DEVICE_NETWORK_NOT_METERED in restrictions) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                }
                val networkRequest = NetworkRequest.Builder().apply {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (LibraryPreferences.DEVICE_ONLY_ON_WIFI in restrictions) {
                        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    }
                    if (LibraryPreferences.DEVICE_NETWORK_NOT_METERED in restrictions) {
                        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                }
                    .build()
                val constraints = Constraints.Builder()
                    // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used
                    .setRequiredNetworkRequest(networkRequest, networkType)
                    .setRequiresCharging(LibraryPreferences.DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<NovelUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    1,
                    TimeUnit.HOURS,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        /** Run a check immediately (for manual triggers / testing); reuses a running drain via KEEP.
         *  A non-null [category] scopes the run to that category (the novel twin of manga's
         *  per-category manual update); null updates the whole library per the include/exclude prefs. */
        fun startNow(workManager: WorkManager, category: Category? = null): Boolean {
            val wm = workManager
            if (wm.isRunning(TAG)) {
                // Already running either as a scheduled or manual job.
                return false
            }
            val request = OneTimeWorkRequestBuilder<NovelUpdateJob>()
                .addTag(TAG)
                .setInputData(workDataOf(KEY_CATEGORY to (category?.id ?: -1L)))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
            return true
        }

        /** Cancel the currently-running check by id (so the periodic schedule survives), re-enqueuing
         *  the auto schedule if that was what got cancelled. Mirrors the manga LibraryUpdateJob. */
        fun stop(context: Context) {
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                .forEach {
                    wm.cancelWorkById(it.id)
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
