package reikai.presentation.novel.reader

import reikai.presentation.reader.readerDarkPreset

/*
 * Preference values on their way into the reader document's <style> block. Restoring a backup writes
 * almost every preference key, so a shared file decides these strings, and one carrying a closing
 * style tag would end the block and start whatever followed it in a page that runs JavaScript with
 * the app's cookies. Each is checked against the shape it can legitimately have rather than escaped,
 * because CSS escaping is per-context while these shapes are small enough to state exactly.
 */

/** `#rgb` through `#rrggbbaa`, which is every form the presets and the colour picker write. */
private val cssColorPattern = Regex("^#[0-9a-fA-F]{3,8}$")

private val cssTextAlignments = setOf("left", "center", "right", "justify")

fun cssColorOrDefault(value: String, fallback: String): String =
    if (cssColorPattern.matches(value)) value else fallback

fun cssBackgroundColor(value: String): String = cssColorOrDefault(value, readerDarkPreset.background)

fun cssTextColor(value: String): String = cssColorOrDefault(value, readerDarkPreset.textColor)

fun cssTextAlign(value: String): String = if (value in cssTextAlignments) value else "left"

/**
 * A family name as CSS can read it. Kept unquoted the way the stylesheet expects, so the characters
 * that would end the declaration are the ones dropped; an empty result is the reader's own default
 * face, which is what an unset preference already means.
 */
fun cssFontFamily(value: String): String =
    value.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()

/**
 * Whether a path can go inside a quoted `url('...')`. The font mirror's path carries a file name the
 * user chose, and a quote or a backslash in it would end the token early. A space is fine, since the
 * quotes are what it is inside.
 */
fun isSafeInCssUrl(url: String): Boolean =
    url.none { it == '\'' || it == '"' || it == '\\' || it == '\n' || it == '\r' }
