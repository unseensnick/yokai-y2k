package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupProtoReader
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeedRow
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.i18n.MR
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
@AssistedInject
class BackupRestorer(
    @Assisted private val notifier: BackupNotifier,
    @Assisted private val isSync: Boolean,
    private val context: Context,

    private val database: Database,
    private val downloadCache: DownloadCache,
    private val categoriesRestorer: CategoriesRestorer,
    private val preferenceRestorer: PreferenceRestorer,
    private val extensionStoreRestorer: ExtensionStoreRestorer,
    private val mangaRestorer: MangaRestorer,
    private val parser: ProtoBuf,
    // RK -->
    private val novelRestorer: NovelRestorer,
    private val extensionRestorer: ExtensionRestorer,
    private val feedRestorer: FeedRestorer,
    // RK <--
) {

    @AssistedFactory
    fun interface Factory {
        fun create(notifier: BackupNotifier, isSync: Boolean): BackupRestorer
    }

    private var restoreAmount = 0
    private val restoreProgress = AtomicInt(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        // Invalidate download cache to ensure UI reflects any restored downloads
        if (options.libraryEntries) {
            try {
                downloadCache.invalidateCache()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to invalidate download cache after restore" }
            }
        }

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    // RK: restore streams the backup instead of decoding the whole file into memory (which OOMs on a
    // large library, the read side of Issue #53). Pass 1 (readBackupSummary) gathers the small fields
    // and counts the library entries; the entries themselves are streamed and restored one bounded
    // batch at a time in restoreMangaStream / restoreNovelsStream.
    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val summary = readBackupSummary(uri)

        // Store source mapping for error messages
        sourceMapping = summary.backupSources.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += summary.mangaCount + summary.novelCount
        }
        if (options.categories) {
            restoreAmount += 1
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += summary.backupExtensionStores.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            // RK: categories must finish restoring BEFORE manga + app-settings, both of which map to
            // live categories by name (a manga's category assignments in MangaRestorer.restoreCategories;
            // the default-category pref in PreferenceRestorer). Otherwise an entry restored before its
            // categories exist loses them and lands in Default. Upstream fixes the same race by handing
            // the categories job to those two restorers to await; we await it once here instead, because
            // the novel stream below needs the same wait and would have to be threaded separately.
            if (options.categories) {
                restoreCategories(summary.backupCategories).join()
            }
            if (options.appSettings) {
                restoreAppPreferences(summary.backupPreferences, summary.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(summary.backupSourcePreferences)
            }
            if (options.libraryEntries) {
                restoreMangaStream(
                    uri,
                    if (options.categories) summary.backupCategories else emptyList(),
                    summary.backupMangaMerges,
                    summary.backupCustomMangaInfo,
                )
            }
            if (options.extensionStores) {
                restoreExtensionStores(summary.backupExtensionStores, summary.backupExtensions)
            }
            // RK -->
            if (options.savedSearches) {
                restoreIsolated("saved searches") {
                    feedRestorer(summary.backupSavedSearches, summary.backupFeedRows)
                }
            }
            restoreNovelsStream(uri, summary, options)
            // RK: novel plugins are NOT reinstalled here. Their install state (URLs + metadata) rides
            // the preference backup, so the normal lazy loader re-downloads them on the next novel-
            // screen open. A restore-time reinstall just duplicated that work and stalled on any
            // unreachable repo.
            // RK <--

            // TODO: optionally trigger online library + tracker update
        }

        // RK: the novel category-id preferences still name the backup's category ids after the restore.
        // Manga remaps them inline in PreferenceRestorer, but novel categories are not restored until the
        // novel stream above, so it happens here, once both the prefs and the novel categories are in place.
        if (options.categories && options.appSettings) {
            novelRestorer.remapCategoryPreferences(summary.backupNovelCategories)
        }
    }

    // RK: pass 1. Decode only the small fields (kilobytes) and count the streamed library entries, so
    // the restore never has to hold every manga / novel and their chapters in memory at once.
    private suspend fun readBackupSummary(uri: Uri): BackupSummary {
        val backupCategories = mutableListOf<BackupCategory>()
        val backupSources = mutableListOf<BackupSource>()
        val backupPreferences = mutableListOf<BackupPreference>()
        val backupSourcePreferences = mutableListOf<BackupSourcePreferences>()
        val backupExtensionStores = mutableListOf<BackupExtensionStore>()
        val backupExtensions = mutableListOf<BackupExtension>()
        val backupMangaMerges = mutableListOf<BackupMangaMergeGroup>()
        val backupCustomMangaInfo = mutableListOf<BackupCustomMangaInfo>()
        val backupNovelCategories = mutableListOf<BackupNovelCategory>()
        val backupNovelMerges = mutableListOf<BackupNovelMergeGroup>()
        val backupCustomNovelInfo = mutableListOf<BackupCustomNovelInfo>()
        val backupSavedSearches = mutableListOf<BackupSavedSearch>()
        val backupFeedRows = mutableListOf<BackupFeedRow>()
        var mangaCount = 0
        var novelCount = 0

        BackupProtoReader(context).read(uri) { fieldNumber, data ->
            when (fieldNumber) {
                1 -> mangaCount++
                700 -> novelCount++
                2 -> backupCategories.add(parser.decodeFromByteArray(BackupCategory.serializer(), data))
                101 -> backupSources.add(parser.decodeFromByteArray(BackupSource.serializer(), data))
                104 -> backupPreferences.add(parser.decodeFromByteArray(BackupPreference.serializer(), data))
                105 -> backupSourcePreferences.add(
                    parser.decodeFromByteArray(BackupSourcePreferences.serializer(), data),
                )
                106 -> backupExtensionStores.add(parser.decodeFromByteArray(BackupExtensionStore.serializer(), data))
                710 -> backupExtensions.add(parser.decodeFromByteArray(BackupExtension.serializer(), data))
                711 -> backupMangaMerges.add(parser.decodeFromByteArray(BackupMangaMergeGroup.serializer(), data))
                713 -> backupCustomMangaInfo.add(parser.decodeFromByteArray(BackupCustomMangaInfo.serializer(), data))
                701 -> backupNovelCategories.add(parser.decodeFromByteArray(BackupNovelCategory.serializer(), data))
                702 -> backupNovelMerges.add(parser.decodeFromByteArray(BackupNovelMergeGroup.serializer(), data))
                714 -> backupCustomNovelInfo.add(parser.decodeFromByteArray(BackupCustomNovelInfo.serializer(), data))
                715 -> backupSavedSearches.add(parser.decodeFromByteArray(BackupSavedSearch.serializer(), data))
                716 -> backupFeedRows.add(parser.decodeFromByteArray(BackupFeedRow.serializer(), data))
            }
        }

        return BackupSummary(
            mangaCount = mangaCount,
            novelCount = novelCount,
            backupCategories = backupCategories,
            backupSources = backupSources,
            backupPreferences = backupPreferences,
            backupSourcePreferences = backupSourcePreferences,
            backupExtensionStores = backupExtensionStores,
            backupExtensions = backupExtensions,
            backupMangaMerges = backupMangaMerges,
            backupCustomMangaInfo = backupCustomMangaInfo,
            backupNovelCategories = backupNovelCategories,
            backupNovelMerges = backupNovelMerges,
            backupCustomNovelInfo = backupCustomNovelInfo,
            backupSavedSearches = backupSavedSearches,
            backupFeedRows = backupFeedRows,
        )
    }

    private data class BackupSummary(
        val mangaCount: Int,
        val novelCount: Int,
        val backupCategories: List<BackupCategory>,
        val backupSources: List<BackupSource>,
        val backupPreferences: List<BackupPreference>,
        val backupSourcePreferences: List<BackupSourcePreferences>,
        val backupExtensionStores: List<BackupExtensionStore>,
        val backupExtensions: List<BackupExtension>,
        val backupMangaMerges: List<BackupMangaMergeGroup>,
        val backupCustomMangaInfo: List<BackupCustomMangaInfo>,
        val backupNovelCategories: List<BackupNovelCategory>,
        val backupNovelMerges: List<BackupNovelMergeGroup>,
        val backupCustomNovelInfo: List<BackupCustomNovelInfo>,
        val backupSavedSearches: List<BackupSavedSearch>,
        val backupFeedRows: List<BackupFeedRow>,
    )

    // RK: restore the light-novel library, streamed. Categories first, then each novel in bounded
    // batches, then the merge groups + custom-info overlay (both re-keyed from {url,source}).
    private fun CoroutineScope.restoreNovelsStream(
        uri: Uri,
        summary: BackupSummary,
        options: RestoreOptions,
    ) = launch {
        if (options.categories) {
            ensureActive()
            novelRestorer.restoreCategories(summary.backupNovelCategories)
        }
        // Mirrors the manga stream's gate: with Categories off, novels must not be assigned to
        // same-named pre-existing categories either.
        val membershipCategories = if (options.categories) summary.backupNovelCategories else emptyList()
        if (options.libraryEntries) {
            val batch = ArrayList<BackupNovel>(RESTORE_CHUNK)
            suspend fun flush() {
                if (batch.isEmpty()) return
                // Same batch-failure containment as the manga stream: a per-entry catch inside the
                // shared transaction cannot stop one bad novel from rolling the whole batch back.
                val restoredAsBatch = try {
                    database.transaction {
                        batch.forEach { backupNovel ->
                            ensureActive()
                            novelRestorer.restore(backupNovel, membershipCategories)
                        }
                    }
                    true
                } catch (e: Exception) {
                    ensureActive()
                    logcat(LogPriority.WARN, e) { "Batch novel restore failed, retrying entry by entry" }
                    false
                }

                if (restoredAsBatch) {
                    restoreProgress.addAndFetch(batch.size)
                } else {
                    batch.forEach { backupNovel ->
                        ensureActive()
                        try {
                            novelRestorer.restore(backupNovel, membershipCategories)
                        } catch (e: Exception) {
                            ensureActive()
                            errors.add(Date() to "${backupNovel.title} [${backupNovel.source}]: ${e.message}")
                        }
                        restoreProgress.incrementAndFetch()
                    }
                }

                notifier.showRestoreProgress(batch.last().title, restoreProgress.load(), restoreAmount, isSync)
                batch.clear()
            }

            BackupProtoReader(context).read(uri) { fieldNumber, data ->
                if (fieldNumber != 700) return@read
                ensureActive()
                batch.add(parser.decodeFromByteArray(BackupNovel.serializer(), data))
                if (batch.size >= RESTORE_CHUNK) flush()
            }
            flush()

            // Isolated for the same reason as the manga twin: a failure here used to cancel the
            // sibling stream and escape before the error log was written.
            restoreIsolated("novel merges") { novelRestorer.restoreMerges(summary.backupNovelMerges) }
            restoreIsolated("novel custom info") {
                novelRestorer.restoreCustomNovelInfo(summary.backupCustomNovelInfo)
            }
        }
    }

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        categoriesRestorer(backupCategories)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
    }

    // RK: pass 2 for manga. Streams field 1, restoring bounded batches inside a DB transaction (each
    // restore also opens its own, harmlessly nested), then materializes the merge groups + custom-info
    // once every manga has a fresh id. The old whole-list sortByNew is dropped: entries restore
    // independently and merges resolve by {url,source} after the loop, so file order is fine.
    private fun CoroutineScope.restoreMangaStream(
        uri: Uri,
        backupCategories: List<BackupCategory>,
        backupMangaMerges: List<BackupMangaMergeGroup>,
        backupCustomMangaInfo: List<BackupCustomMangaInfo>,
    ) = launch {
        val batch = ArrayList<BackupManga>(RESTORE_CHUNK)
        suspend fun flush() {
            if (batch.isEmpty()) return
            // SQLDelight fails an enclosing transaction when a nested one fails, so catching per entry
            // inside the shared transaction cannot contain a bad entry: the whole batch rolls back and
            // those entries are lost. Retry entry by entry instead.
            val restoredAsBatch = try {
                database.transaction {
                    batch.forEach { backupManga ->
                        ensureActive()
                        mangaRestorer.restore(backupManga, backupCategories)
                    }
                }
                true
            } catch (e: Exception) {
                ensureActive()
                logcat(LogPriority.WARN, e) { "Batch restore failed, retrying entry by entry" }
                false
            }

            if (restoredAsBatch) {
                restoreProgress.addAndFetch(batch.size)
            } else {
                batch.forEach { backupManga ->
                    ensureActive()
                    try {
                        mangaRestorer.restore(backupManga, backupCategories)
                    } catch (e: Exception) {
                        ensureActive()
                        val sourceName = sourceMapping[backupManga.source] ?: backupManga.source.toString()
                        errors.add(Date() to "${backupManga.title} [$sourceName]: ${e.message}")
                    }
                    restoreProgress.incrementAndFetch()
                }
            }

            notifier.showRestoreProgress(batch.last().title, restoreProgress.load(), restoreAmount, isSync)
            batch.clear()
        }

        BackupProtoReader(context).read(uri) { fieldNumber, data ->
            if (fieldNumber != 1) return@read
            ensureActive()
            batch.add(parser.decodeFromByteArray(BackupManga.serializer(), data))
            if (batch.size >= RESTORE_CHUNK) flush()
        }
        flush()

        // RK: with every manga restored (fresh IDs), materialize the backup's merge groups, then
        // apply the custom-info overlay re-keyed to those same IDs. Isolated like the entry loop
        // above: these ran bare, so a failure here cancelled the novel stream mid-batch and escaped
        // before the error log was written, leaving the user a half-restored library and no report.
        ensureActive()
        restoreIsolated("merges") { mangaRestorer.restoreMerges(backupMangaMerges) }
        ensureActive()
        restoreIsolated("custom info") { mangaRestorer.restoreCustomInfo(backupCustomMangaInfo) }
    }

    /** Run a post-loop restore phase, recording a failure instead of taking the whole restore down. */
    private suspend fun restoreIsolated(phase: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add(Date() to "$phase: ${e.message}")
        }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
        backupExtensions: List<BackupExtension>,
    ) = launch {
        backupExtensionStores
            .chunked(RESTORE_CHUNK)
            .forEach { chunk ->
                database.transaction {
                    chunk.forEach {
                        ensureActive()

                        try {
                            extensionStoreRestorer(it)
                        } catch (e: Exception) {
                            errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress.load(),
                    restoreAmount,
                    isSync,
                )
            }

        // RK: with the repos restored, reinstall the recorded manga extensions. Those whose repo is
        // missing can't be matched; log them so the user knows what to reinstall by hand.
        ensureActive()
        try {
            extensionRestorer.restore(backupExtensions).forEach { name ->
                errors.add(Date() to "Extension not reinstalled (repo missing): $name")
            }
        } catch (e: Exception) {
            errors.add(Date() to "Error reinstalling extensions: ${e.message}")
        }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("reikai_restore_error.txt") // RK: Reikai-branded dump name
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }

    companion object {
        // RK: entries per DB transaction while streaming; also the memory bound (only this many
        // entries + their chapters are resident at once).
        private const val RESTORE_CHUNK = 100
    }
}
