package reikai.domain.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import reikai.domain.novel.model.NovelMigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Net-new preferences for the light-novel vertical. Only the subset the plugin host / source /
 * install / update layers need lands here; later stages (reader, library, merge) grow this
 * holder. Key strings are preserved from the Yōkai-era fork so an in-place upgrade keeps state.
 */
@Inject
@SingleIn(AppScope::class)
class NovelPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Canonicalized plugin .js URLs the user has installed. The plugin id is not stored here: it is
     * read from each plugin after load, so an unloadable plugin (404, parse error) can still be
     * uninstalled by removing its URL.
     */
    fun installedPluginUrls() = preferenceStore.getStringSet(INSTALLED_PLUGIN_URLS_KEY, emptySet())

    /**
     * Set by backup restore when [installedPluginUrls] came from the backup. A restored set can carry
     * arbitrary plugin .js URLs that auto-load and get evaluated, so LnPluginInstaller validates them
     * against the added repos before loading any, then clears this flag.
     */
    fun pluginsNeedRevalidation() = preferenceStore.getBoolean(PLUGINS_NEED_REVALIDATION_KEY, false)

    /**
     * Per-plugin metadata side-table keyed by the same canonicalized URL as [installedPluginUrls].
     * Carries the registry's icon URL (so the sources list renders real icons), version, and lang.
     */
    fun installedPluginMetadata() = preferenceStore.getObjectFromString(
        key = "ln_installed_plugin_metadata",
        defaultValue = emptyMap(),
        serializer = { metadataJson.encodeToString(metadataMapSerializer, it) },
        deserializer = {
            runCatching { metadataJson.decodeFromString(metadataMapSerializer, it) }.getOrElse { emptyMap() }
        },
    )

    /**
     * Last-known display identity (name, icon, lang) per plugin id, kept so the Browse migration list
     * can render a source whose plugin is no longer installed (manga-stub parity). Written on every
     * source load/install and deliberately NOT pruned on uninstall, so the row survives removal. A
     * source never seen on this device (e.g. a backup restore) is absent and falls back to its raw id.
     */
    fun seenNovelSources() = preferenceStore.getObjectFromString(
        key = "ln_seen_novel_sources",
        defaultValue = emptyMap(),
        serializer = { metadataJson.encodeToString(seenSourcesMapSerializer, it) },
        deserializer = {
            runCatching { metadataJson.decodeFromString(seenSourcesMapSerializer, it) }.getOrElse { emptyMap() }
        },
    )

    /**
     * Plugin repo URLs (i.e. `plugins.min.json` registries) the user added. Distinct from
     * [installedPluginUrls]: this tracks repos (sources of plugins); that tracks the individual
     * `.js` URLs actually installed.
     */
    fun addedRepoUrls() = preferenceStore.getStringSet("novel_added_repo_urls", emptySet())

    /** Count of installed plugins whose stored version is older than the latest registry version. */
    fun pluginUpdatesCount() = preferenceStore.getInt("ln_plugin_updates_count", 0)

    /** Last successful update-check timestamp (millis); gates the on-launch path to skip if recent. */
    fun lastLnPluginCheck() = preferenceStore.getLong("ln_plugin_last_check", 0L)

    /** Epoch-millis the last novel library update started; feeds the shared Updates "Last updated"
     *  line. App-state (not backed up), mirroring manga's [LibraryPreferences.lastUpdatedTimestamp]. */
    fun novelLibraryUpdateLastTimestamp() =
        preferenceStore.getLong(Preference.appStateKey("novel_library_update_last_timestamp"), 0L)

    // Global chapter sort / filter / display defaults. A novel falls back to these unless its
    // own `chapterFlags` local bit is set (see [reikai.domain.novel.model.NovelChapterFlags]). Stored
    // as the same bitmask values the per-novel flags use, so "Set as default" is a straight copy.

    /** Default chapter sort method (source order / number / upload date) as the SORTING_MASK bits. */
    fun defaultChapterSortOrder() = preferenceStore.getLong("ln_default_chapter_sort", 0L)

    /** Default chapter sort direction; true = newest/highest first (matches the manga default). */
    fun defaultChapterSortDescending() = preferenceStore.getBoolean("ln_default_chapter_sort_desc", true)

    /** Default unread filter as the READ_MASK bits (0 = show all). */
    fun defaultChapterFilterUnread() = preferenceStore.getLong("ln_default_chapter_filter_unread", 0L)

    /** Default bookmarked filter as the BOOKMARKED_MASK bits (0 = show all). */
    fun defaultChapterFilterBookmarked() = preferenceStore.getLong("ln_default_chapter_filter_bookmarked", 0L)

    /** Default downloaded filter as the DOWNLOADED_MASK bits (0 = show all). */
    fun defaultChapterFilterDownloaded() = preferenceStore.getLong("ln_default_chapter_filter_downloaded", 0L)

    /** Default display: true shows "Chapter N", false shows the source chapter title. */
    fun defaultChapterHideTitles() = preferenceStore.getBoolean("ln_default_chapter_hide_titles", false)

    /**
     * Chapters the user manually hid (e.g. a source's own duplicate listings). Each entry is the
     * restore-stable key `"<source>|<chapterUrl>"` (no local novel id, so it survives a backup
     * restore where novels are re-inserted with new ids). Backed up automatically as a normal pref.
     */
    fun hiddenChapters() = preferenceStore.getStringSet("novel_hidden_chapters", emptySet())

    // Reader display + theme. Fed into the WebView reader's `--readerSettings-*` CSS vars and
    // pushed live on change. Key strings preserved from the Yōkai-era fork for upgrade continuity.

    fun readerFontSize() = preferenceStore.getInt("ln_reader_font_size_sp", 16)
    fun readerLineSpacing() = preferenceStore.getFloat("ln_reader_line_spacing", 1.5f)
    fun readerTextAlign() = preferenceStore.getString("ln_reader_text_align", "left")
    fun readerFontFamily() = preferenceStore.getString("ln_reader_font_family", "")
    fun readerPadding() = preferenceStore.getInt("ln_reader_padding", 16)

    /** Hold the screen awake while reading (Android FLAG_KEEP_SCREEN_ON). Separate from the manga
     *  reader's key on purpose, not twin debt to unify: see docs/dev/plans/settings-restructure.md. */
    fun readerKeepScreenOn() = preferenceStore.getBoolean("ln_reader_keep_screen_on", false)

    /** Default reader orientation for novels with no per-novel override, the novel twin of the manga
     *  reader's `defaultOrientationType`. Stores a [ReaderOrientation] `flagValue`. */
    fun readerDefaultOrientation() =
        preferenceStore.getInt("ln_reader_default_orientation", ReaderOrientation.FREE.flagValue)

    /**
     * Which reader a novel opens in. Defaults to the standalone reader while the shared host is
     * built out, so the half-finished one is opt-in rather than what everyone lands in.
     */
    fun readerRenderingMode() =
        preferenceStore.getEnum("ln_reader_rendering_mode", NovelRenderingMode.LEGACY)

    /** When true the reader follows the system light/dark mode; otherwise the chosen preset wins. */
    fun readerFollowSystemTheme() = preferenceStore.getBoolean("ln_reader_follow_system_theme", true)
    fun readerBackgroundColor() = preferenceStore.getString("ln_reader_bg_color", "#292832")
    fun readerTextColor() = preferenceStore.getString("ln_reader_text_color", "#CCCCCC")

    /** When on, the reader's next/previous skip a chapter whose number matches the one just read (the
     *  same-number duplicates a cross-source merge produces). Reading-navigation only, non-destructive. */
    fun readerSkipDuplicateChapters() = preferenceStore.getBoolean("ln_reader_skip_duplicate_chapters", false)

    /**
     * Forward-only skips, the novel twins of the manga reader's `skipRead` / `skipFiltered`. Their own
     * keys rather than the manga ones, so a reader can skip read chapters in one library and not the
     * other; the defaults match manga's so the two behave alike until someone changes one.
     */
    fun readerSkipRead() = preferenceStore.getBoolean("ln_reader_skip_read", false)

    fun readerSkipFiltered() = preferenceStore.getBoolean("ln_reader_skip_filtered", true)

    /**
     * The progress rail's side and height. Novels always draw the rail, so unlike the manga reader's
     * pair these are never gated on a reading mode; the manga rows are hidden until a vertical
     * navigator is switched on, which used to leave a novel reader's rail configured by settings the
     * user could not see. Defaults match the manga ones.
     */
    fun readerRailOnLeft() = preferenceStore.getBoolean("ln_reader_rail_on_left", false)

    fun readerRailHeight() = preferenceStore.getInt("ln_reader_rail_height", 65)

    /** When on, tapping "next" marks the chapter you skipped away from as read (forward only), the novel
     *  twin of the manga reader's mark-read-on-skip. Opt-in. */
    fun readerMarkReadOnSkip() = preferenceStore.getBoolean("ln_reader_mark_read_on_skip", false)

    // Brightness + colour filter. Novel-specific (independent of the manga reader's global
    // ReaderPreferences, so each reader keeps its own); the shared ReaderContentOverlay renders them
    // and brightness also drives the host window's screenBrightness.

    /** Override the screen brightness while reading instead of following the system. */
    fun readerCustomBrightness() = preferenceStore.getBoolean("ln_reader_custom_brightness", false)

    /** Custom brightness, -75..100: positive sets the window brightness, negative dims via a black
     *  overlay (below the system minimum), 0 = system brightness. */
    fun readerCustomBrightnessValue() = preferenceStore.getInt("ln_reader_custom_brightness_value", 0)

    /** Tint the reading surface with a colour overlay. */
    fun readerColorFilter() = preferenceStore.getBoolean("ln_reader_color_filter", false)

    /** Packed ARGB colour for the filter overlay. */
    fun readerColorFilterValue() = preferenceStore.getInt("ln_reader_color_filter_value", 0)

    /** Blend-mode index into `ReaderPreferences.ColorFilterMode`. */
    fun readerColorFilterMode() = preferenceStore.getInt("ln_reader_color_filter_mode", 0)

    // Text-to-speech (reader engine extras, round 2). The bundled `core.js` posts `speak` messages we
    // voice with Android TextToSpeech; these prefs drive the engine + the WebView's `tts` settings block.

    /** Master switch: show the floating play control and let `core.js` run TTS. Off by default. */
    fun readerTtsEnabled() = preferenceStore.getBoolean("ln_reader_tts_enabled", false)

    /** Chosen `TextToSpeech` engine package (e.g. `com.google.android.tts`); empty = system default. */
    fun readerTtsEngine() = preferenceStore.getString("ln_reader_tts_engine", "")

    /** Chosen voice name within the engine (the `Voice.name` id); empty = engine default. */
    fun readerTtsVoice() = preferenceStore.getString("ln_reader_tts_voice", "")

    /** Base language codes (e.g. `en`, `ja`) the voice picker is filtered to. Empty = show every
     *  language the engine offers. */
    fun readerTtsLanguages() = preferenceStore.getStringSet("ln_reader_tts_languages", emptySet())

    /** Speech rate multiplier (0.1..5.0; 1.0 = normal). */
    fun readerTtsRate() = preferenceStore.getFloat("ln_reader_tts_rate", 1.0f)

    /** Speech pitch multiplier (0.1..5.0; 1.0 = normal). */
    fun readerTtsPitch() = preferenceStore.getFloat("ln_reader_tts_pitch", 1.0f)

    /** When the chapter finishes reading aloud, auto-advance to the next chapter and keep reading. */
    fun readerTtsAutoPageAdvance() = preferenceStore.getBoolean("ln_reader_tts_auto_page_advance", false)

    /** Scroll the spoken paragraph near the top (vs centering it) as TTS advances. */
    fun readerTtsScrollToTop() = preferenceStore.getBoolean("ln_reader_tts_scroll_to_top", true)

    /** Persisted floating-puck position (dp offsets within the reader). [Int.MIN_VALUE] = not yet
     *  placed, so the puck uses its default anchor. */
    fun readerTtsButtonX() = preferenceStore.getInt("ln_reader_tts_button_x", Int.MIN_VALUE)
    fun readerTtsButtonY() = preferenceStore.getInt("ln_reader_tts_button_y", Int.MIN_VALUE)

    // Reader engine extras (round 2). Flags the bundled `core.js` applies to the chapter text; toggling
    // one re-pushes the general settings block, which reflows the text in place.

    /** Bold the start of each word (bionic reading) to ease skimming. */
    fun readerBionicReading() = preferenceStore.getBoolean("ln_reader_bionic_reading", false)

    /** Collapse large runs of blank space between paragraphs. */
    fun readerRemoveExtraSpacing() = preferenceStore.getBoolean("ln_reader_remove_extra_spacing", false)

    /** Show the always-on reading percentage while reading (chrome hidden), the novel twin of the manga
     *  reader's "Show page number". Native Compose overlay; on by default (matches manga and LNReader). */
    fun readerShowProgressPercentage() = preferenceStore.getBoolean("ln_reader_show_progress_percentage", true)

    /** Tap the top / bottom of the screen to scroll a page (center tap still toggles the chrome). */
    fun readerTapToScroll() = preferenceStore.getBoolean("ln_reader_tap_to_scroll", false)

    /** Swipe left / right to go to the next / previous chapter. */
    fun readerSwipeGestures() = preferenceStore.getBoolean("ln_reader_swipe_gestures", false)

    /** Continuously scroll the chapter while reading (paused while the chrome is shown). */
    fun readerAutoScroll() = preferenceStore.getBoolean("ln_reader_auto_scroll", false)

    /** Auto-scroll speed in CSS pixels per frame (~60fps). */
    fun readerAutoScrollSpeed() = preferenceStore.getFloat("ln_reader_auto_scroll_speed", 1.0f)

    /** Scroll the chapter with the hardware volume keys (down = forward), the novel twin of the manga
     *  reader's `readWithVolumeKeys`. Intercepted at the host window; off by default. */
    fun readerUseVolumeButtons() = preferenceStore.getBoolean("ln_reader_use_volume_buttons", false)

    /** Swap which volume key scrolls forward vs back, mirroring `readWithVolumeKeysInverted`. */
    fun readerVolumeButtonsInverted() = preferenceStore.getBoolean("ln_reader_volume_buttons_inverted", false)

    /** How far one volume press scrolls, as a fraction of the screen height (LNReader's default is
     *  0.75, leaving a quarter-screen overlap for reading continuity). */
    fun readerVolumeButtonsFraction() = preferenceStore.getFloat("ln_reader_volume_buttons_fraction", 0.75f)

    /** User-selected bottom-bar buttons for the novel reader (the novel twin of the manga
     *  [ReaderPreferences.readerBottomButtons]). Values are [ReaderBottomButton.value] codes. */
    fun readerBottomButtons() =
        preferenceStore.getStringSet("ln_reader_bottom_buttons", ReaderBottomButton.NOVEL_BUTTONS_DEFAULTS)

    // Chapter text pipeline. Applied to the chapter body before it is rendered, in the order
    // [reikai.novel.content.NovelContentPipeline] runs its stages.

    /** Drop a heading at the top of the body that repeats the chapter name the reader already shows. */
    fun readerHideChapterTitle() = preferenceStore.getBoolean("ln_reader_hide_chapter_title", false)

    /** Lowercase the whole chapter body. */
    fun readerForceLowercase() = preferenceStore.getBoolean("ln_reader_force_lowercase", false)

    /** Strip `img` / `video` / `audio` tags from the chapter body. */
    fun readerBlockMedia() = preferenceStore.getBoolean("ln_reader_block_media", false)

    /** Keep a chapter's own `style` blocks and stylesheet links. Only a WebView renderer can honour
     *  this: a TextView draws CSS as visible text, so it strips it whatever this says. */
    fun readerKeepEmbeddedCss() = preferenceStore.getBoolean("ln_reader_keep_embedded_css", true)

    /** Keep a chapter's own `script` blocks. Off by default: they are the source page's own code
     *  (ads, loaders, analytics) rather than chapter content. */
    fun readerKeepEmbeddedJs() = preferenceStore.getBoolean("ln_reader_keep_embedded_js", false)

    /** Insert paragraph breaks into chapters that arrive as one unbroken block of text. */
    fun readerAutoSplitText() = preferenceStore.getBoolean("ln_reader_auto_split_text", false)

    /** Words to pass before an auto-split break looks for the next sentence end. The splitter floors
     *  this at 20, so a smaller value does not shred the text. */
    fun readerAutoSplitWordCount() = preferenceStore.getInt("ln_reader_auto_split_word_count", 50)

    /** User find/replace rules, a JSON array of [reikai.novel.content.NovelRegexReplacement]. No editor
     *  UI yet, so this stays at its empty default and the pipeline stage is a no-op. */
    fun readerRegexReplacements() = preferenceStore.getString("ln_reader_regex_replacements", "[]")

    // Library.

    /** Category a newly favorited novel auto-lands in, the novel twin of manga's
     *  [LibraryPreferences.defaultCategory]. -1 = prompt for a category when the user has any. */
    fun defaultNovelCategory() = preferenceStore.getInt("default_novel_category", -1)

    /** Hide the inline "N missing chapters" separators in the details chapter list, the novel twin of
     *  manga's [LibraryPreferences.hideMissingChapters]. The header warning stays regardless. */
    fun hideMissingChapters() = preferenceStore.getBoolean("novel_hide_missing_chapters", false)

    // Downloads. Key strings preserved from the Yōkai-era fork for upgrade continuity.

    /** Delete a downloaded chapter's offline copy once it's marked read. */
    fun removeAfterMarkedAsRead() = preferenceStore.getBoolean("novel_remove_after_marked_as_read", false)

    /** Keep only the last N read chapters downloaded (a rolling buffer), the novel twin of manga's
     *  `removeAfterReadSlots`. -1 = off; 0 = delete the just-read chapter; 1 = keep 1 back, etc. When
     *  set (>= 0) it takes precedence over [removeAfterMarkedAsRead]. */
    fun removeAfterReadSlots() = preferenceStore.getInt("novel_remove_after_read_slots", -1)

    /** When false (default), never auto-delete a bookmarked chapter on read. Twin of manga's
     *  `removeBookmarkedChapters`. */
    fun removeBookmarkedChapters() = preferenceStore.getBoolean("novel_remove_bookmarked", false)

    /** Category ids whose novels' chapters are never auto-deleted on read. Twin of manga's
     *  `removeExcludeCategories`. */
    fun removeExcludeCategories() = preferenceStore.getStringSet("novel_remove_exclude_categories", emptySet())

    /** Download the next N un-downloaded chapters as you read (download-ahead). 0 = off. Twin of
     *  manga's `autoDownloadWhileReading`. */
    fun autoDownloadWhileReading() = preferenceStore.getInt("novel_auto_download_while_reading", 0)

    /** Auto-download newly fetched chapters when an update is detected. The pref + download-manager
     *  plumbing exist; the update-detection trigger that consumes it is wired into the background
     *  update job. */
    fun downloadNewChapters() = preferenceStore.getBoolean("novel_download_new_chapters", false)

    /** When auto-downloading, skip a new chapter whose number matches one already read (avoids
     *  re-downloading a source's duplicate listing of a chapter you've finished). */
    fun downloadNewUnreadChaptersOnly() =
        preferenceStore.getBoolean("novel_download_new_unread_chapters_only", false)

    /** Restrict auto-download to novels in these categories (empty = all). Mirrors the manga keys. */
    fun downloadNewChapterCategories() = preferenceStore.getStringSet("novel_download_new_categories", emptySet())

    fun downloadNewChapterCategoriesExclude() =
        preferenceStore.getStringSet("novel_download_new_categories_exclude", emptySet())

    // Background chapter updates.

    /** How often the background novel-update job runs, in hours. 0 = off (the default, matching the
     *  manga library's off-by-default); 12/24/48/72/168 mirror the manga interval options. */
    fun libraryUpdateInterval() = preferenceStore.getInt("novel_library_update_interval", 0)

    /** Device conditions gating the background job, reusing the manga restriction keys
     *  ([LibraryPreferences.DEVICE_ONLY_ON_WIFI] etc.) so the same Constraints builder applies. */
    fun libraryUpdateDeviceRestrictions() =
        preferenceStore.getStringSet(
            "novel_library_update_restrictions",
            setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI),
        )

    /** Smart-update restrictions, reusing the manga restriction keys ([LibraryPreferences.MANGA_HAS_UNREAD]
     *  etc.) for parallel semantics: skip completed / skip with unread / skip unstarted. Defaults to the
     *  same set the manga `autoUpdateMangaRestrictions` defaults to (minus release-period prediction,
     *  which novels lack) so the two sides start identical. */
    fun novelUpdateRestrictions() = preferenceStore.getStringSet(
        "novel_library_smart_update",
        setOf(
            LibraryPreferences.MANGA_HAS_UNREAD,
            LibraryPreferences.MANGA_NON_COMPLETED,
            LibraryPreferences.MANGA_NON_READ,
        ),
    )

    /** Categories to include / exclude from the background update (mirrors the manga update categories). */
    fun novelUpdateCategories() = preferenceStore.getStringSet("novel_library_update_categories", emptySet())
    fun novelUpdateCategoriesExclude() =
        preferenceStore.getStringSet("novel_library_update_categories_exclude", emptySet())

    // Source migration.

    /** Last selection in the migrate dialog, as a [NovelMigrationFlag] bitmask. Defaults to all on. */
    fun novelMigrationFlags() = preferenceStore.getInt("novel_migration_flags", NovelMigrationFlag.DEFAULT_BITS)

    /** Hide rows whose search found nothing. Twin of the manga migration pref. */
    fun novelMigrationHideUnmatched() = preferenceStore.getBoolean("novel_migration_hide_unmatched", false)

    /** Hide rows whose match is no further ahead than the entry already is. */
    fun novelMigrationHideWithoutUpdates() =
        preferenceStore.getBoolean("novel_migration_hide_without_updates", false)

    companion object {
        // Referenced by backup restore (PreferenceRestorer) to flag restored plugin URLs for
        // validation against the added repos before the host evaluates any.
        const val INSTALLED_PLUGIN_URLS_KEY = "ln_installed_plugin_urls"
        const val PLUGINS_NEED_REVALIDATION_KEY = "ln_plugins_need_revalidation"

        private val metadataMapSerializer =
            MapSerializer(String.serializer(), LnInstalledPluginMetadata.serializer())
        private val seenSourcesMapSerializer =
            MapSerializer(String.serializer(), LnSourceIdentity.serializer())
        private val metadataJson = Json { ignoreUnknownKeys = true }
    }
}
