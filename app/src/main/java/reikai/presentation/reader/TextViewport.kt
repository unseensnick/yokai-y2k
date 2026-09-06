package reikai.presentation.reader

import reikai.presentation.novel.reader.NovelReaderSettings

/**
 * What a viewport answers when it renders text rather than images, so the host can drive any text
 * renderer: today the WebView, next the native one. Kept off [ReaderViewport] so an image viewer is
 * never made to declare a contract it has no answer for.
 *
 * How a chapter becomes pixels is the implementation's business, which is why [load] takes the
 * chapter the model produced rather than a document built for one renderer.
 */
interface TextViewport {

    /**
     * Renders [chapter]. The neighbour flags are the session's, not the chapter's, so a renderer that
     * offers its own way forward can show it without asking the model.
     *
     * Suspending because building the document is proportional to the chapter, and a downloaded one
     * carries its images inline. Implementations do that work off the main thread.
     */
    suspend fun load(
        chapter: NovelReaderViewModel.LoadedChapter,
        hasPrevious: Boolean,
        hasNext: Boolean,
        settings: NovelReaderSettings,
    )

    /**
     * Applies changed display settings to what is already rendered, so a size or colour change lands
     * in place rather than waiting for the next chapter.
     */
    fun applySettings(settings: NovelReaderSettings)
}
