package reikai.domain.novel.tts

import java.text.BreakIterator
import java.util.Locale

/**
 * Breaks a paragraph into pieces a speech engine will accept. Android refuses an utterance longer
 * than its own maximum outright, so a paragraph past it has to arrive as several.
 *
 * Built on [BreakIterator] rather than on a punctuation list: its sentence instance is locale
 * correct, and its line instance segments Chinese, Japanese, Thai and Khmer by dictionary, which is
 * the text that has no spaces to break at. The cap is never exceeded, whatever the text.
 */
object TtsUtteranceSplitter {

    fun split(text: String, maxLength: Int, locale: Locale): List<String> {
        val trimmed = text.trim()
        if (maxLength <= 0 || trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxLength) return listOf(trimmed)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val chunk = current.toString().trim()
            if (chunk.isNotEmpty()) chunks.add(chunk)
            current.setLength(0)
        }

        // Appends what fits and starts a new chunk with what does not. A piece bigger than the cap on
        // its own is one unbreakable run, which only the cut below can shorten.
        fun append(piece: String) {
            if (current.isNotEmpty() && current.length + piece.length > maxLength) flush()
            if (piece.length <= maxLength) {
                current.append(piece)
            } else {
                flush()
                chunks.addAll(cut(piece, maxLength))
            }
        }

        for (sentence in segments(BreakIterator.getSentenceInstance(locale), trimmed)) {
            if (sentence.length <= maxLength) {
                append(sentence)
            } else {
                // Only now, because a sentence that fits should stay whole even where it could break.
                flush()
                segments(BreakIterator.getLineInstance(locale), sentence).forEach(::append)
            }
        }
        flush()
        return chunks
    }

    private fun segments(iterator: BreakIterator, text: String): List<String> {
        iterator.setText(text)
        val out = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return out
    }

    /** The last resort, for a run with no break opportunity in it at all. Steps back off a leading
     *  surrogate so a cut cannot land inside a character and speak as a replacement glyph. */
    private fun cut(run: String, maxLength: Int): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < run.length) {
            var end = minOf(start + maxLength, run.length)
            if (end < run.length && Character.isHighSurrogate(run[end - 1])) end--
            out.add(run.substring(start, end))
            start = end
        }
        return out
    }
}
