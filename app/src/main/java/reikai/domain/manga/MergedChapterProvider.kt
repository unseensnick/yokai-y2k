package reikai.domain.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ChapterMatchKeys
import reikai.domain.merge.MergedChapters
import reikai.domain.merge.crossSourceReadIds
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * One-shot merge group resolver for the manga reader: given the manga a chapter was opened from, it
 * resolves the whole group and returns the unified cross-source chapter list in reading order, plus
 * the group's manga keyed by id so the reader can build a per-source page loader. Reuses the same
 * [MangaMergeManager] math and [ChapterAggregation] stitcher the details screen uses, so the reader's
 * list matches what the user tapped, and returns exactly the single-source list when not merged.
 * [aggregate] is the shared aggregate plus reading-order policy, also called by the details flow.
 */
@Inject
@SingleIn(AppScope::class)
class MergedChapterProvider(
    private val getMangaWithChapters: GetMangaWithChapters,
    private val mergeManager: MangaMergeManager,
    private val sourceManager: SourceManager,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) {

    /** The resolved group: every member manga keyed by id, the unified reading-ordered chapters, and
     *  each member's source name for per-source labels (empty when not merged). */
    class Group(
        val mangaById: Map<Long, Manga>,
        val chapters: List<Chapter>,
        val sourceNameByMangaId: Map<Long, String>,
        /** Chapters unread on their own row but already read on another grouped source. */
        val readInOtherSources: Set<Long> = emptySet(),
    ) {
        val isMerged: Boolean get() = mangaById.size > 1
    }

    /**
     * Ids of [unified] chapters whose own row is unread but which another grouped source has already
     * read. The aggregation keeps one row per cross-source chapter and drops the rest, so without this
     * the list would show a chapter as unread purely because the copy that won happens to be the unread
     * one. Uses the same identity as the library's unread count, so the list and the badge agree. Empty
     * for an unmerged entry and for chapters with no cross-source identity.
     */
    suspend fun readInOtherSources(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long>,
        unified: List<Chapter>,
    ): Set<Long> {
        val galleryMangaIds = sourceIdByManga
            .filterValues { sourceId -> ChapterMatchKeys.isGallerySource(sourceId, sourceManager) }
            .keys
        return crossSourceReadIds(
            bySource = chaptersBySource,
            unified = unified,
            id = { it.id },
            read = { it.read },
            key = { ChapterMatchKeys.manga(it.chapterNumber, it.mangaId in galleryMangaIds) },
        )
    }

    suspend fun load(anchor: Manga): Group {
        val ids = mergeManager.computeRelatedIds(anchor.id)
        if (ids.size <= 1) {
            return Group(
                mangaById = mapOf(anchor.id to anchor),
                chapters = getMangaWithChapters.awaitChapters(anchor.id, applyScanlatorFilter = true),
                sourceNameByMangaId = emptyMap(),
            )
        }
        val mangaById = ids.associateWith { getMangaWithChapters.awaitManga(it) }
        val chaptersBySource = ids.associateWith { getMangaWithChapters.awaitChapters(it, applyScanlatorFilter = true) }
        val sourceIdByManga = mangaById.mapValues { it.value.source }
        val sourceNameByMangaId = mangaById.mapValues { sourceManager.getOrStub(it.value.source).name }
        val unified = aggregate(chaptersBySource, sourceIdByManga)
        return Group(
            mangaById = mangaById,
            chapters = unified,
            sourceNameByMangaId = sourceNameByMangaId,
            readInOtherSources = readInOtherSources(chaptersBySource, sourceIdByManga, unified),
        )
    }

    /** Aggregate + reading order: stitch the sources into one list, then restamp source order so a
     *  "by source order" sort reads top to bottom instead of interleaving sources. Suspend because it
     *  resolves the group's per-source override; both callers (reader load, details flow) already are. */
    suspend fun aggregate(chaptersBySource: Map<Long, List<Chapter>>, sourceIdByManga: Map<Long, Long>): List<Chapter> =
        restampReadingOrder(merge(chaptersBySource, sourceIdByManga).chapters)

    /** [aggregate] plus which merged chapter each source's chapter belongs to, for the callers that
     *  count the group rather than render it. */
    suspend fun merge(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long>,
    ): MergedChapters<Chapter> {
        // True gallery sources (E-Hentai / ExHentai / nhentai / Pururin / 8Muses / HentaiFox / AsmHentai)
        // treat each chapter as a whole standalone gallery numbered 1, so exempt them from cross-source
        // number dedup: merging two keeps both instead of collapsing on "1".
        val gallerySourceMangaIds = sourceIdByManga
            .filterValues { sourceId -> ChapterMatchKeys.isGallerySource(sourceId, sourceManager) }
            .keys
        // Members are the map keys, so any one resolves the group for its override ranking (empty = none).
        val memberRanking = chaptersBySource.keys.firstOrNull()
            ?.let { mergeManager.overrideRankingMemberIds(it) }
            .orEmpty()
        // The stitched order follows each source's own, so that order is stated here rather than left
        // to whatever the rows happen to come back in: the chapter query carries no ORDER BY.
        return ChapterAggregation.merge(
            chaptersBySource.mapValues { (_, chapters) -> chapters.sortedBy { it.sourceOrder } },
            sourceIdByManga,
            reikaiLibraryPreferences.preferredMangaSources.get(),
            gallerySourceMangaIds,
            memberRanking,
        )
    }

    /** The member manga ids in trunk order (first = trunk), for ordering the manage-sources rows so the
     *  primary sits on top. Uses the same ranking as [aggregate]; [memberRanking] is the caller's
     *  already-resolved per-group override (empty = the global ranking wins). */
    fun rankedMemberIds(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long>,
        memberRanking: List<Long>,
    ): List<Long> = ChapterAggregation.rankedMemberIds(
        chaptersBySource,
        sourceIdByManga,
        reikaiLibraryPreferences.preferredMangaSources.get(),
        memberRanking,
    )

    /**
     * The list arrives in reading order from the aggregation and keeps it. Sorting by chapter number
     * here was the interleave: a number is whatever its own source counted, and two sources of one
     * series routinely disagree, so the pooled numbers are on different scales.
     */
    private fun restampReadingOrder(chapters: List<Chapter>): List<Chapter> =
        chapters.mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }

    /**
     * Re-add the [opened] chapter when the cross-source dedup dropped it. Restamped, because the
     * re-added row carries its own source's `sourceOrder` while the unified list was renumbered onto a
     * single 0..N-1 scale, and the reader sorts on `sourceOrder` alone: two scales under one
     * comparator drop it at an arbitrary index, breaking prev/next and leaving the reader describing a
     * different chapter than it shows. Returns [unified] untouched when there is nothing to add, so a
     * single-source list is never renumbered over its own source's ordering.
     */
    fun withOpenedChapter(unified: List<Chapter>, opened: Chapter?): List<Chapter> = when {
        opened == null || unified.any { it.id == opened.id } -> unified
        else -> {
            // Placed by number rather than appended: the list is already in reading order, and this
            // row is the only one whose position is not decided. Its own number is the best guess,
            // since the source it came from is not being stitched here.
            val at = unified.indexOfFirst { it.chapterNumber < opened.chapterNumber }
                .takeIf { it >= 0 } ?: unified.size
            restampReadingOrder(unified.toMutableList().apply { add(at, opened) })
        }
    }
}
