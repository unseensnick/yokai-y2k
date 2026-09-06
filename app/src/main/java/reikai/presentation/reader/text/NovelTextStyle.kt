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
        // The web layer's lineHeight is a multiplier, which is exactly what setLineSpacing takes.
        view.setLineSpacing(0f, settings.lineHeight)
        view.setPadding((settings.padding * context.resources.displayMetrics.density).toInt())
        view.setTextColor(parseColor(settings.textColor, Color.BLACK))
        view.typeface = typefaceFor(context, settings.fontFamily)
        applyAlignment(view, settings.textAlign)
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
