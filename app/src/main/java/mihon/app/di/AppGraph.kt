package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.setting.track.BaseOAuthLoginActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.CrashLogUtil
import exh.GalleryAdder
import exh.eh.EHentaiUpdateWorker
import exh.favorites.EhFavoritesBackupJob
import exh.md.MangaDexSyncJob
import exh.source.ExhPreferences
import exh.uconfig.EHConfigurator
import exh.ui.login.EhLoginActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.metro.IsDebugBuild
import mihon.core.migration.Migration
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import reikai.data.novel.update.NovelUpdateJob
import reikai.data.track.TrackerRefreshJob
import reikai.domain.category.GetNovelCategories
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.RepairNovelDetails
import reikai.domain.novel.interactor.ResetNovelCategoryFlags
import reikai.domain.novel.track.NovelDelayedTrackingUpdateJob
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.domain.recommendation.taste.TasteLibraryRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadJob
import reikai.novel.font.NovelFontManager
import reikai.novel.update.LnPluginUpdateChecker
import reikai.presentation.details.MangaEntryCoverViewModel
import reikai.presentation.library.MangaLibraryAdapter
import reikai.presentation.library.NovelLibraryAdapter
import reikai.presentation.migrate.flow.MigrationAdapters
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.novel.details.NovelCoverViewModel
import reikai.presentation.recents.MangaRecentsAdapter
import reikai.presentation.recents.NovelRecentsAdapter
import reikai.presentation.widget.UnifiedUpdatesGlanceWidget
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.InsertTrack

@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class, ReikaiBindings::class],
)
interface AppGraph : ViewModelGraph {
    val viewModelFactory: MetroViewModelFactory

    fun inject(app: App)

    fun inject(backupCreateJob: BackupCreateJob)
    fun inject(backupRestoreJob: BackupRestoreJob)
    fun inject(libraryUpdateJob: LibraryUpdateJob)
    fun inject(metadataUpdateJob: MetadataUpdateJob)
    fun inject(downloadJob: DownloadJob)
    fun inject(novelDownloadJob: NovelDownloadJob)
    fun inject(novelUpdateJob: NovelUpdateJob)
    fun inject(trackerRefreshJob: TrackerRefreshJob)
    fun inject(eHentaiUpdateWorker: EHentaiUpdateWorker)
    fun inject(ehFavoritesBackupJob: EhFavoritesBackupJob)
    fun inject(mangaDexSyncJob: MangaDexSyncJob)
    fun inject(delayedTrackingUpdateJob: DelayedTrackingUpdateJob)
    fun inject(novelDelayedTrackingUpdateJob: NovelDelayedTrackingUpdateJob)

    // Mihon's own widgets inject through PresentationWidgetGraph, contributed from presentation-widget.
    fun inject(unifiedUpdatesGlanceWidget: UnifiedUpdatesGlanceWidget)

    fun inject(secureActivityDelegateImpl: SecureActivityDelegateImpl)

    fun inject(mainActivity: MainActivity)
    fun inject(readerActivity: ReaderActivity)
    fun inject(webViewActivity: WebViewActivity)
    fun inject(baseOAuthLoginActivity: BaseOAuthLoginActivity)
    fun inject(extensionInstallActivity: ExtensionInstallActivity)
    fun inject(ehLoginActivity: EhLoginActivity)
    fun inject(notificationReceiver: NotificationReceiver)

    val context: Context

    val json: Json
    val protoBuf: ProtoBuf
    val database: Database

    val preferenceStore: PreferenceStore
    val networkHelper: NetworkHelper
    val storageManager: StorageManager

    val networkPreferences: NetworkPreferences
    val securityPreferences: SecurityPreferences
    val privacyPreferences: PrivacyPreferences
    val libraryPreferences: LibraryPreferences
    val downloadPreferences: DownloadPreferences
    val backupPreferences: BackupPreferences
    val storagePreferences: StoragePreferences

    // Read through Context.appGraph by companions, objects and composable bodies, none of which can
    // be member-injected.
    // uiPreferences is also read from attachBaseContext, before any injected field exists.
    // migrationAdapters is read from the migrate screens, which pick one by content type at runtime.
    val uiPreferences: UiPreferences
    val migrationAdapters: MigrationAdapters
    val exhPreferences: ExhPreferences
    val ehConfigurator: EHConfigurator

    // Unscoped on purpose: the adder snapshots the enabled-language and disabled-source preferences
    // at construction, so every read has to build a fresh one.
    val galleryAdder: GalleryAdder
    val novelPreferences: NovelPreferences
    val reikaiRecommendationPreferences: ReikaiRecommendationPreferences
    val lnPluginUpdateChecker: LnPluginUpdateChecker
    val refreshTrackerLibrary: RefreshTrackerLibrary

    val basePreferences: BasePreferences
    val readerPreferences: ReaderPreferences
    val sourcePreferences: SourcePreferences
    val trackPreferences: TrackPreferences
    val addTracks: AddTracks
    val insertTrack: InsertTrack
    val reikaiLibraryPreferences: ReikaiLibraryPreferences
    val reikaiSourcePreferences: ReikaiSourcePreferences

    val trackerManager: TrackerManager
    val chapterCache: ChapterCache
    val coverCache: CoverCache
    val pagePreviewCache: PagePreviewCache
    val mangaCoverMetadata: MangaCoverMetadata
    val downloadCache: DownloadCache
    val mangaMergeManager: MangaMergeManager
    val novelMergeManager: NovelMergeManager
    val novelFontManager: NovelFontManager
    val migrationPickHandoff: MigrationPickHandoff

    // The two details adapters build their cover model for whichever entry the source chip is showing,
    // so the id arrives at call time and the factory is what the graph can hand over.
    val mangaCoverViewModelFactory: MangaEntryCoverViewModel.Factory
    val novelCoverViewModelFactory: NovelCoverViewModel.Factory

    // The library engine owns exactly one adapter pair, built from the tab's own three models, so the
    // models arrive at call time here too.
    val mangaLibraryAdapterFactory: MangaLibraryAdapter.Factory
    val novelLibraryAdapterFactory: NovelLibraryAdapter.Factory
    val mangaRecentsAdapterFactory: MangaRecentsAdapter.Factory
    val novelRecentsAdapterFactory: NovelRecentsAdapter.Factory
    val tasteLibraryRepository: TasteLibraryRepository

    // Interactors are unscoped, so every read builds a fresh instance. That matches the pre-port
    // shape: Injekt registered every one of these with addFactory, never addSingletonFactory.
    val getCategories: GetCategories
    val getNovelCategories: GetNovelCategories
    val getFavorites: GetFavorites
    val getFlatMetadataById: GetFlatMetadataById
    val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata
    val getExtensionStoreCountAsFlow: GetExtensionStoreCountAsFlow
    val toggleIncognito: ToggleIncognito
    val trustExtension: TrustExtension
    val resetViewerFlags: ResetViewerFlags
    val resetCategoryFlags: ResetCategoryFlags
    val resetNovelCategoryFlags: ResetNovelCategoryFlags
    val repairNovelDetails: RepairNovelDetails

    // RK: an accessor rather than upstream's injected App field. A field would build every migration
    // at graph.inject, and one of them takes Database, before the legacy-database recovery can move
    // an incompatible database aside. Read where it is used instead, after that recovery has run.
    val migrations: Set<Migration>

    // Read by App's cold-start warm-up.
    val sourceManager: SourceManager
    val extensionManager: ExtensionManager
    val crashLogUtil: CrashLogUtil
    val updateChecker: AppUpdateChecker
    val downloadManager: DownloadManager

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}
