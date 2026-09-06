package reikai.presentation.reader.text

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.core.view.setPadding
import logcat.LogPriority
import reikai.presentation.novel.reader.NovelReaderSettings
import tachiyomi.core.common.util.system.logcat

/**
 * Applies the reader's display settings to a chunk view.
 *
 * Net-new rather than ported: tsundoku's renderer styles nothing, and the equivalent lives in the
 * host viewer we deliberately did not take, reading its own preferences directly. This reads the
 * settings the session already resolved, so the native renderer and the WebView answer to one
 * source. The bundled faces are the same nine files `core.js` loaded over `file:///android_asset`.
 */
object NovelTextStyle {

    private val typefaceCache = HashMap<String, Typeface?>()

    fun apply(view: TextView, settings: NovelReaderSettings, context: Context) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        view.typeface = typefaceFor(context, settings.fontFamily)
        applyLineSpacing(view, settings.lineHeight)
        view.setPadding((settings.padding * context.resources.displayMetrics.density).toInt())
        view.setTextColor(parseColor(settings.textColor, Color.BLACK))
        applyAlignment(view, settings.textAlign)
    }

    /**
     * The setting is a multiplier, but it is applied here as the equivalent number of pixels.
     *
     * A multiplier scales every line by its own height, and a line holding an image is as tall as the
     * image, so a full-width picture gained half its height again in blank space above it. The same
     * spacing expressed as a fixed amount leaves text looking identical and leaves images alone.
     * Requires the size and typeface to be set first, since it measures them.
     */
    private fun applyLineSpacing(view: TextView, multiplier: Float) {
        val metrics = view.paint.fontMetricsInt
        val textLineHeight = (metrics.bottom - metrics.top).toFloat()
        val extra = ((multiplier - 1f) * textLineHeight).coerceAtLeast(0f)
        view.setLineSpacing(extra, 1f)
    }

    /** Justification is a paragraph property the framework only honours from API 26, our minimum. */
    private fun applyAlignment(view: TextView, align: String) {
        view.justificationMode = if (align == "justify") {
            Layout.JUSTIFICATION_MODE_INTER_WORD
        } else {
            Layout.JUSTIFICATION_MODE_NONE
        }
        view.gravity = when (align) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right" -> Gravity.END
            else -> Gravity.START
        }
    }

    fun parseColor(value: String, fallback: Int): Int = try {
        Color.parseColor(value)
    } catch (e: IllegalArgumentException) {
        logcat(LogPriority.WARN, e) { "Unparseable reader colour: $value" }
        fallback
    }

    /** Cached because a chapter builds one view per 6000 characters and each would re-read the file. */
    private fun typefaceFor(context: Context, family: String): Typeface {
        if (family.isBlank()) return Typeface.DEFAULT
        val cached = typefaceCache.getOrPut(family) {
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$family.ttf") }
                .onFailure { logcat(LogPriority.WARN, it) { "Missing reader font asset: $family" } }
                .getOrNull()
        }
        return cached ?: Typeface.DEFAULT
    }
}
