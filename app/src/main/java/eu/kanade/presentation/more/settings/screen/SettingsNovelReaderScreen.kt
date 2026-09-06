package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import mihon.app.di.appGraph
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt
import tachiyomi.core.common.preference.Preference as PreferenceStoreEntry

/**
 * Light-novel reader settings, a top-level Settings entry beside [SettingsMangaReaderScreen]. Settings
 * of the same name on the two screens are deliberately separate values; see that screen's note.
 *
 * The live-tuning display controls (font, size, theme, colours) stay in the in-reader gear sheet,
 * which previews them as you change them.
 */
object SettingsNovelReaderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_novel_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val novelPref = remember { context.appGraph.novelPreferences }

        return listOf(
            getReadingGroup(novelPreferences = novelPref),
            getTextDisplayGroup(novelPreferences = novelPref),
            getChapterTextGroup(novelPreferences = novelPref),
            getNavigationGroup(novelPreferences = novelPref),
            getAccessibilityGroup(novelPreferences = novelPref),
        )
    }

    /**
     * Paragraph shape, applied by whichever renderer draws the chapter. Both are multiples of the
     * text size, so they hold their proportions when it changes. Novel-only by mechanism: a manga
     * page is an image the source ships, so there is no text for them to act on.
     */
    @Composable
    private fun getTextDisplayGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val indentPref = novelPreferences.readerParagraphIndent()
        val spacingPref = novelPreferences.readerParagraphSpacing()
        val indent by indentPref.collectAsState()
        val spacing by spacingPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_text_display),
            preferenceItems = listOf(
                marginRow(novelPreferences.readerMarginTop(), MR.strings.pref_margin_top),
                marginRow(novelPreferences.readerMarginBottom(), MR.strings.pref_margin_bottom),
                marginRow(novelPreferences.readerMarginLeft(), MR.strings.pref_margin_left),
                marginRow(novelPreferences.readerMarginRight(), MR.strings.pref_margin_right),
                Preference.PreferenceItem.SliderPreference(
                    value = (indent * TENTHS).roundToInt(),
                    valueRange = 0..50,
                    title = stringResource(MR.strings.pref_paragraph_indent),
                    subtitle = stringResource(MR.strings.pref_paragraph_indent_summary),
                    valueString = "%.1fem".format(indent),
                    onValueChanged = { indentPref.set(it / TENTHS) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (spacing * TENTHS).roundToInt(),
                    valueRange = 0..30,
                    title = stringResource(MR.strings.pref_paragraph_spacing),
                    subtitle = stringResource(MR.strings.pref_paragraph_spacing_summary),
                    valueString = "%.1fem".format(spacing),
                    onValueChanged = { spacingPref.set(it / TENTHS) },
                ),
            ),
        )
    }

    /** One page margin, in dp. The ceiling is a third of a phone's short edge, past which a column
     *  of text stops being readable. */
    @Composable
    private fun marginRow(
        preference: PreferenceStoreEntry<Int>,
        titleRes: StringResource,
    ): Preference.PreferenceItem.SliderPreference {
        val value by preference.collectAsState()
        return Preference.PreferenceItem.SliderPreference(
            value = value,
            valueRange = 0..64,
            title = stringResource(titleRes),
            valueString = "${value}dp",
            onValueChanged = { preference.set(it) },
        )
    }

    /**
     * How a chapter's markup is processed before it is rendered, in the order the pipeline applies
     * them. The two embedded-markup rows only bind while a WebView renders the chapter; a text
     * renderer draws CSS and scripts as visible characters, so it strips them regardless.
     */
    @Composable
    private fun getChapterTextGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val autoSplitEnabled by novelPreferences.readerAutoSplitText().collectAsState()
        val autoSplitWordCount by novelPreferences.readerAutoSplitWordCount().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_chapter_text),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerHideChapterTitle(),
                    title = stringResource(MR.strings.pref_hide_chapter_title),
                    subtitle = stringResource(MR.strings.pref_hide_chapter_title_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerForceLowercase(),
                    title = stringResource(MR.strings.pref_force_lowercase),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerBlockMedia(),
                    title = stringResource(MR.strings.pref_block_media),
                    subtitle = stringResource(MR.strings.pref_block_media_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerAutoSplitText(),
                    title = stringResource(MR.strings.pref_auto_split_text),
                    subtitle = stringResource(MR.strings.pref_auto_split_text_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = autoSplitWordCount,
                    valueRange = 20..200,
                    steps = 17,
                    title = stringResource(MR.strings.pref_auto_split_word_count),
                    subtitle = "%s",
                    onValueChanged = { novelPreferences.readerAutoSplitWordCount().set(it) },
                ).takeIf { autoSplitEnabled },
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepEmbeddedCss(),
                    title = stringResource(MR.strings.pref_keep_embedded_css),
                    subtitle = stringResource(MR.strings.pref_keep_embedded_css_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepEmbeddedJs(),
                    title = stringResource(MR.strings.pref_keep_embedded_js),
                    subtitle = stringResource(MR.strings.pref_keep_embedded_js_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getReadingGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reading),
            preferenceItems = listOf(
                // Read when a novel is opened, not while one is on screen, so a change applies to the
                // next chapter opened rather than the session in progress.
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerRenderingMode(),
                    entries = NovelRenderingMode.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_novel_rendering_mode),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerDefaultOrientation(),
                    entries = ReaderOrientation.entries
                        .filter { it != ReaderOrientation.DEFAULT && it != ReaderOrientation.REVERSE_PORTRAIT }
                        .associate { it.flagValue to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_rotation_type),
                    subtitle = "%s",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTapToScroll(),
                    title = stringResource(MR.strings.pref_tap_to_scroll),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSwipeGestures(),
                    title = stringResource(MR.strings.pref_swipe_between_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSkipRead(),
                    title = stringResource(MR.strings.pref_skip_read_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSkipFiltered(),
                    title = stringResource(MR.strings.pref_skip_filtered_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSkipDuplicateChapters(),
                    title = stringResource(MR.strings.pref_skip_dupe_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerMarkReadOnSkip(),
                    title = stringResource(MR.strings.pref_mark_read_on_skip),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerAutoScroll(),
                    title = stringResource(MR.strings.pref_auto_scroll),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = novelPreferences.readerBottomButtons(),
                    entries = ReaderBottomButton.offeredIn(ReaderBottomButton.Scope.Novel)
                        .associate { it.value to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_reader_bottom_buttons),
                ),
            ),
        )

    @Composable
    private fun getNavigationGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup {
        val useVolumeButtonsPref = novelPreferences.readerUseVolumeButtons()
        val useVolumeButtons by useVolumeButtonsPref.collectAsState()
        val volumeButtonsFractionPref = novelPreferences.readerVolumeButtonsFraction()
        val volumeButtonsFraction by volumeButtonsFractionPref.collectAsState()
        val volumeButtonsPercent = (volumeButtonsFraction * 100).roundToInt()
        // Ungated, unlike the manga screen's pair: a novel always draws its progress rail, so there is
        // no reading mode to switch on first.
        val railHeightPref = novelPreferences.readerRailHeight()
        val railHeight by railHeightPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = useVolumeButtonsPref,
                    title = stringResource(MR.strings.pref_read_with_volume_keys),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerVolumeButtonsInverted(),
                    title = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
                    enabled = useVolumeButtons,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = volumeButtonsPercent,
                    valueRange = 25..100,
                    title = stringResource(MR.strings.pref_volume_keys_scroll_amount),
                    valueString = "$volumeButtonsPercent%",
                    enabled = useVolumeButtons,
                    onValueChanged = { volumeButtonsFractionPref.set(it / 100f) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerRailOnLeft(),
                    title = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = railHeight,
                    valueRange = 65..100,
                    steps = 6,
                    title = stringResource(MR.strings.pref_vertical_navigator_height),
                    onValueChanged = { railHeightPref.set(it) },
                ),
            ),
        )
    }

    @Composable
    private fun getAccessibilityGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_accessibility),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepScreenOn(),
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerShowProgressPercentage(),
                    title = stringResource(MR.strings.pref_show_reading_progress),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerBionicReading(),
                    title = stringResource(MR.strings.pref_bionic_reading),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerRemoveExtraSpacing(),
                    title = stringResource(MR.strings.pref_remove_extra_spacing),
                ),
            ),
        )
}

/** The slider rows are integers, so an em value rides across as tenths of one. */
private const val TENTHS = 10f
