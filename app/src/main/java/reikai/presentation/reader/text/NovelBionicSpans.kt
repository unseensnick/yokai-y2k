package reikai.presentation.reader.text

import android.graphics.Typeface
import android.text.Spannable
import android.text.style.StyleSpan

/**
 * Bionic reading: bold the opening of each word so the eye can skim on the emphasised stems.
 *
 * The word-length-to-bold-length table is the vendored `text-vide` bundle's own, so the native
 * renderer emphasises exactly what the WebView modes do. Its shape is a list of length boundaries:
 * a word bolds its length minus the index of the first boundary it fits in, which grows the bold
 * run as words get longer rather than taking a flat fraction.
 */
object NovelBionicSpans {

    private val fixationBoundaries = intArrayOf(0, 4, 12, 17, 24, 29, 35, 42, 48)

    /** Letters and digits containing at least one letter, matching the bundle's own word rule. */
    private val word = Regex("""(\p{L}|\p{Nd})*\p{L}(\p{L}|\p{Nd})*""")

    fun apply(text: Spannable) {
        word.findAll(text).forEach { match ->
            val bold = boldLengthFor(match.value.length)
            if (bold <= 0) return@forEach
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                match.range.first,
                match.range.first + bold,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun boldLengthFor(wordLength: Int): Int {
        val index = fixationBoundaries.indexOfFirst { wordLength <= it }
        val bold = if (index == -1) wordLength - fixationBoundaries.size else wordLength - index
        return bold.coerceAtLeast(0)
    }
}
