package reikai.presentation.reader

import android.content.Context
import android.util.TypedValue
import reikai.presentation.novel.reader.ReaderThemeColors
import com.google.android.material.R as MaterialR

/**
 * The reader's Material colours read off a themed [Context] rather than out of Compose, so a
 * View-hosted reader can build its document without one.
 *
 * **Resolve from the Activity, never from `createReaderThemeContext`.** Each app theme is its own XML
 * style applied to the Activity, so the Activity carries the user's choice; that wrapper re-applies
 * the base theme for the manga reader and would flatten every app theme to the default.
 */
fun Context.resolveReaderThemeColors(): ReaderThemeColors {
    val surface = colorAttr(MaterialR.attr.colorSurface)
    return ReaderThemeColors(
        primary = cssHex(colorAttr(MaterialR.attr.colorPrimary)),
        onPrimary = cssHex(colorAttr(MaterialR.attr.colorOnPrimary)),
        secondary = cssHex(colorAttr(MaterialR.attr.colorSecondary)),
        onSecondary = cssHex(colorAttr(MaterialR.attr.colorOnSecondary)),
        tertiary = cssHex(colorAttr(MaterialR.attr.colorTertiary)),
        onTertiary = cssHex(colorAttr(MaterialR.attr.colorOnTertiary)),
        surface = cssHex(surface),
        // Kept translucent rather than flattened to hex: it backs a scrim the chapter shows through.
        surface09 = cssRgba(surface, alpha = 0.9f),
        onSurface = cssHex(colorAttr(MaterialR.attr.colorOnSurface)),
        surfaceVariant = cssHex(colorAttr(MaterialR.attr.colorSurfaceVariant)),
        onSurfaceVariant = cssHex(colorAttr(MaterialR.attr.colorOnSurfaceVariant)),
        outline = cssHex(colorAttr(MaterialR.attr.colorOutline)),
        rippleColor = cssHex(colorAttr(MaterialR.attr.colorOnSurface)),
    )
}

/** Falls back to opaque black, which is only reachable if the theme is missing a Material attribute. */
private fun Context.colorAttr(attr: Int): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(attr, value, true)) value.data else 0xFF000000.toInt()
}

private fun cssHex(color: Int): String = "#%06X".format(0xFFFFFF and color)

private fun cssRgba(color: Int, alpha: Float): String {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return "rgba($r, $g, $b, $alpha)"
}
