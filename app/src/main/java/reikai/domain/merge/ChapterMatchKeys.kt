package reikai.domain.merge

import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.MANGADEX_IDS
import exh.source.getMainSource
import tachiyomi.domain.source.service.SourceManager

/**
 * The cross-source identity of a manga chapter: two chapters from different sources share a key when
 * they are the same chapter. Used for carrying read state across a merge group; what a merged entry
 * SHOWS and COUNTS comes from the stitch itself (see `merged_chapter_unit.sq`), which knows the group
 * and can place a chapter no key identifies. The novel twin is `NovelChapterAggregation.matchKey`.
 */
object ChapterMatchKeys {

    /**
     * Whether a source's chapters are each a standalone work rather than an instalment.
     *
     * True gallery sources all implement [NamespaceSource], but so does the enhanced MangaDex, which
     * has normal sequential chapters that must dedup like any other source, so it is excluded by id.
     * There is no clean positive id-set for every gallery, since installed extensions vary.
     */
    suspend fun isGallerySource(sourceId: Long, sourceManager: SourceManager): Boolean =
        sourceId !in MANGADEX_IDS && sourceManager.get(sourceId)?.getMainSource<NamespaceSource>() != null
}
