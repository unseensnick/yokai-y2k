package reikai.presentation.reader

/** A reader background + text colour pairing. Choosing one writes both. */
data class ReaderThemePreset(val name: String, val background: String, val textColor: String)

/** The five presets from LNReader (light, sepia, mint, dark, black). */
val readerThemePresets = listOf(
    ReaderThemePreset("Light", "#f5f5fa", "#111111"),
    ReaderThemePreset("Sepia", "#F7DFC6", "#593100"),
    ReaderThemePreset("Mint", "#dce5e2", "#000000"),
    ReaderThemePreset("Dark", "#292832", "#CCCCCC"),
    ReaderThemePreset("Black", "#000000", "#FFFFFFB3"),
)

/** Presets the "Auto" (follow-system) option resolves to for light and dark system modes. */
val readerLightPreset = readerThemePresets.first { it.name == "Light" }
val readerDarkPreset = readerThemePresets.first { it.name == "Dark" }
