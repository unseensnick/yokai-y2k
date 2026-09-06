package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.presentation.library.preferredsources.PreferredSourcesScreen
import reikai.presentation.recommendation.SettingsRecommendationsScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MARK_DUPLICATE_CHAPTER_READ_EXISTING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MARK_DUPLICATE_CHAPTER_READ_NEW
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsLibraryScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.pref_category_library

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val getCategories = remember { context.appGraph.getCategories }
        val libraryPreferences = remember { context.appGraph.libraryPreferences }
        val novelPreferences = remember { context.appGraph.novelPreferences }
        // RK: master merging switch, hosted in the Sources group below
        val reikaiLibraryPreferences = remember { context.appGraph.reikaiLibraryPreferences }
        // RK: novel categories for the novel update-categories filter
        val getNovelCategories = remember { context.appGraph.getNovelCategories }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())
        val novelCategories by getNovelCategories.subscribe().collectAsState(initial = emptyList())

        return listOf(
            // RK: pass novel prefs + categories so the Categories group also hosts the novel default category
            getCategoriesGroup(
                LocalNavigator.currentOrThrow,
                allCategories,
                libraryPreferences,
                novelPreferences,
                novelCategories,
            ),
            getGlobalUpdateGroup(allCategories, libraryPreferences),
            // RK: background light-novel chapter updates
            getNovelUpdateGroup(novelPreferences, novelCategories),
            getBehaviorGroup(libraryPreferences, novelPreferences),
            // RK: merge-group preferred-source ranking
            getSourcesGroup(LocalNavigator.currentOrThrow, reikaiLibraryPreferences),
        )
    }

    // RK --> background light-novel chapter updates
    @Composable
    private fun getNovelUpdateGroup(
        novelPreferences: NovelPreferences,
        allNovelCategories: List<Category>,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val intervalPref = novelPreferences.libraryUpdateInterval()
        val interval by intervalPref.collectAsState()

        val includePref = novelPreferences.novelUpdateCategories()
        val excludePref = novelPreferences.novelUpdateCategoriesExclude()
        val included by includePref.collectAsState()
        val excluded by excludePref.collectAsState()
        var showCategoriesDialog by rememberSaveable { mutableStateOf(false) }
        if (showCategoriesDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.categories),
                message = stringResource(MR.strings.pref_library_update_categories_details),
                items = allNovelCategories,
                initialChecked = included.mapNotNull { id -> allNovelCategories.find { it.id.toString() == id } },
                initialInversed = excluded.mapNotNull { id -> allNovelCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showCategoriesDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    includePref.set(newIncluded.map { it.id.toString() }.toSet())
                    excludePref.set(newExcluded.map { it.id.toString() }.toSet())
                    showCategoriesDialog = false
                },
            )
        }
        return Preference.PreferenceGroup(
            // RK: same "Global update" concept as the manga group, content-typed for consistency.
            title = contentTypedCategory(MR.strings.pref_category_library_update, MR.strings.content_type_novels),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = intervalPref,
                    entries = mapOf(
                        0 to stringResource(MR.strings.update_never),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        72 to stringResource(MR.strings.update_72hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_library_update_interval),
                    onValueChanged = {
                        NovelUpdateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = novelPreferences.libraryUpdateDeviceRestrictions(),
                    entries = mapOf(
                        DEVICE_ONLY_ON_WIFI to stringResource(MR.strings.connected_to_wifi),
                        DEVICE_NETWORK_NOT_METERED to stringResource(MR.strings.network_not_metered),
                        DEVICE_CHARGING to stringResource(MR.strings.charging),
                    ),
                    title = stringResource(MR.strings.pref_library_update_restriction),
                    subtitle = stringResource(MR.strings.restrictions),
                    enabled = interval > 0,
                    onValueChanged = {
                        // Post to the main looper so the preference write lands before rescheduling.
                        ContextCompat.getMainExecutor(context).execute { NovelUpdateJob.setupTask(context) }
                        true
                    },
                ),
                // Categories + Smart update are ungated (always shown), matching the manga Global-update
                // group where only the device-restriction row is gated on interval > 0.
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.categories),
                    subtitle = getCategoriesLabel(allNovelCategories, included, excluded),
                    onClick = { showCategoriesDialog = true },
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = novelPreferences.novelUpdateRestrictions(),
                    entries = mapOf(
                        MANGA_NON_COMPLETED to stringResource(MR.strings.pref_update_only_non_completed),
                        MANGA_HAS_UNREAD to stringResource(MR.strings.pref_update_only_completely_read),
                        MANGA_NON_READ to stringResource(MR.strings.pref_update_only_started),
                    ),
                    title = stringResource(MR.strings.pref_library_update_smart_update),
                ),
            ),
        )
    }
    // RK <--

    // RK -->
    @Composable
    private fun getSourcesGroup(
        navigator: Navigator,
        reikaiLibraryPreferences: ReikaiLibraryPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            // RK: named for what it holds. Every row here is about merging a series across its
            // sources, including the preferred-sources ranking, which only the merge path reads.
            title = stringResource(MR.strings.label_merged_series),
            preferenceItems = listOf(
                // RK: master switch for source merging (also in the library display menu). Off resolves
                // every series standalone and hides the merge UI, keeping the groups.
                Preference.PreferenceItem.SwitchPreference(
                    preference = reikaiLibraryPreferences.seriesMergingEnabled,
                    title = stringResource(MR.strings.action_series_merging),
                    subtitle = stringResource(MR.strings.pref_series_merging_summary),
                ),
                // RK: same-title grouping suggestion at add time, per content type.
                Preference.PreferenceItem.SwitchPreference(
                    preference = reikaiLibraryPreferences.autoMergeSameTitle,
                    title = contentTypedCategory(
                        MR.strings.pref_suggest_group_same_title,
                        MR.strings.content_type_manga,
                    ),
                    subtitle = stringResource(MR.strings.pref_suggest_group_same_title_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = reikaiLibraryPreferences.novelAutoMergeSameTitle,
                    title = contentTypedCategory(
                        MR.strings.pref_suggest_group_same_title,
                        MR.strings.content_type_novels,
                    ),
                    subtitle = stringResource(MR.strings.pref_suggest_group_same_title_summary),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_preferred_sources),
                    subtitle = stringResource(MR.strings.pref_preferred_sources_summary),
                    onClick = { navigator.push(PreferredSourcesScreen()) },
                ),
            ),
        )
    }
    // RK <--

    @Composable
    private fun getCategoriesGroup(
        navigator: Navigator,
        allCategories: List<Category>,
        libraryPreferences: LibraryPreferences,
        // RK: novel default-category pref + novel categories, so both defaults sit in one group
        novelPreferences: NovelPreferences,
        novelCategories: List<Category>,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        // RK: the edit-categories screen lists every category, so this count is the union of the two
        // library reads rather than the manga-visible ones alone. They overlap on categories shown in
        // both libraries, hence the dedupe by id.
        val userCategoriesCount = (allCategories + novelCategories)
            .distinctBy { it.id }
            .filterNot(Category::isSystemCategory)
            .size

        // For default category
        val ids = listOf(libraryPreferences.defaultCategory.defaultValue()) +
            allCategories.fastMap { it.id.toInt() }
        val labels = listOf(stringResource(MR.strings.default_category_summary)) +
            allCategories.fastMap { it.visualName }
        // RK --> novel default-category entries (its own category namespace)
        val novelIds = listOf(novelPreferences.defaultNovelCategory().defaultValue()) +
            novelCategories.fastMap { it.id.toInt() }
        val novelLabels = listOf(stringResource(MR.strings.default_category_summary)) +
            novelCategories.fastMap { it.visualName }
        // RK <--

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.categories),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.action_edit_categories),
                    subtitle = pluralStringResource(
                        MR.plurals.num_categories,
                        count = userCategoriesCount,
                        userCategoriesCount,
                    ),
                    onClick = { navigator.push(CategoryScreen()) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.defaultCategory,
                    entries = ids.zip(labels).toMap(),
                    // RK: content-type label, since a novel default-category twin sits below.
                    title = contentTypedCategory(MR.strings.default_category, MR.strings.content_type_manga),
                ),
                // RK --> novel default category, alongside the manga one
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.defaultNovelCategory(),
                    entries = novelIds.zip(novelLabels).toMap(),
                    title = contentTypedCategory(MR.strings.default_category, MR.strings.content_type_novels),
                ),
                // RK <--
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.categorizedDisplaySettings,
                    title = stringResource(MR.strings.categorized_display_settings),
                    onValueChanged = {
                        if (!it) {
                            scope.launch {
                                context.appGraph.resetCategoryFlags.await()
                                // RK: reset novel category sorts too, so novels honor the toggle like manga
                                context.appGraph.resetNovelCategoryFlags.await()
                            }
                        }
                        true
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getGlobalUpdateGroup(
        allCategories: List<Category>,
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current

        val autoUpdateIntervalPref = libraryPreferences.autoUpdateInterval
        val autoUpdateCategoriesPref = libraryPreferences.updateCategories
        val autoUpdateCategoriesExcludePref = libraryPreferences.updateCategoriesExclude

        val autoUpdateInterval by autoUpdateIntervalPref.collectAsState()

        val included by autoUpdateCategoriesPref.collectAsState()
        val excluded by autoUpdateCategoriesExcludePref.collectAsState()
        var showCategoriesDialog by rememberSaveable { mutableStateOf(false) }
        if (showCategoriesDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.categories),
                message = stringResource(MR.strings.pref_library_update_categories_details),
                items = allCategories,
                initialChecked = included.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                initialInversed = excluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showCategoriesDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    autoUpdateCategoriesPref.set(newIncluded.map { it.id.toString() }.toSet())
                    autoUpdateCategoriesExcludePref.set(newExcluded.map { it.id.toString() }.toSet())
                    showCategoriesDialog = false
                },
            )
        }

        return Preference.PreferenceGroup(
            // RK: content-type header, pairs with the novel library-update group.
            title = contentTypedCategory(MR.strings.pref_category_library_update, MR.strings.content_type_manga),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = autoUpdateIntervalPref,
                    entries = mapOf(
                        0 to stringResource(MR.strings.update_never),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        72 to stringResource(MR.strings.update_72hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_library_update_interval),
                    onValueChanged = {
                        LibraryUpdateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = libraryPreferences.autoUpdateDeviceRestrictions,
                    entries = mapOf(
                        DEVICE_ONLY_ON_WIFI to stringResource(MR.strings.connected_to_wifi),
                        DEVICE_NETWORK_NOT_METERED to stringResource(MR.strings.network_not_metered),
                        DEVICE_CHARGING to stringResource(MR.strings.charging),
                    ),
                    title = stringResource(MR.strings.pref_library_update_restriction),
                    subtitle = stringResource(MR.strings.restrictions),
                    enabled = autoUpdateInterval > 0,
                    onValueChanged = {
                        // Post to event looper to allow the preference to be updated.
                        ContextCompat.getMainExecutor(context).execute { LibraryUpdateJob.setupTask(context) }
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.categories),
                    subtitle = getCategoriesLabel(
                        allCategories = allCategories,
                        included = included,
                        excluded = excluded,
                    ),
                    onClick = { showCategoriesDialog = true },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoUpdateMetadata,
                    title = stringResource(MR.strings.pref_library_update_refresh_metadata),
                    subtitle = stringResource(MR.strings.pref_library_update_refresh_metadata_summary),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = libraryPreferences.autoUpdateMangaRestrictions,
                    entries = mapOf(
                        MANGA_HAS_UNREAD to stringResource(MR.strings.pref_update_only_completely_read),
                        MANGA_NON_READ to stringResource(MR.strings.pref_update_only_started),
                        MANGA_NON_COMPLETED to stringResource(MR.strings.pref_update_only_non_completed),
                        MANGA_OUTSIDE_RELEASE_PERIOD to stringResource(MR.strings.pref_update_only_in_release_period),
                    ),
                    title = stringResource(MR.strings.pref_library_update_smart_update),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.newShowUpdatesCount,
                    title = stringResource(MR.strings.pref_library_update_show_tab_badge),
                ),
            ),
        )
    }

    @Composable
    private fun getBehaviorGroup(
        libraryPreferences: LibraryPreferences,
        // RK: the novel twin of the missing-chapter toggle sits here too, so the pair reads as one
        // setting per content type rather than a row that looks duplicated.
        novelPreferences: NovelPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_behavior),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.swipeToStartAction,
                    entries = mapOf(
                        LibraryPreferences.ChapterSwipeAction.Disabled to
                            stringResource(MR.strings.disabled),
                        LibraryPreferences.ChapterSwipeAction.ToggleBookmark to
                            stringResource(MR.strings.action_bookmark),
                        LibraryPreferences.ChapterSwipeAction.ToggleRead to
                            stringResource(MR.strings.action_mark_as_read),
                        LibraryPreferences.ChapterSwipeAction.Download to
                            stringResource(MR.strings.action_download),
                    ),
                    title = stringResource(MR.strings.pref_chapter_swipe_start),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.swipeToEndAction,
                    entries = mapOf(
                        LibraryPreferences.ChapterSwipeAction.Disabled to
                            stringResource(MR.strings.disabled),
                        LibraryPreferences.ChapterSwipeAction.ToggleBookmark to
                            stringResource(MR.strings.action_bookmark),
                        LibraryPreferences.ChapterSwipeAction.ToggleRead to
                            stringResource(MR.strings.action_mark_as_read),
                        LibraryPreferences.ChapterSwipeAction.Download to
                            stringResource(MR.strings.action_download),
                    ),
                    title = stringResource(MR.strings.pref_chapter_swipe_end),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = libraryPreferences.markDuplicateReadChapterAsRead,
                    entries = mapOf(
                        MARK_DUPLICATE_CHAPTER_READ_EXISTING to
                            stringResource(MR.strings.pref_mark_duplicate_read_chapter_read_existing),
                        MARK_DUPLICATE_CHAPTER_READ_NEW to
                            stringResource(MR.strings.pref_mark_duplicate_read_chapter_read_new),
                    ),
                    title = stringResource(MR.strings.pref_mark_duplicate_read_chapter_read),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.hideMissingChapters,
                    // RK: content-typed, because the novel twin below is otherwise the same row twice.
                    title = contentTypedCategory(
                        MR.strings.pref_hide_missing_chapter_indicators,
                        MR.strings.content_type_manga,
                    ),
                ),
                // RK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.hideMissingChapters(),
                    title = contentTypedCategory(
                        MR.strings.pref_hide_missing_chapter_indicators,
                        MR.strings.content_type_novels,
                    ),
                ),
                // RK <--
            ),
        )
    }
}
