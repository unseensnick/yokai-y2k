package reikai.presentation.novel.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.presentation.reader.ReaderThemePreset
import reikai.presentation.reader.readerDarkPreset
import reikai.presentation.reader.readerLightPreset
import reikai.presentation.reader.readerThemePresets

/**
 * Resolved reader display settings fed to the WebView (the LNReader `ChapterReaderSettings` subset
 * the web layer reads). [followSystemTheme] is carried so the settings sheet can show the "Auto"
 * state; [NovelReaderScreen] resolves it into the effective [backgroundColor]/[textColor] before render.
 */
data class NovelReaderSettings(
    val fontSize: Int,
    val lineHeight: Float,
    val textAlign: String,
    val padding: Int,
    /** First-line indent, as a multiple of [fontSize]. */
    val paragraphIndent: Float,
    /** Gap after a paragraph, as a multiple of [fontSize]. */
    val paragraphSpacing: Float,
    val fontFamily: String,
    val followSystemTheme: Boolean,
    val backgroundColor: String,
    val textColor: String,
    val keepScreenOn: Boolean,
    /** The per-novel reader orientation `flagValue` (0 = Default, i.e. follow the global default).
     *  Drives the settings sheet's current selection. */
    val orientation: Int,
    /** [orientation] resolved against the global default: the concrete orientation the reader applies. */
    val resolvedOrientation: Int,
    // Text-to-speech: the subset the WebView's `core.js` reads (general `TTSEnable` + the `tts` block).
    val ttsEnabled: Boolean,
    val ttsRate: Float,
    val ttsPitch: Float,
    val ttsAutoPageAdvance: Boolean,
    val ttsScrollToTop: Boolean,
    // Engine extras applied by `core.js` (general settings block).
    val bionicReading: Boolean,
    val removeExtraSpacing: Boolean,
    val tapToScroll: Boolean,
    val swipeGestures: Boolean,
    /** Always-on reading percentage overlay while reading (chrome hidden). Native Compose, not core.js. */
    val showProgressPercentage: Boolean,
    // Driven natively (not by core.js): auto-scroll runs an injected scroller.
    val autoScroll: Boolean,
    val autoScrollSpeed: Float,
    // Driven natively: hardware volume keys scroll the chapter, intercepted at the host window.
    val useVolumeButtons: Boolean,
    val volumeButtonsInverted: Boolean,
    val volumeButtonsFraction: Float,
    // Vertical progress-rail geometry, shared with the manga reader (verticalNavigator prefs).
    val railHeightPercent: Int,
    val railOnLeft: Boolean,
)

/**
 * Brightness + colour-filter overlay settings. Kept separate from [NovelReaderSettings] because they
 * render as a native Compose overlay (plus the host window's brightness) and never touch the WebView,
 * so changing them must not trigger a settings re-push to the web layer.
 */
data class NovelReaderOverlaySettings(
    val customBrightness: Boolean,
    val customBrightnessValue: Int,
    val colorFilter: Boolean,
    val colorFilterValue: Int,
    val colorFilterMode: Int,
)

/** Per-novel orientation choices in the reader sheet: Default (follow the global default) plus the
 *  concrete locks. Reverse-portrait is dropped (rarely wanted); the global-default Settings list
 *  additionally drops Default. */
val readerOrientations = ReaderOrientation.entries.filter { it != ReaderOrientation.REVERSE_PORTRAIT }

/**
 * Applies the "Auto" theme option, which every reader owes before it renders. The stored colours are
 * whatever a manual choice last left behind, so skipping this shows a reader the opposite shade of
 * the one the user is in rather than falling back to a sensible default.
 */
fun NovelReaderSettings.resolvedForSystemTheme(isDark: Boolean): NovelReaderSettings {
    if (!followSystemTheme) return this
    val preset = if (isDark) readerDarkPreset else readerLightPreset
    return copy(backgroundColor = preset.background, textColor = preset.textColor)
}

/** A selectable reader font. [family] is empty for the source's original font and otherwise matches
 *  a bundled `assets/fonts/<family>.ttf` (the path the LNReader web layer loads). */
data class ReaderFont(val family: String, val name: String)

/** Bundled fonts from LNReader (Original + 9 families shipped under assets/fonts/). */
val readerFonts = listOf(
    ReaderFont("", "Original"),
    ReaderFont("lora", "Lora"),
    ReaderFont("nunito", "Nunito"),
    ReaderFont("noto-sans", "Noto Sans"),
    ReaderFont("open-sans", "Open Sans"),
    ReaderFont("arbutus-slab", "Arbutus Slab"),
    ReaderFont("domine", "Domine"),
    ReaderFont("lato", "Lato"),
    ReaderFont("pt-serif", "PT Serif"),
    ReaderFont("OpenDyslexic3-Regular", "OpenDyslexic"),
)
