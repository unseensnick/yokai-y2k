package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsBrowseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { context.appGraph.sourcePreferences }
        // RK: the adult-sources gate, moved here from Advanced so it sits above the settings group it
        // reveals rather than making one appear on a screen the user is not looking at.
        val exhPreferences = remember { context.appGraph.exhPreferences }
        val getExtensionStoreCountAsFlow = remember { context.appGraph.getExtensionStoreCountAsFlow }
        // RK: the Repos screen is unified (manga + light novel), so count both.
        val novelPreferences = remember { context.appGraph.novelPreferences }
        // RK --> the Feed tab's own switches, which the rest of its group hangs off
        val reikaiSourcePreferences = remember { context.appGraph.reikaiSourcePreferences }
        val showFeedTab by reikaiSourcePreferences.showFeedTab.changes()
            .collectAsState(reikaiSourcePreferences.showFeedTab.get())
        // RK <--

        val adultSourcesEnabled by exhPreferences.isHentaiEnabled().changes()
            .collectAsState(exhPreferences.isHentaiEnabled().get())

        // RK: page previews are a source capability (four sources implement PagePreviewSource), so the
        // row lives with sources rather than with the app-wide look it used to sit under.
        val uiPreferences = remember { context.appGraph.uiPreferences }
        val previewsRowCount by uiPreferences.previewsRowCount.changes()
            .collectAsState(uiPreferences.previewsRowCount.get())

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)
        val novelRepoUrls by novelPreferences.addedRepoUrls().changes()
            .collectAsState(novelPreferences.addedRepoUrls().get())

        return listOfNotNull(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    // RK -->
                    Preference.PreferenceItem.SliderPreference(
                        value = previewsRowCount,
                        valueRange = 0..10,
                        title = stringResource(MR.strings.pref_previews_row_count),
                        subtitle = stringResource(MR.strings.pref_previews_row_count_summary),
                        onValueChanged = { uiPreferences.previewsRowCount.set(it) },
                    ),
                    // RK <--
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        // RK: show manga + light-novel repo counts (the Repos screen holds both).
                        subtitle = stringResource(
                            MR.strings.extension_repos_subtitle,
                            reposCount.toInt(),
                            novelRepoUrls.size,
                        ),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ),
            ),
            // RK --> the Feed tab is Reikai's, and every switch here is off by default
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_feed),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = reikaiSourcePreferences.showFeedTab,
                        title = stringResource(MR.strings.pref_show_feed_tab),
                        subtitle = stringResource(MR.strings.pref_show_feed_tab_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = reikaiSourcePreferences.feedTabInFront,
                        title = stringResource(MR.strings.pref_feed_tab_first),
                        subtitle = stringResource(MR.strings.pref_feed_tab_first_summary),
                        enabled = showFeedTab,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = reikaiSourcePreferences.hideInLibraryFeedItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                        enabled = showFeedTab,
                    ),
                ),
            ),
            // RK <--
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_nsfw_content),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showNsfwSource,
                        title = stringResource(MR.strings.pref_show_nsfw_source),
                        subtitle = stringResource(MR.strings.requires_app_restart),
                        onValueChanged = {
                            (context as FragmentActivity).authenticate(
                                title = context.stringResource(MR.strings.pref_category_nsfw_content),
                            )
                        },
                    ),
                    // RK: the built-in adult sources gate, next to the NSFW switch it belongs with.
                    Preference.PreferenceItem.SwitchPreference(
                        preference = exhPreferences.isHentaiEnabled(),
                        title = stringResource(MR.strings.pref_enable_adult_sources),
                        subtitle = stringResource(MR.strings.pref_enable_adult_sources_summary),
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.parental_controls_info)),
                ),
            ),
            // RK --> settings owned by the app rather than by an installed extension, so they have
            // nowhere to live on the source itself. Each row appears only while its source is on, and
            // the group disappears entirely when neither is, which is why it is built conditionally.
            getSourceSettingsGroup(navigator, adultSourcesEnabled),
            // RK <--
        )
    }

    @Composable
    private fun getSourceSettingsGroup(
        navigator: Navigator,
        adultSourcesEnabled: Boolean,
    ): Preference.PreferenceGroup? {
        val rows = listOfNotNull(
            // The gate is passed in as observed state rather than read through isEnabled(), which is a
            // plain pref read: without a snapshot dependency this row would not appear until the screen
            // was recreated, even though the switch that reveals it is right above.
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_category_eh),
                subtitle = stringResource(MR.strings.pref_ehentai_summary),
                onClick = { navigator.push(SettingsEhScreen) },
            ).takeIf { adultSourcesEnabled },
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_category_mangadex),
                subtitle = stringResource(MR.strings.pref_mangadex_summary),
                onClick = { navigator.push(SettingsMangaDexScreen) },
            ).takeIf { SettingsMangaDexScreen.isEnabled() },
        )
        return if (rows.isEmpty()) {
            null
        } else {
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.source_settings),
                preferenceItems = rows,
            )
        }
    }
}
