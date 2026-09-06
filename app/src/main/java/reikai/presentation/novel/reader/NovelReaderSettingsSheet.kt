package reikai.presentation.novel.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ExpandMore
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsVoice
import reikai.presentation.icons.FormatAlignCenter
import reikai.presentation.icons.FormatAlignJustify
import reikai.presentation.icons.FormatAlignLeft
import reikai.presentation.icons.FormatAlignRight
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.reader.PresetSwatch
import reikai.presentation.reader.ReaderThemePreset
import reikai.presentation.reader.readerDarkPreset
import reikai.presentation.reader.readerLightPreset
import reikai.presentation.reader.readerThemePresets
import tachiyomi.presentation.core.components.lockPagerSwipeWhileDragging
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reader settings in a [TabbedDialog] with three tabs: General (the reading toggles), Display
 * (typography + theme), and TTS. Holds no prefs: the caller's ScreenModel persists changes and feeds
 * new [settings] back in, which the reader pushes to the WebView live.
 */
// no ScreenModel: pure UI, state owned by the caller's ScreenModel.
@Composable
fun NovelReaderSettingsSheet(
    settings: NovelReaderSettings,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onTextAlign: (String) -> Unit,
    onFontFamily: (String) -> Unit,
    onFollowSystem: () -> Unit,
    onPreset: (ReaderThemePreset) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onOrientation: (Int) -> Unit,
    onTtsEnabled: (Boolean) -> Unit,
    onTtsRate: (Float) -> Unit,
    onTtsPitch: (Float) -> Unit,
    onTtsAutoPageAdvance: (Boolean) -> Unit,
    onTtsScrollToTop: (Boolean) -> Unit,
    onTtsEngine: (String) -> Unit,
    onTtsVoice: (String) -> Unit,
    onTtsLanguages: (Set<String>) -> Unit,
    ttsEngines: () -> List<TtsEngineInfo>,
    ttsVoices: () -> List<TtsVoice>,
    currentTtsEngine: String,
    currentTtsVoice: String,
    currentTtsLanguages: Set<String>,
    onBionicReading: (Boolean) -> Unit,
    onRemoveExtraSpacing: (Boolean) -> Unit,
    onTapToScroll: (Boolean) -> Unit,
    onSwipeGestures: (Boolean) -> Unit,
    onAutoScroll: (Boolean) -> Unit,
    onAutoScrollSpeed: (Float) -> Unit,
    overlay: NovelReaderOverlaySettings,
    onCustomBrightness: (Boolean) -> Unit,
    onCustomBrightnessValue: (Int) -> Unit,
    onColorFilter: (Boolean) -> Unit,
    onColorFilterValue: (Int) -> Unit,
    onColorFilterMode: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = listOf("General", "Display", "TTS"),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                1 -> {
                    LabeledSlider("Font size", "${settings.fontSize}", settings.fontSize.toFloat(), 12f..32f, 19) {
                        onFontSize(it.toInt())
                    }
                    LabeledSlider(
                        "Line height",
                        "%.1f".format(settings.lineHeight),
                        settings.lineHeight,
                        1.0f..2.5f,
                        14,
                    ) {
                        onLineHeight((it * 10).toInt() / 10f)
                    }
                    Text("Alignment", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AlignButton(ReikaiIcons.FormatAlignLeft, "left", settings.textAlign, onTextAlign)
                        AlignButton(ReikaiIcons.FormatAlignCenter, "center", settings.textAlign, onTextAlign)
                        AlignButton(ReikaiIcons.FormatAlignJustify, "justify", settings.textAlign, onTextAlign)
                        AlignButton(
                            ReikaiIcons.FormatAlignRight,
                            "right",
                            settings.textAlign,
                            onTextAlign,
                        )
                    }
                    Text("Font", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .lockPagerSwipeWhileDragging().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        readerFonts.forEach { font ->
                            // Preview each chip in its own bundled font; Compose caches the typeface.
                            val fontFamily = remember(font.family) {
                                if (font.family.isEmpty()) {
                                    FontFamily.Default
                                } else {
                                    FontFamily(Font(path = "fonts/${font.family}.ttf", assetManager = context.assets))
                                }
                            }
                            FilterChip(
                                selected = settings.fontFamily == font.family,
                                onClick = { onFontFamily(font.family) },
                                label = { Text(font.name, fontFamily = fontFamily) },
                            )
                        }
                    }
                    Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onFollowSystem).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(selected = settings.followSystemTheme, onClick = null)
                        Text("Follow system (auto light / dark)", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        readerThemePresets.forEach { preset ->
                            val selected = !settings.followSystemTheme &&
                                settings.backgroundColor.equals(preset.background, ignoreCase = true)
                            PresetSwatch(preset, selected) { onPreset(preset) }
                        }
                    }
                    // Per-novel orientation: Default (follow the global default) + the concrete locks.
                    Text(
                        "Orientation",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .lockPagerSwipeWhileDragging().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        readerOrientations.forEach { orientation ->
                            FilterChip(
                                selected = settings.orientation == orientation.flagValue,
                                onClick = { onOrientation(orientation.flagValue) },
                                leadingIcon = { Icon(orientation.icon, contentDescription = null) },
                                label = { Text(stringResource(orientation.stringRes)) },
                            )
                        }
                    }
                    Text(
                        "Brightness & color filter",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SwitchRow("Custom brightness", overlay.customBrightness, onCustomBrightness)
                    if (overlay.customBrightness) {
                        LabeledSlider(
                            "Brightness",
                            "${overlay.customBrightnessValue}",
                            overlay.customBrightnessValue.toFloat(),
                            -75f..100f,
                            0,
                        ) { onCustomBrightnessValue(it.roundToInt()) }
                    }
                    SwitchRow("Color filter", overlay.colorFilter, onColorFilter)
                    if (overlay.colorFilter) {
                        val argb = overlay.colorFilterValue
                        LabeledSlider("Red", "${argb.red}", argb.red.toFloat(), 0f..255f, 0) {
                            onColorFilterValue(getColorValue(argb, it.roundToInt(), RED_MASK, 16))
                        }
                        LabeledSlider("Green", "${argb.green}", argb.green.toFloat(), 0f..255f, 0) {
                            onColorFilterValue(getColorValue(argb, it.roundToInt(), GREEN_MASK, 8))
                        }
                        LabeledSlider("Blue", "${argb.blue}", argb.blue.toFloat(), 0f..255f, 0) {
                            onColorFilterValue(getColorValue(argb, it.roundToInt(), BLUE_MASK, 0))
                        }
                        LabeledSlider("Opacity", "${argb.alpha}", argb.alpha.toFloat(), 0f..255f, 0) {
                            onColorFilterValue(getColorValue(argb, it.roundToInt(), ALPHA_MASK, 24))
                        }
                        Text(
                            "Blend mode",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .lockPagerSwipeWhileDragging().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ColorFilterMode.forEachIndexed { index, mode ->
                                FilterChip(
                                    selected = overlay.colorFilterMode == index,
                                    onClick = { onColorFilterMode(index) },
                                    label = { Text(stringResource(mode.first)) },
                                )
                            }
                        }
                    }
                }
                0 -> {
                    SwitchRow("Bionic reading", settings.bionicReading, onBionicReading)
                    SwitchRow("Remove extra spacing", settings.removeExtraSpacing, onRemoveExtraSpacing)
                    SwitchRow("Auto-scroll", settings.autoScroll, onAutoScroll)
                    if (settings.autoScroll) {
                        LabeledSlider(
                            "Scroll speed",
                            "%.1f".format(settings.autoScrollSpeed),
                            settings.autoScrollSpeed,
                            0.2f..4.0f,
                            18,
                        ) {
                            onAutoScrollSpeed((it * 10).roundToInt() / 10f)
                        }
                    }
                    SwitchRow("Tap edges to scroll", settings.tapToScroll, onTapToScroll)
                    SwitchRow("Swipe between chapters", settings.swipeGestures, onSwipeGestures)
                    SwitchRow("Keep screen on", settings.keepScreenOn, onKeepScreenOn)
                }
                2 -> TtsTab(
                    settings = settings,
                    onTtsEnabled = onTtsEnabled,
                    onTtsRate = onTtsRate,
                    onTtsPitch = onTtsPitch,
                    onTtsAutoPageAdvance = onTtsAutoPageAdvance,
                    onTtsScrollToTop = onTtsScrollToTop,
                    onTtsEngine = onTtsEngine,
                    onTtsVoice = onTtsVoice,
                    onTtsLanguages = onTtsLanguages,
                    ttsEngines = ttsEngines,
                    ttsVoices = ttsVoices,
                    currentTtsEngine = currentTtsEngine,
                    currentTtsVoice = currentTtsVoice,
                    currentTtsLanguages = currentTtsLanguages,
                )
            }
        }
    }
}

@Composable
private fun TtsTab(
    settings: NovelReaderSettings,
    onTtsEnabled: (Boolean) -> Unit,
    onTtsRate: (Float) -> Unit,
    onTtsPitch: (Float) -> Unit,
    onTtsAutoPageAdvance: (Boolean) -> Unit,
    onTtsScrollToTop: (Boolean) -> Unit,
    onTtsEngine: (String) -> Unit,
    onTtsVoice: (String) -> Unit,
    onTtsLanguages: (Set<String>) -> Unit,
    ttsEngines: () -> List<TtsEngineInfo>,
    ttsVoices: () -> List<TtsVoice>,
    currentTtsEngine: String,
    currentTtsVoice: String,
    currentTtsLanguages: Set<String>,
) {
    SwitchRow("Enable text to speech", settings.ttsEnabled, onTtsEnabled)
    if (!settings.ttsEnabled) return

    // The TTS backend initializes asynchronously, so poll briefly until its voices populate.
    var engines by remember { mutableStateOf<List<TtsEngineInfo>>(emptyList()) }
    var voices by remember { mutableStateOf<List<TtsVoice>>(emptyList()) }
    LaunchedEffect(Unit) {
        repeat(12) {
            engines = ttsEngines()
            voices = ttsVoices()
            if (voices.isNotEmpty()) return@LaunchedEffect
            delay(300)
        }
    }

    var selectedEngine by remember { mutableStateOf(currentTtsEngine) }
    var selectedVoice by remember { mutableStateOf(currentTtsVoice) }
    var selectedLanguages by remember { mutableStateOf(currentTtsLanguages) }
    var dialog by remember { mutableStateOf(TtsDialog.None) }

    // Distinct base languages the engine offers (e.g. en, ja), shown by their display name.
    val languages = remember(voices) {
        voices.map { it.locale.substringBefore('-') }
            .filter { it.isNotBlank() }
            .distinct()
            .map { code -> (Locale.forLanguageTag(code).displayLanguage.ifBlank { code }) to code }
            .sortedBy { it.first }
    }
    val shownVoices = if (selectedLanguages.isEmpty()) {
        voices
    } else {
        voices.filter { it.locale.substringBefore('-') in selectedLanguages }
    }

    if (engines.size > 1) {
        PickerRow("Engine", engines.firstOrNull { it.packageName == selectedEngine }?.label ?: "Default") {
            dialog = TtsDialog.Engine
        }
    }
    if (languages.size > 1) {
        PickerRow("Languages", if (selectedLanguages.isEmpty()) "All" else "${selectedLanguages.size} selected") {
            dialog = TtsDialog.Languages
        }
    }
    PickerRow("Voice", voices.firstOrNull { it.name == selectedVoice }?.displayName ?: "Default") {
        dialog = TtsDialog.Voice
    }
    LabeledSlider("Speed", "%.1fx".format(settings.ttsRate), settings.ttsRate, 0.1f..3.0f, 28) {
        onTtsRate((it * 10).roundToInt() / 10f)
    }
    LabeledSlider("Pitch", "%.1f".format(settings.ttsPitch), settings.ttsPitch, 0.1f..2.0f, 18) {
        onTtsPitch((it * 10).roundToInt() / 10f)
    }
    SwitchRow("Auto page advance", settings.ttsAutoPageAdvance, onTtsAutoPageAdvance)
    SwitchRow("Scroll to top", settings.ttsScrollToTop, onTtsScrollToTop)

    when (dialog) {
        TtsDialog.Engine -> SingleSelectDialog(
            title = "TTS engine",
            options = engines.map { it.label to it.packageName },
            selected = selectedEngine,
            onSelect = { pkg ->
                selectedEngine = pkg
                selectedVoice = ""
                onTtsEngine(pkg)
            },
            onDismiss = { dialog = TtsDialog.None },
        )
        TtsDialog.Languages -> MultiSelectDialog(
            title = "Languages",
            options = languages,
            selected = selectedLanguages,
            onToggle = { code ->
                selectedLanguages =
                    if (code in selectedLanguages) selectedLanguages - code else selectedLanguages + code
                onTtsLanguages(selectedLanguages)
            },
            onDismiss = { dialog = TtsDialog.None },
        )
        TtsDialog.Voice -> SingleSelectDialog(
            title = "Voice",
            options = listOf("Default" to "") + shownVoices.map { it.displayName to it.name },
            selected = selectedVoice,
            onSelect = { name ->
                selectedVoice = name
                onTtsVoice(name)
            },
            onDismiss = { dialog = TtsDialog.None },
        )
        TtsDialog.None -> Unit
    }
}

private enum class TtsDialog { None, Engine, Languages, Voice }

/** A settings row that opens a selection dialog: label on the left, current value + dropdown caret
 *  on the right, matching the sheet's other rows. */
@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            Icon(
                MaterialSymbols.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SingleSelectDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(options) { (display, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(display, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MultiSelectDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(options) { (display, code) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(code) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = code in selected, onCheckedChange = null)
                        Text(display, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Text("$label: $value", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = sliderValue.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        // Keep slider drags off the OS edge back-gesture and off the tab pager while dragging.
        modifier = Modifier.systemGestureExclusion().lockPagerSwipeWhileDragging(),
    )
}

@Composable
private fun AlignButton(icon: ImageVector, value: String, current: String, onClick: (String) -> Unit) {
    val selected = current == value
    IconButton(onClick = { onClick(value) }) {
        Icon(
            imageVector = icon,
            contentDescription = value,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Replace one channel of a packed ARGB int (mirrors the manga ColorFilterPage helper). */
private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int =
    (color shl bitShift) or (currentColor and mask.inv().toInt())

private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF
