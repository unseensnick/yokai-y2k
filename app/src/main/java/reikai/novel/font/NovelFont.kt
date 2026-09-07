package reikai.novel.font

/**
 * A font the user added, identified by its file name rather than a `content://` URI.
 *
 * The name is what the reader preference stores and what it is resolved against the fonts directory
 * with. A URI would go dangling after a restore onto a device that never granted access to that
 * folder, and nothing would revalidate it: the reader would quietly fall back to the default face.
 */
data class NovelFont(val fileName: String, val displayName: String)

/**
 * The formats Android's own font stack reads. Skia consumes SFNT, and WOFF is a compressed container
 * it does not unwrap, so a woff or woff2 file would work in the WebView renderer and silently not in
 * the native one. Both are refused at the door rather than saved and then hidden by the picker.
 */
private val ALLOWED_EXTENSIONS = listOf(".ttf", ".otf")

fun isSupportedFontFile(fileName: String): Boolean =
    ALLOWED_EXTENSIONS.any { fileName.endsWith(it, ignoreCase = true) }

/** Turns a file name into something readable, matching how the bundled faces are labelled. */
fun fontDisplayName(fileName: String): String =
    fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()

/**
 * The first usable face in a Google Fonts stylesheet, or null when it offers none.
 *
 * Google picks the format from the User-Agent, so an agent it reads as a modern browser is answered
 * entirely in woff2. Returning null there is the point: the download fails with a message instead of
 * writing a file the picker would then hide forever.
 */
fun firstSupportedFontUrl(css: String): String? =
    GSTATIC_URL.findAll(css)
        .map { it.groupValues[1] }
        .firstOrNull { isSupportedFontFile(it.substringBefore('?')) }

private val GSTATIC_URL = Regex("""url\((https://fonts\.gstatic\.com/[^)]+)\)""")

/**
 * Whether the first bytes are an SFNT wrapper, which is what Android can actually load. Checked
 * because a file picker only reports the name, and a mislabelled or truncated file otherwise reaches
 * the reader as a blank page rather than as an error at import time.
 */
fun isSfntHeader(header: ByteArray): Boolean {
    if (header.size < 4) return false
    val magic = header.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
    return magic == 0x00010000L || magic == 0x74727565L || magic == 0x4F54544FL || magic == 0x74746366L
}

/**
 * The three families every Android device has, stored as the CSS names the web renderers use
 * directly. Android's own alias table folds Arial into sans-serif and Georgia and Times New Roman
 * into serif, so those are not offered: they would be extra rows that draw the same three faces.
 */
val GENERIC_FONT_FAMILIES = listOf("sans-serif", "serif", "monospace")

fun isGenericFont(family: String): Boolean = family in GENERIC_FONT_FAMILIES
