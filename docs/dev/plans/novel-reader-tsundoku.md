# Novel reader: tsundoku as the foundation

> **The migration plan moved 2026-09-03 to [content-layer-reader-surface.md](content-layer-reader-surface.md).** The scout this doc asked for ran, and it changed the shape: novels do not fold into `ReaderActivity` the way tsundoku does, because that design rests on novels being manga rows (`Page.text`, `fetchPageText` on `Source`), which Reikai rejected at the data layer. What survives here is the evaluation and the case for tsundoku as the engine source; what is superseded is the "Option 3, folded into `ReaderActivity`" framing and the Option 1 comparison. The port is scoped to the portable layers under both of their novel viewers, since their `NovelViewer` calls twenty-one distinct members of their own forked `ReaderActivity` and their host carries roughly fifty `is NovelViewer || is NovelWebViewViewer` type checks. **Re-grounded 2026-09-03:** the port is not renderer-only. It takes the native renderer, the WebView mode's Kotlin stylers and their four small JS assets, and the `shared/` content pipeline, because Reikai ships both rendering modes and because the pipeline is a separate lift their own contract forbids the renderer from duplicating. Of the three feature-harvest items below, the reader-extras one narrowed: the content pipeline, custom fonts, the four margins, paragraph indent and paragraph spacing moved into the takeover, leaving the status bar, bottom-bar editor, edit mode, translation, quotes, snippet editors and auto-split. TTS in-text highlight and the source-system work are unaffected and still live in `ROADMAP.md`.

Developer-facing record of evaluating [Tsundoku](https://github.com/tsundoku-otaku/tsundoku) (an actively-maintained, Apache-2.0 Mihon fork built for novels) as the basis for Reikai's novel reader, and the plan that came out of it. Two tracks were assessed: a near-term seamless-transitions port onto the current reader (Option 1) and a migration to tsundoku's native reader (Option 3). Option 3 is the one being built; Option 1 was dropped once the two landed in the same release. Investigated 2026-07-11 from the `refs/tsundoku` clone, rescoped 2026-08-07.

## Goal

Give the novel reader seamless chapter-to-chapter reading (scroll out of one chapter straight into the next, the way the manga webtoon reader already does), and set a direction that moves the novel reader off bespoke, hard-to-maintain code onto a maintained upstream.

## Why

Reikai's current novel reader ([novel-reader.md](novel-reader.md)) is net-new `reikai.*` code: a Voyager screen hosting a WebView plus a **verbatim-vendored LNReader `core.js`** that this session established we cannot easily update. The Kotlin↔JS contract is fragile (the progress-% formula mismatch, the `generalSettings` DOM-rebuild gotcha, the polyfill-parity concern), so every novel-reader feature is bespoke hand-work with no upstream to sync from. The manga webtoon reader has seamless chapter transitions that novels lack.

Tsundoku is a Mihon fork whose novel reader is **native, folded into Mihon's own `ReaderActivity`, and far richer than LNReader**, and it is actively maintained (Apache-2.0; roughly three commits a day, latest 2026-07-10 at review). That makes it both a feature reference and a candidate upstream.

## What tsundoku is (the evidence)

Symbols below are in `refs/tsundoku` at review time.

- **Folded into Mihon's reader, not a separate engine.** A novel opens in the **same `ReaderActivity`** as manga. `ReaderViewModel.getMangaReadingMode()` forces `ReadingMode.NOVEL` when `source.isNovelSource()`, and `NOVEL` (a `ViewerType.Text` reading mode) maps through `ReadingMode.toViewer()` to `NovelViewer` (native) or `NovelWebViewViewer` (opt-in). The rest of the reader branches on the viewer class (`viewer is NovelViewer || viewer is NovelWebViewViewer`).
- **Native TextView renderer is the default** (`ReaderPreferences.novelRenderingMode` defaults to non-webview). `NovelViewer` renders with Android `TextView` + spans, no WebView: HTML via `Html.fromHtml`, Markdown via `NovelMarkdownUtils`, inline images via a Coil `ImageGetter`, custom paragraph spans (`NovelViewerSpans`), and `PrecomputedText` for performance. It is continuous-scroll (a `NestedScrollView` + `LinearLayout`); `moveToPage()` is a no-op. A chapter is one stub `Page` whose `page.text` flows through Mihon's `ViewerChapters` model (`LocalNovelPageLoader`).
- **Seamless "infinite scroll"** is bespoke, not Mihon's manga `ChapterTransition`. Gated on `ReaderPreferences.novelInfiniteScroll` (default off) with threshold `novelAutoLoadNextChapterAt` (default ~95%). Crossing the threshold appends the prefetched next chapter into the same scroll surface (native: a separator plus the chapter's views; webview: a divider div in the DOM via `scroll-tracking.js` + a JS bridge), tracks the boundary crossing (`onChapterChanged` saves the leaving chapter at 100%, calls `ReaderViewModel.setNovelVisibleChapter` which drives the top-bar title, marks read, records history, prefetches the next), and prunes distant chapters to bound memory.
- **Feature set well beyond LNReader:** dual native/WebView engines; six fonts plus custom import; seven themes plus custom colors; full text controls (justify, indent, four margins); full TTS (highlight styles, auto-next, background service); tap-zones / volume-key / swipe / auto-scroll; user CSS + JS and regex find-replace; a customizable in-reader status bar; EPUB / TXT / HTML / Markdown plus EPUB import and export; novel-native trackers (NovelUpdates, NovelList); translation hooks.

## Approach: the two tracks assessed

**Option 1 (assessed, not built): port seamless transitions onto the current reader.** Re-implement tsundoku's infinite-scroll idea inside Reikai's existing WebView + `core.js` reader. At a scroll threshold, append the prefetched next chapter's HTML into the document behind a divider, track the boundary crossing (top-bar title, progress reset, mark-read, history, prefetch-next), and prune distant chapters. Reikai already prefetches the next chapter, so the data groundwork exists; the append + boundary tracking + pruning would be the new work. It was the lower-risk, incremental track while Option 3 was distant, and tsundoku's design subsumes it entirely.

**Option 3 (long-term, its own branch): adopt tsundoku's native reader.** Replace Reikai's Voyager WebView novel reader with tsundoku's native reader folded into Mihon's `ReaderActivity`. Novels join the manga reader (the deepest unification), gain native rendering (no WebView / `core.js` fragility) and the full feature set, and become **syncable from a maintained upstream** (tsundoku), the way manga syncs from Mihon. The domain / data / merge / tracking / source layers stay; the source→`page.text` loader is the net-new bridge.

## Recommendation

**Superseded 2026-08-07: Option 3 only, and it lands in 0.4.0.** Option 1 is dropped rather than done first. Its whole justification was shipping value while Option 3 stayed distant, and that gap has closed: Option 3 is now committed to the same release, so Option 1 would be medium work on a reader that gets deleted before it ever shipped in a stable build, and the migration carries the feature anyway. The reader also keeps its `ScreenModel` for the same reason: the ViewModel migration deliberately skipped it rather than migrating a file Option 3 deletes ([viewmodel-migration.md](viewmodel-migration.md)). The tsundoku-health contingency below was re-checked on 2026-08-15 and holds comfortably: head `d47f7c1aa` tagged **v0.3.1** dated 2026-08-14, with 379 commits in the preceding 90 days (previously checked 2026-08-07 at v0.3.0 and around 300).

**One input the migration scout must account for, new since this doc was written:** `ReadingMode.toViewer` no longer has a single branch per mode. Mihon's high quality WebGPU renderer (mihonapp/mihon#3388, synced 2026-08-14) puts an opt-in fork ahead of the stock viewers, so the function now returns `WebGpuViewer` / `WebGpuViewerContinuous` when `BasePreferences.highQualityRenderer` is on and the pager or webtoon viewers otherwise. Routing novels to a text viewer means fitting a third case around that fork rather than the two-way switch the original plan assumed, and `ReaderActivity` reads the WebGPU viewer's `isReversed` when it picks the chapter-navigator direction. The original recommendation follows, kept because its reasoning for Option 3 is unchanged.

**Original recommendation (2026-07-11): Option 1 now; Option 3 as the deliberate target for its own branch.** Accepting the **View-based novel reader** (Option 3) is the right call: it does not so much expand the sanctioned View exception as make it consistent (today manga=View / novel=Compose is a split; Option 3 makes "the reader is View-based" uniform), it removes the WebView / `core.js` fragility, and it converts the biggest bespoke subsystem into one synced from a live upstream, which is the core of how Reikai is maintained. The main contingency is tsundoku staying healthy (verified active at review; re-check when starting). Kick off Option 3 with a migration-planning `/scout`: what to keep vs replace, the source→loader bridge, the tsundoku-sync setup, and a phased path so novels never break mid-migration.

## Status

Evaluated and recommended (2026-07-11), rescoped 2026-08-07. **Option 3 is the work, queued in 0.4.0; Option 1 is not being built.** Option 3 was absorbed into the reader takeover, which has shipped its steps 0 to 4b and 6, so the native renderer and the shared content pipeline are in. Option 1 has not started and is not going to. Backlog lines in [ROADMAP.md](../../../ROADMAP.md), where Option 3 sits under Next and Option 1 under Parked / not building.

**Two things the migration inherits, found by the DI audit (2026-08-21) rather than by planning this
work.** The TTS transport is wired only by the reader model: `NovelTtsService` routes its MediaSession,
lock-screen and notification actions through `NovelTtsSession`'s callbacks, which nothing but
`NovelTtsController` writes and nothing but `NovelReaderScreenModel` constructs. Replacing the model
compiles clean and leaves those buttons as silent no-ops, so the transport needs an owner in the new
reader. Separately, `MainActivity` implements the reader package's `NovelVolumeKeyHost`, which is a
compile-level dependency of a Mihon file on the reader and is not listed in the DI tail.

Also worth knowing before the scout: the `reikai.**` and `exh.**` proguard keeps do NOT leave with this
migration, contrary to what the DI plan said until 2026-08-21. Both are permanent for reasons that have
nothing to do with the reader.

## Decisions & tradeoffs

- **View-based for novels is accepted** for Option 3. This reverses the Compose-native novel-reader choice ([novel-reader.md](novel-reader.md)); justified by maintainability-via-upstream plus true manga/novel unification, and by the reader already being the one sanctioned View holdout ([unified-reader.md](unified-reader.md)).
- **Two upstreams** (Mihon + tsundoku) is more sync surface, but replaces the far larger burden of maintaining a bespoke reader alone.
- **This session's parity features are not wasted:** always-on progress %, volume-key navigation, and the settings reorg ship value now and have tsundoku equivalents if we migrate.
- **The reader settings-sheet collapse waits for Option 3** (roadmap, Reader). Folding novels into `ReaderActivity` changes what the two sheets are, so collapsing them first is work against an architecture that is being replaced.
- **Attribution:** tsundoku is Apache-2.0; port with credit, the same as the Komikku ports.
