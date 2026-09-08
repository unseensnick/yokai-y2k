# Merged read state (one definition across a merge group)

## Goal

Make a chapter count as **read, bookmarked or downloaded when any member source of a merge group says so**, and make every surface agree on that. Today the answer changes depending on which screen asks, so a merged series can show unread chapters it does not have, resume into a chapter already read on another source, and offer to re-download a file that is already on disk.

## Why

A merge group presents several sources as one series, but per-chapter state stays per-source row. Both chapter aggregators keep exactly one row per cross-source key and discard the losing duplicates, so the surviving row's `read` / `bookmark` / `downloaded` fields become the display state and the other sources' copies become invisible. Nothing merges them.

That produces contradictions the user meets directly:

- The library unread badge is the collapse representative's own count, deliberately not a sum (summing double-counts shared chapters), so it under-reports the group.
- The details "All" list shows a chapter as unread when it was read on a source that lost the dedup.
- Continue-reading, download-next, reader next-chapter and history resume all compute "next unread" from a per-manga query that never sees the group, so they can serve a chapter already read elsewhere.
- The library badge already sums downloads across members while the chapter row reads only the winning copy, so the badge and the list disagree today, before any of this changes.

Fixing only the visible surfaces would make it worse, not better: a badge reading zero unread while continue-reading still opens a read chapter is a sharper contradiction than the current consistent under-reporting.

## Approach

**One shared "any member" resolution, computed where the losers still exist.** Both aggregators already hold every member's chapters at the moment they dedup; that is the only point where the discarded copies are still in hand. Resolve the merged state there and carry it forward.

**Carry it on the UI item, never on the domain model.** `Chapter` is the shared domain and DB model, and several consumers depend on it being DB truth (mark-as-unread, tracker sync, delete-after-read, backup). Synthesising `read = true` onto it would leak into all of them. The merged flags belong on the per-screen chapter item the details list already builds.

**Reuse each content type's existing match key, once.** Manga matches on the chapter number narrowed to `Float`; novels match on a normalized title string, falling back to the number. Those keys are already written and tested. The shared helper lives beside the aggregators and takes the key function as its input, so manga and novel cannot drift into two definitions of "the same chapter".

**Teach the next-unread family about groups.** The details resume button already runs over the aggregated list and agrees for free. The other four (library continue-reading, library download-next, reader next-chapter plus its download-next, history resume) each call a per-manga interactor and need the group resolved first.

**Group-aware download means the deduplicated list, never a fan-out.** Downloading every member would fetch each chapter once per source and waste the storage on near-duplicates, so the target is one row per canonical chapter, downloaded from its own source. The details screen already does exactly this, acting on the visible list and grouping by each chapter's `mangaId`. Library download-next inherits the same treatment once this work gives the library a group-aware next-unread over the canonical set; until then it stays on the collapsed primary.

**The unread count moves as a set.** The badge, the unread filter predicate and the unread sort all read the same number. They change together or the library contradicts itself.

**The count is cached as a mapping, never as a count.** Read status is not an input to either aggregator, so reading can never change which chapters are duplicates, but it changes the count constantly. Persist a canonical-chapter set per merge group (a new table beside `merge_group_manga`, with a novel twin), rebuilt only on chapter-list changes, membership changes, source-order override changes, preferred-source changes and excluded-scanlator changes. The count is then a live SQL join over that set and the `read` column, the same cost class as the aggregates `libraryView` already computes.

**Scope:** read, bookmark and downloaded state, in one pass, since all three come from the identical discard and splitting them would mean touching the same code three times.

## Key files

- Aggregators and the match keys: `reikai/domain/manga/ChapterAggregation.kt` (`Float` chapter number), `reikai/domain/novel/NovelChapterAggregation.kt` (`matchKey`, normalized title). The shared helper lands beside them.
- Aggregation entry point: `reikai/domain/manga/MergedChapterProvider.kt`; the novel side calls its aggregator directly from `NovelDetailsViewModel`.
- Details list item to widen: the chapter item model in `MangaViewModel`, and the novel twin on `EntryDetailsScreenState`.
- Next-unread callers: `LibraryViewModel` (`getNextUnreadChapter`, `downloadNextChapters`), `ReaderViewModel` (`GetNextChapters`), `HistoryViewModel`.
- Unread count set: the badge in `MangaMergeCollapse` / `NovelMergeCollapse`, the filter predicate and the sort in both library models.
- Persistence: `data/src/main/sqldelight/tachiyomi/data/merge_group.sq` plus a new `.sqm`.

## Status

**Library and details halves shipped; reader-adjacent remainder open.** Pulled forward ahead of the rest of the library surface at the owner's request, so it landed before the shared pipeline rather than after.

Shipped and on-device verified: the `chapter_match_key` schema and migration 31 (`004077165`), the reconciliation that computes and maintains the identities plus the shared key rules (`8a88a6109`), the update-job triggers and the `185f` backfill migration with `versionCode` 185 (`cb37e887e`), the deduplicated count wired into both library models with the novel collapse-before-filter reorder (`7fba8e5f2`), the details "All" list read flag (`116f74ca5`), source chips reporting group read state (`e45bdf483`), the reader chapter sheet keeping read chapters and back-navigation reaching them (`508bf14ff`), group-wide read/bookmark from the sheet (`82aebf3cb`), and the details `expandToGroup` matching on the same Float key (`d1f0dafb8`).

**The two surfaces disagree about what a merged selection means (recorded 2026-08-12).** A bulk action in the library expands a selected merged row to every source behind it, so marking one row read marks the whole group; the recents surface does the opposite, because its collapse drops the other members' rows outright, leaving the verbs to act on the one chapter the surviving row names. Both are defensible where they stand (the library selects series, recents selects chapters), and closing the gap means giving recents the cross-source chapter identity the library already uses, which is this record's remaining work rather than a defect of either surface.

**Still open:** reader next-chapter and history resume are still per-manga; library download-next is still the collapsed primary rather than the group's deduplicated list; bookmark and downloaded state are still whichever copy won the dedup; and whether the Updates feed should hide a sibling's copy of an already-read chapter is undecided.

**Known gap in the shipped part:** neither `expandToGroup` nor the reader's `groupCopyIds` excludes gallery sources, which number every standalone work as chapter 1. Merging a gallery source into a normal group can therefore spread a read mark to an unrelated gallery. The library's count already handles this through `ChapterMatchKeys.isGallerySource`; wiring the same into those two needs per-sibling source resolution and a `SourceManager`.

**Cross-source ordering measured and rebuilt (2026-09-08).** A grouped entry's chapter list was ordered by `chapterNumber`, which is not comparable across sources: one site counts a prologue, another reports its own page position. Measured on a two-source novel group, all 532 rows of one source satisfied `chapter_number = source_order + 1`, because its plugin assigns the site's `n_sort`; the other source's titles carry `siteIndex - realNumber` and the recognizer took the leading token. The two ran three apart and the list alternated between sources. **The order is now built while the sources are stitched**, in `MergedChapterOrder`, one kernel both `ChapterAggregation` and `NovelChapterAggregation` call: a chapter an earlier source placed moves a cursor to it, a new one lands just after. Komikku's `dedupeByPriority` shape (`GetMergedChaptersByMangaId`), taken over Reikai's title-first `matchKey` rather than their raw number equality. The stitched position is stamped onto `sourceOrder`, and both `restampReadingOrder` helpers stop re-sorting by number.

**The stored number is deliberately left as the source reported it.** It is not a chapter number by contract: LNReader's plugin type documents it not at all (`src/types/plugin.ts`, an empty description cell in `docs/docs.md`), its own worked example assigns `i + 1`, index-derived assignments dominate the 86 of 146 plugins that set it, and neither LNReader (orders by `position`, offering no number option at all) nor tsundoku (defaults to source order) orders by it. Nobody reconciles two sources' numbering: Komikku and TachiyomiSY have no offset handling and no per-source adjustment column, and their escape hatch is picking one source wholesale. **The reader's duplicate-read marking moved onto `matchKey` too**, since comparing numbers marked a chapter several along on the sibling source; that setting is on by default here, so some read flags predating this are wrong and cannot be told apart from a real read. **Two consequences.** Sorting "By source" now really follows the source's own listing, where it had quietly sorted by number even for a single source. And a chapter whose title carries no words (`Chapter 524 - 518` against `Chapter 518`) still appears once per source, because with no title text and numbers on different scales there is no identity to match on.

## Decisions & tradeoffs

- **Read means read on any source.** The alternative, matching the aggregated list exactly, reproduces a quirk rather than fixing it: reading a chapter on a source that loses the dedup leaves it displayed as unread. One definition everywhere is the point.
- **Both tiers ship together.** The display layer (All list, badge, filter, sort) and the next-unread family (continue-reading, download-next, reader next-chapter, history resume) are one piece of work. Shipping only the display layer leaves five surfaces reasoning off the old definition and makes the inconsistency more visible, not less.
- **Bookmark and downloaded state ride along.** All three flags are lost by the same discard, in the same function. Doing them separately means three passes over the same code, and the downloaded case is already inconsistent with the library badge today.
- **Merged flags live on the UI item, not on `Chapter`.** Keeps DB truth intact for tracker sync, delete-after-read and backup.
- **Cache the dedup mapping, not the count.** A cached count is stale the moment the user finishes a chapter, which is exactly when they look at the badge. The mapping is invariant under reads.
- **No pure-SQL count without the cache.** It is expressible for manga, whose key is a number, but not for novels, whose key is a Kotlin-normalized title. Taking it would re-split the two content types, the failure the content-layer program exists to prevent.
- **Recomputing the aggregation per library emission is rejected.** It loads full chapter rows per member, so a library with a few dozen merged groups adds on the order of a hundred queries per emission, and emissions fire on every preference change and DB invalidation.
- **`markDuplicateReadChapterAsRead` stays.** Two independent options, both off by default, writing real DB state that display-time inference cannot replace: tracker counts, delete-after-read, per-source history, backup fidelity. It becomes partially redundant for display only.
- **Deliberately out of scope:** per-chapter page progress (a merged chapter shows the winning row's progress, and any-member-read does not solve partial progress), and whether the Updates feed should hide a sibling's copy of an already-read chapter. Both are real, both are separable.
