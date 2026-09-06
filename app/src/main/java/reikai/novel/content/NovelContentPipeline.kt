package reikai.novel.content

import androidx.annotation.WorkerThread
import reikai.domain.novel.NovelPreferences

/**
 * Stage order is user-visible and fixed: strip title, normalize, regex replacements, lowercase,
 * auto-split, translate, sanitize. Each stage sees what the previous one produced (a regex rule
 * matches post-normalization markup, auto-split counts words after those rules ran), so reordering
 * changes the rendered output for some chapters.
 */
class NovelContentPipeline(private val preferences: NovelPreferences) {

    suspend fun process(
        raw: String,
        config: NovelContentConfig,
        translator: (suspend (String) -> String)? = null,
    ): NovelChapterContent = finalize(preTranslate(raw, config), config, translator)

    @WorkerThread
    fun preTranslate(raw: String, config: NovelContentConfig): PreTranslated {
        var content = raw
        val plainTextMode = NovelHtmlUtils.isPlainTextChapter(config.chapterUrl)

        if (config.hideTitle) {
            content = NovelHtmlUtils.stripChapterTitle(content, config.chapterName)
        }

        content = if (plainTextMode) {
            NovelHtmlUtils.normalizePlainTextContent(content)
        } else {
            NovelHtmlUtils.normalizeContentForHtml(content, config.chapterUrl)
        }

        content = NovelRegexReplacements.apply(content, preferences)

        if (config.forceLowercase) content = content.lowercase()

        if (preferences.readerAutoSplitText().get()) {
            content = NovelTextSplitter.splitText(
                text = content,
                wordCount = preferences.readerAutoSplitWordCount().get(),
                isHtml = !plainTextMode,
            )
        }

        return PreTranslated(content, plainTextMode)
    }

    suspend fun finalize(
        pre: PreTranslated,
        config: NovelContentConfig,
        translator: (suspend (String) -> String)? = null,
    ): NovelChapterContent {
        var content = pre.text
        if (translator != null) content = translator(content)

        if (!pre.isPlainText) {
            content = NovelHtmlUtils.sanitizeForRender(
                content,
                target = config.target,
                keepEmbeddedCss = config.keepEmbeddedCss,
                keepEmbeddedJs = config.keepEmbeddedJs,
                blockMedia = config.blockMedia,
            )
        }

        return NovelChapterContent(
            text = content,
            isPlainText = pre.isPlainText,
            chapterUrl = config.chapterUrl,
        )
    }

    data class PreTranslated(
        val text: String,
        val isPlainText: Boolean,
    )
}
