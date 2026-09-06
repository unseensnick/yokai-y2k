package reikai.novel.content

/**
 * Inserts paragraph breaks into chapters that arrive as one unbroken wall of text, which some
 * sources do. Breaks land on sentence-ending punctuation, never mid-sentence.
 */
object NovelTextSplitter {

    private val sentenceEndingPunctuation = setOf('.', '!', '?', '。', '！', '？', '…')

    /**
     * Splits text by inserting paragraph breaks after approximately [wordCount] words, but always
     * continuing until a sentence-ending punctuation mark is found.
     *
     * @param text The input text (can be HTML or plain text)
     * @param wordCount Target number of words before looking for punctuation
     * @param isHtml Whether [text] is HTML markup, per the caller's own classification
     * @return Text with additional paragraph breaks inserted
     */
    fun splitText(text: String, wordCount: Int, isHtml: Boolean): String {
        if (wordCount <= 0) return text
        val effectiveWordCount = wordCount.coerceAtLeast(20)

        return if (isHtml) {
            splitHtmlText(text, effectiveWordCount)
        } else {
            splitPlainText(text, effectiveWordCount)
        }
    }

    private fun splitPlainText(text: String, targetWordCount: Int): String {
        val result = StringBuilder()
        val words = text.split(Regex("\\s+"))
        var wordsSincePunctuation = 0

        for (i in words.indices) {
            val word = words[i]
            if (word.isEmpty()) continue

            result.append(word)
            wordsSincePunctuation++

            val endsWithPunctuation = word.lastOrNull()?.let { it in sentenceEndingPunctuation } == true

            if (endsWithPunctuation && wordsSincePunctuation >= targetWordCount) {
                result.append("\n\n")
                wordsSincePunctuation = 0
            } else {
                result.append(" ")
            }
        }

        return result.toString().trim()
    }

    private fun splitHtmlText(html: String, targetWordCount: Int): String {
        val result = StringBuilder()
        var wordsSincePunctuation = 0

        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val tagEnd = html.indexOf('>', i)
                if (tagEnd == -1) {
                    result.append(html.substring(i))
                    break
                }
                val tag = html.substring(i, tagEnd + 1)
                result.append(tag)

                // A tag that already breaks the line restarts the count, so an existing paragraph
                // never gets a break inserted right after it.
                if (tag.lowercase().startsWith("<p>") ||
                    tag.lowercase().startsWith("<br") ||
                    tag.lowercase().startsWith("</p>") ||
                    tag.lowercase().startsWith("<div") ||
                    tag.lowercase().startsWith("</div") ||
                    tag.lowercase().startsWith("<body") ||
                    tag.lowercase().startsWith("</body")
                ) {
                    wordsSincePunctuation = 0
                }
                i = tagEnd + 1
            } else {
                val nextTag = html.indexOf('<', i)
                val textEnd = if (nextTag == -1) html.length else nextTag
                val text = html.substring(i, textEnd)

                // Walk character by character to preserve the original whitespace exactly.
                var ti = 0
                while (ti < text.length) {
                    val wsStart = ti
                    while (ti < text.length && text[ti].isWhitespace()) ti++
                    if (ti > wsStart) result.append(text, wsStart, ti)
                    if (ti >= text.length) break

                    val wordStart = ti
                    while (ti < text.length && !text[ti].isWhitespace()) ti++
                    val word = text.substring(wordStart, ti)

                    result.append(word)
                    wordsSincePunctuation++

                    val endsWithPunctuation = word.lastOrNull()?.let { it in sentenceEndingPunctuation } == true
                    if (endsWithPunctuation && wordsSincePunctuation >= targetWordCount) {
                        // Line breaks rather than paragraph tags, so the split stays valid inside
                        // div-based chapters and body-level plain HTML.
                        result.append("<br><br>")
                        wordsSincePunctuation = 0
                    }
                }
                i = textEnd
            }
        }

        return result.toString()
    }
}
