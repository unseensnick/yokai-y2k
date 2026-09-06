package reikai.novel.content

/** Renderers must NOT re-run any preprocessing step on [text]. */
data class NovelChapterContent(
    val text: String,
    val isPlainText: Boolean,
    val chapterUrl: String?,
)
