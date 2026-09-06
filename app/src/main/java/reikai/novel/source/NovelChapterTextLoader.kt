package reikai.novel.source

import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.content.NovelContentConfig
import reikai.novel.content.NovelContentPipeline
import reikai.novel.content.NovelHtmlUtils
import reikai.novel.content.RenderTarget
import reikai.novel.install.LnPluginInstaller
import java.util.Collections

/**
 * Fetches a chapter's text and resolves the source it came from, for one reading session.
 *
 * Held per session rather than shared, because the source cache is keyed on novel id and a merged
 * session walks several novels: a session-scoped cache resolves each once and no more.
 */
class NovelChapterTextLoader(
    private val novelRepo: NovelRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val preferences: NovelPreferences,
    /** The downloaded copy, or null when the chapter is not on disk. */
    private val readDownloaded: (Novel, NovelChapter) -> String?,
) {

    private val pipeline = NovelContentPipeline(preferences)

    private val sourcesByNovel: MutableMap<Long, NovelSource> =
        Collections.synchronizedMap(HashMap())

    /** [LnPluginInstaller.ensureLoaded] needs to run once before the first source resolve. */
    @Volatile
    private var pluginsLoaded = false

    /** The source resolved for [novelId] so far, if a chapter of it has already loaded. */
    fun cachedSource(novelId: Long): NovelSource? = sourcesByNovel[novelId]

    /**
     * Downloaded chapter: read the self-contained HTML from disk (no source, null base URL, images
     * already inlined). Otherwise resolve the chapter's source and parse live, using the source site
     * as the base URL so relative image URLs resolve.
     *
     * What comes back is pipeline output, never raw source markup, so a renderer must not process it
     * again. [RenderTarget.WEB_VIEW] because both readers are WebViews today.
     */
    suspend fun load(chapter: NovelChapter): Pair<String, String?> {
        val (raw, baseUrl) = fetch(chapter)
        val config = NovelContentConfig.from(
            preferences = preferences,
            target = RenderTarget.WEB_VIEW,
            chapterUrl = chapter.url,
            chapterName = chapter.name,
        )
        val processed = pipeline.process(raw, config)
        // A plain-text chapter leaves the pipeline unescaped and unsanitised, because a text renderer
        // takes it verbatim. Both readers are HTML sinks, so it is escaped here or a `.txt` chapter's
        // markup becomes live document.
        val html = if (processed.isPlainText) {
            NovelHtmlUtils.plainTextToHtml(processed.text)
        } else {
            processed.text
        }
        return html to baseUrl
    }

    private suspend fun fetch(chapter: NovelChapter): Pair<String, String?> {
        val novel = novelRepo.getById(chapter.novelId)
        if (novel != null) readDownloaded(novel, chapter)?.let { return it to null }
        val src = resolveSource(chapter.novelId)
        return src.parseChapter(chapter.url) to src.site.ifBlank { null }
    }

    /**
     * Resolve (and cache) the source owning [forNovelId]. Each chapter in a merged session resolves
     * by its own `novelId`, so prev/next can cross source boundaries.
     */
    suspend fun resolveSource(forNovelId: Long): NovelSource {
        sourcesByNovel[forNovelId]?.let { return it }
        if (!pluginsLoaded) {
            runCatching { installer.ensureLoaded() }.onSuccess { pluginsLoaded = true }
        }
        val sourceId = novelRepo.getById(forNovelId)?.source ?: error("Novel not found")
        val resolved = sourceManager.get(sourceId) ?: error("Source not installed: $sourceId")
        sourcesByNovel[forNovelId] = resolved
        return resolved
    }
}
