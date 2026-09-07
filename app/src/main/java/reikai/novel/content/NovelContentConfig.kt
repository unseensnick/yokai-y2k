package reikai.novel.content

import reikai.domain.novel.NovelPreferences

/**
 * [TEXT_VIEW]: TextView renders scripts/styles as visible text, so they are always stripped.
 * [WEB_VIEW]: embedded CSS/JS can be preserved per user preferences.
 */
enum class RenderTarget { TEXT_VIEW, WEB_VIEW }

data class NovelContentConfig(
    val chapterUrl: String?,
    val chapterName: String,
    val target: RenderTarget,
    val hideTitle: Boolean = false,
    val forceLowercase: Boolean = false,
    val blockMedia: Boolean = false,
    val removeExtraSpacing: Boolean = false,
    val keepEmbeddedCss: Boolean = true,
    val keepEmbeddedJs: Boolean = false,
) {
    companion object {
        fun from(
            preferences: NovelPreferences,
            target: RenderTarget,
            chapterUrl: String?,
            chapterName: String,
        ): NovelContentConfig = NovelContentConfig(
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            target = target,
            hideTitle = preferences.readerHideChapterTitle().get(),
            forceLowercase = preferences.readerForceLowercase().get(),
            blockMedia = preferences.readerBlockMedia().get(),
            removeExtraSpacing = preferences.readerRemoveExtraSpacing().get(),
            keepEmbeddedCss = preferences.readerKeepEmbeddedCss().get(),
            keepEmbeddedJs = preferences.readerKeepEmbeddedJs().get(),
        )
    }
}
