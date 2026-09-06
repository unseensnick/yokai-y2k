package reikai.novel.source

import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
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
    /** The downloaded copy, or null when the chapter is not on disk. */
    private val readDownloaded: (Novel, NovelChapter) -> String?,
) {

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
     */
    suspend fun load(chapter: NovelChapter): Pair<String, String?> {
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
