package reikai.domain.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.merge.renderStoredStitch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * One-shot merge group resolver for manga: given an entry, it resolves the whole group and returns the
 * cross-source chapter list in reading order, plus the group's manga keyed by id so the reader can
 * build a per-source page loader. The list is read from the STORED stitch, never stitched here, so the
 * reader, the details screen and the library badge cannot reach different answers. Returns exactly the
 * single-source list when not merged.
 */
@Inject
@SingleIn(AppScope::class)
class MergedChapterProvider(
    private val getMangaWithChapters: GetMangaWithChapters,
    private val mergeManager: MangaMergeManager,
    private val sourceManager: SourceManager,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val units: MergedChapterUnitRepository,
    private val reconcile: ReconcileMergedChapters,
) {

    /** The resolved group: every member manga keyed by id, the unified reading-ordered chapters, and
     *  each member's source name for per-source labels (empty when not merged). */
    class Group(
        val mangaById: Map<Long, Manga>,
        val chapters: List<Chapter>,
        val sourceNameByMangaId: Map<Long, String>,
        /** Chapters unread on their own row but already read on another grouped source. */
        val readInOtherSources: Set<Long> = emptySet(),
        /** The stored stitch behind [chapters], so a reader acting on a row can reach the group's
         *  other copies of it without matching numbers the sources disagree on. */
        val stitch: List<ChapterUnit> = emptyList(),
        /** Every member's chapters, which is where the copies [chapters] stands in for live. A caller
         *  asking a cross-source question (bookmarked anywhere, on disk anywhere) needs them. */
        val pooledChapters: List<Chapter> = chapters,
    ) {
        val isMerged: Boolean get() = mangaById.size > 1
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
        val sourceNameByMangaId = mangaById.mapValues { sourceManager.getOrStub(it.value.source).name }
        val pooled = chaptersBySource.values.flatten()
        val stitch = stitchOf(anchor.id)
        val merged = merged(pooled, stitch)
        return Group(
            mangaById = mangaById,
            chapters = merged,
            sourceNameByMangaId = sourceNameByMangaId,
            readInOtherSources = flaggedOnAnotherSource(pooled, merged, stitch, { it.id }, { it.read }),
            stitch = stitch,
            pooledChapters = pooled,
        )
    }

    /**
     * The group's stored stitch, rebuilt first when it is stale. Empty when the entry is not in a
     * group, which [merged] reads as nothing to merge. Reading it rather than stitching here is what
     * keeps a screen from producing a second answer to the question the library badge asks of the
     * same rows.
     */
    suspend fun stitchOf(anchorId: Long): List<ChapterUnit> {
        val groupId = mergeManager.groupIdOf(anchorId) ?: return emptyList()
        reconcile.awaitGroup(ContentType.MANGA, groupId)
        return units.getStitch(ContentType.MANGA, groupId)
    }

    /** [chapters] as the merged reading order [stitch] describes, source order restamped onto it. */
    fun merged(chapters: List<Chapter>, stitch: List<ChapterUnit>): List<Chapter> =
        renderStoredStitch(chapters, stitch) { it.id }
            .let { if (stitch.isEmpty()) it else restampReadingOrder(it) }

    /** The member manga ids in trunk order (first = trunk), for ordering the manage-sources rows so the
     *  primary sits on top. Uses the stitch's own ranking; [memberRanking] is the caller's
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
