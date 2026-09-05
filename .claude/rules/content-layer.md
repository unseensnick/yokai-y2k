---
alwaysApply: true
---

# The content layer

Reikai serves two content types, manga and light novels, from one Reikai-owned layer over a neutral
`Entry` vocabulary, so a change written once reaches both. This file is the law. The rationale, the
measurements behind each ruling and the per-surface history live in
[content-layer-architecture.md](../../docs/dev/plans/content-layer-architecture.md) and the four
per-surface plan docs; read those before designing, read this before touching anything.

The goal is **parity and anti-divergence**, and every duplicate is a cost tracked against it.
Collapsing two implementations into one is the mechanism rather than the point: it is worth doing
because it makes future divergence structurally impossible. The point is that a change to one
content type cannot silently miss the other.

## How deep the seam actually goes, per surface

Every surface is at a different depth. Assuming a surface is deeper than it is, is the single most
common way to mis-plan work here.

| Surface | Depth | What is actually shared | Record |
|---|---|---|---|
| Details | Deep | Neutral state and behavior contract, two adapters; Mihon composables deleted and manifested | content-layer-details-surface |
| Library | Takeover of orchestration | Shared engine owns assembly, selection and the action verbs. `LibraryViewModel` stays **live** at 884 lines behind its adapter, still the manga provider: it has real callers, so it is an engine file and is never manifested (owner, 2026-08-22, settling the amendment that said otherwise). Dead members inside it are deleted, not marked | content-layer-library-surface |
| Migrate | Full takeover | The whole flow, screens and orchestration; seventeen Mihon files deleted and manifested | content-layer-migrate-surface |
| Browse | Full takeover | One engine assembles each of the four multi-source lists over two providers with the content-type chip as a predicate, and one catalogue screen serves both per-source grids over two Paging 3 pagers. Fifteen Mihon files deleted and manifested. Only the filter dispatch stays per-type, as a slot the screen fills | content-layer-browse-surface, content-layer-add-flow |
| History, Updates | Takeover of the screen | Both tabs render one shared screen over the recents engine, which owns assembly, search, selection, the dialogs and the action verbs; the four feed models stay live behind adapters. The two tabs keep only what needs the host: the badge reset, the splash gate, the bottom nav and reselect. The filter sheet is shared too, drawing what the mode can answer for and editing the surface's own selection | content-layer-recents-surface |
| Downloads | Not started | Nothing. Road B; `DownloadQueueViewModel` is still `// RK: inert` | download-queue-unification |
| Reader | Takeover of orchestration, ruled not started | One host Activity keeps the window responsibilities, a shared engine owns navigation, position, the chrome composables, the action verbs and **the viewer contract** over two providers. Menu visibility stays with the host, because half of it is the insets controller. `ReaderViewModel` stays **live** as the manga provider; the novel reader dissolves entirely, since its engine is being replaced by two tsundoku-ported rendering modes. Mihon's `Viewer` becomes one adapter under the engine's contract rather than the interface novels implement, because `ViewerChapters` cannot carry a novel chapter without the novels-as-manga mechanism the program rejected. **The image viewers are never diverged past their five existing `// RK` markers**, and position is a typed capability, never an `Int`, with `ReaderChapter.requestedPage` as the one named exception, since the three viewers read it directly. **The takeover owns the settings whose implementation lives in the code being deleted**, which on this surface is most of the novel reader's typography | content-layer-reader-surface |

Everything below the behavior seam is scheduled to be redone at it. Sequencing is in the record, not
here.

**The two depths fail differently, so look for different things.** A taken-over surface produces
**upstream-drop** bugs: behaviour the replaced code had that ours silently lost. A UI-leaf or
behavior-partial surface produces **duplicate-implementation** bugs: one rule restated at N call
sites, with some of them wrong. Neither class shows up in the other's review.

## Ownership

- **The two engines are never merged.** Mihon's manga engine (the `Manga` model, its repositories,
  source, library and download machinery) stays upstream-tracked and minimally patched. Reikai's
  novel engine stays fully Reikai-owned. Merging them would re-type Mihon's whole stack and sever
  upstream flow, and it could not reach the bottom anyway: `source-api` is the contract installed
  extensions compile against, so a manga-shaped source boundary survives any merge.
- **That exemption covers the implementation, never the rule** (owner, 2026-08-10). A split engine
  excuses two implementations. It has never excused the same rule being written twice with nothing
  binding them, which is the drift the whole program exists to stop. An engine twin still owes the
  pin-once ladder below: a shared kernel, a typed capability, or one conformance test over both
  halves. A twin that can cite none of the three is unpinned debt, not a sanctioned split.
- **The exemption is only for a Reikai-to-Mihon twin.** It exists because re-typing Mihon's models
  breaks hand-porting, so it reaches exactly as far as that reason does. Two Reikai-owned files
  twinning each other are ordinary duplication and get no exemption at all; judge them by the code
  rules like any other duplicate. Measured 2026-08-10: 67 of 84 twin-marked files are Reikai-owned,
  so most of what reads as a sanctioned engine split is not one.
- **Adapters are the only seam.** The shared layer talks to each engine through an adapter, so a
  renamed upstream field breaks the build at one file instead of hiding until a pixel hunt.
- **Never reimplement Mihon's spine** in the shared layer: read, download, filter, sort, selection.
  Interactors and repositories stay Mihon's and stay synced. A step that starts reimplementing what
  `setReadStatus` or `DownloadManager` does has gone too far. Three surfaces have ruled amendments
  widening this (library twice, migrate once); they are scoped to those surfaces and are not a
  general licence.
- **Identity is the sealed `EntryId`** (`reikai/domain/entry/EntryId.kt`), never a raw `Long` and
  never the retired negative-id disguise. Novels keep their own tables; novels-as-manga is ruled out.
- Placement and `// RK` fencing follow [architecture.md](architecture.md); screen shape follows
  [screen-conventions.md](screen-conventions.md). Not restated here.

## The rules that bind every change

- **Write once, both types get it.** Any change to behaviour a user can observe lands for manga and
  novels in the same commit, not the next one and not a follow-up roadmap item. The only exit is
  that a type genuinely cannot support it: a named mechanism in the source contract, the schema or
  the plugin format that makes it impossible, cited in the commit and recorded in the surface's
  plan doc. "The engines are structured differently", "the other side needs a rewrite first", "no
  caller needs it yet" and cost are not exits, they are the work. If the second half cannot ship in
  the same commit, the change does not ship: it goes back to planning as one item covering both. A
  gate is an owner ruling and is never self-issued. **Forward-only (owner, 2026-08-09):** the parity
  backlog that predates the rule is labelled in `ROADMAP.md` as gated or as an open gap, and does not
  retroactively block unrelated work.
- **Sharing the implementation is a means, not the rule.** Declining a code collapse stays allowed on
  cited mechanism grounds (the browse pager and the filter dispatch are the standing examples), and
  it never licenses a behaviour fork. Two implementations that must behave identically are pinned by
  one conformance test.
- **Divergent bits are typed capability slots.** Never a nullable field, never a boolean-flag
  combination, never a per-type fork inside shared code. A capability one type genuinely cannot
  support is hidden for that type, never shown disabled and never a silent no-op.
- **A shared component either derives a piece of state or does not own it.** Sharing the storage
  while each type interprets it its own way is a fork wearing shared-code clothing, and nobody rules
  on it because it looks unified.
- **A rule that must hold for both types exists once**, in this order of preference: a shared kernel
  both sides call (`resolveDefaultCategoryIds` is the model), a typed capability the compiler forces
  both types to answer, or, where neither reaches, one conformance test parameterized over both
  adapters. Hand-maintained twin tests are the last resort and they drift: the 2026-08-05 audit
  found 8 tests on `MigrateMangaUseCase` against 24 on `MigrateNovelUseCase`. A type that genuinely
  cannot do the thing declares it unsupported in the case rather than being quietly omitted.
- **A `twin of` marker is a claim that owes a pin** (owner, 2026-08-10). Writing that a function is
  another's twin asserts the two must behave alike, so it names which rung pins them: the kernel they
  both call, the capability they both answer, or the conformance test that runs both. A marker with
  no pin named is debt, and it is paid the next time either half is touched, on the same trigger the
  parity rule uses. Never a standalone sweep to clear the backlog, and never a new unpinned twin.
- **Parity is the default; a gap needs a ruling to stay open.** A gap you notice on a surface you are
  touching is levelled up in that change unless the owner gates it. Never fake a feature a type
  cannot support.
- **A decline expires with its evidence.** "Assessed and declined, do not re-flag" holds only while
  the reasoning that produced it holds, so record that premise with the decline and treat the
  decline as void once it changes. The neutral adder contract was declined because no shared caller
  existed; the recents engine became one.
- **Verify by mutation.** A new test is not done until the production clause it names has been
  deleted, the test seen red, and the clause restored.

## Replaced Mihon files: delete and manifest

- A pure-UI Mihon file fully replaced by a shared component is **deleted** and given a row in
  [off-path-manifest.md](../../docs/dev/off-path-manifest.md). The keep-inert rule is retired: a dead
  copy buys a diff base `refs/mihon` already provides, at the cost of a file an edit can land in.
- **Engine files are never deleted.** They stay live and minimally patched on the render path. The
  only exceptions are ruled orchestration takeovers, recorded in the manifest's own carve-out note.
- A **partially collapsed** file keeps its live remainder in place, marked `// RK` with what moved
  out, until that remainder can move too: take it into a Reikai-owned file, or retire it where the
  takeover made it pointless, then delete and manifest the original. Nothing live remaining is the
  bar, and moving the last piece out is how a file reaches it, as `MangaInfoHeader` did.
- **Dead code is deleted, never marked** (owner, 2026-08-12), in a live Mihon file as much as in a
  Reikai-owned one. A noted remnant is the retired keep-inert rule at member scale, and the diff base
  a sync needs is `refs/mihon`, not a corpse in this tree. The download queue model is the one
  sanctioned holdout, and it is named above.
- The manifest is enforced by `pre-commit` and `commit-msg` hooks plus `docs-lint`, and read by
  `scripts/off-path-check.ps1` during a sync. Treat a VANISHED report as unresolved, never expected.

## A takeover is not complete until its behaviour is inventoried

Cutting a surface over and verifying it on device is **not** the completion bar. Device verification
finds what you thought to test. The migrate surface passed that bar and then spent five audit rounds
with two upstream behaviours sitting silently dropped, both found by accident rather than by any
check: the additional search query never reached a search, and a manually picked target was accepted
without the refresh upstream refuses one on.

So a takeover is done when the replaced code's behaviour has been walked end to end and every item
marked **present**, **deliberately dropped** with the reason, or **missing**. The manifest catches
upstream changing a file after a takeover; nothing else catches what the takeover failed to carry
across in the first place. That inventory is the thing that does.
