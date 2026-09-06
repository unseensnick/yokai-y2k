# Settings restructure

> **Standing rules for this work.** This is an owner-approved settings rewrite (2026-09-03), the second standing exemption from the no-standalone-refactor rule in [code-quality.md](../../../.claude/rules/code-quality.md) after the content-layer program. It is scoped to the screens named below and does not license adjacent cleanup. Two mechanics bind every change here: **a new settings screen is invisible to search unless it is registered** in `SettingsSearchScreen`'s hardcoded screen list, and **moving a row between groups changes its `HighlightKey`**, so a stale key fails silently by not scrolling rather than erroring. Search registration is part of each change, never a follow-up.

## Goal

Make Settings findable. Three screens carry the problem: Reader is one flat list of 68 rows with light-novel settings scattered through it, About bypasses the preference system entirely so nothing on it is searchable, and source settings live in four different places depending on which source you mean.

## Why

`unseensnick/Reikai#55` asks for reader settings that do not require hunting. The reporter's complaint is the number of options shown at once and the scrolling needed to find one, and their screenshot is the in-reader sheet, but the app-level screen behind it has the same defect one level up and is the cheaper half to fix.

The measured shape of the problem, taken 2026-09-03:

- **Reader is 68 rows** in ten groups on one screen, with novel settings in three non-adjacent places (`Reading · Novels`, `Accessibility · Novels`, and six rows inside `Reader navigation`) distinguished from their manga twins only by a title suffix that `contentTypedCategory` appends.
- **Source settings are in four places**: with the source (`SourcePreferencesScreen`, pushed from extension details and the catalogue overflow), at the Settings root for two enhanced sources (MangaDex, E-Hentai), under Browse > Sources (extension stores), and under Library > Sources (preferred sources). Which one is correct depends on the source, which is not something a user can know.
- **About is not a `SearchableSettings`.** It hand-rolls a `ScrollbarLazyColumn`, so nothing on it reaches settings search, including "check for updates" and "licenses". It also mixes version actions, legal links and social links in one flat list.
- **Recommendations is three levels down** behind an unrelated parent (Settings > Library > Recommendations), carrying 24 rows in five groups, and its breadcrumb already renders without "Library" because search breadcrumbs are two levels by construction.

Nothing about a feature's size predicts whether it is a group or a screen today: Recommendations is a pushed screen at 24 rows, Advanced > Network is an inline group at 13, and MangaDex is a whole top-level screen for 5 rows with no groups at all.

## Approach

Three passes, in order, each shippable alone.

### Pass 1: Reader

The single "Reader" root entry becomes two, "Manga reader" and "Novel reader", each holding its content type's complete set. Neither costs a tap more than the old Reader row did, and the `contentTypedCategory` suffix retires because the screen name now carries the content type.

**Two shapes were built and rejected before this one, and the reasons are worth keeping.** A hub screen with the shared settings on top and two drill-downs charged every manga user an extra tap to buy novel users a findable screen, which is a bad trade on volume alone (owner, 2026-09-03). Keeping the shared settings on both screens over one unified key was then rejected because the same row appearing on two screens reads as two independent settings, and a section header is too weak a signal to correct that.

**So the six same-named settings stay per-type, deliberately** (owner, 2026-09-03). `skipDupe` / `readerSkipDuplicateChapters()`, `markReadOnSkip` / `readerMarkReadOnSkip()`, `keepScreenOn` / `readerKeepScreenOn()`, and the volume-key trio `readWithVolumeKeys` / `readWithVolumeKeysInverted` / `readWithVolumeKeysScrollAmount` against `readerUseVolumeButtons()` / `readerVolumeButtonsInverted()` / `readerVolumeButtonsFraction()`. **This is a ruled per-type capability, not unpinned twin debt, and it should not be "fixed" by a later unification.** The reasoning: the write-once rule targets behaviour a user can observe being inconsistent, and these are ergonomics rather than rules. Paged images and continuously scrolling text want different answers, so wanting volume keys on for novels and off for manga is a real preference, not a mistake. Each screen is therefore self-contained, which is also what makes two screens legible: everything on a screen applies to that reader, with no cross-screen semantics to explain. Settings search disambiguates the pairs by breadcrumb ("Manga reader > Navigation" against "Novel reader > Navigation"), verified on device.

Two further pairs were never twins and stay split regardless: bottom buttons offers a different option set per type (`ReaderBottomButton.Scope.Manga` against `Scope.Novel`), and novel orientation deliberately omits two of manga's entries.

### Pass 2: About

Rebuilt as a normal `SearchableSettings` so its rows reach search, with the version and update actions ungrouped at the top and Legal and Links as groups below. The logo header and the link-icon row are `CustomPreference`s, the shape `SettingsDataScreen` already uses for its backup segmented buttons.

Two mechanics made this cheap. `SearchableSettings` extends Voyager's `Screen`, so every existing `screen = AboutScreen` call site and both `AboutScreen.getVersionName` callers keep working untouched. And the search index filters on a non-blank title, so a `CustomPreference` carrying only a composable never becomes a junk search result; the logo and link rows pass a blank title deliberately for that reason.

The update-check spinner survives because `TextPreference` already takes a `widget` composable. The two conditional rows (`updaterEnabled` for the update check, `!BuildConfig.DEBUG` for What's new) became `takeIf` on the same conditions, so they are unchanged in behaviour but cannot be exercised on a debug build, where both are false.

### Pass 3: Sources consolidation

**Scoped down from "one new Sources screen" once the code was read.** The Library screen's "Sources" group turned out to hold merge settings, not source configuration: the merge master switch, the two auto-merge toggles, and preferred sources, whose only readers are the merge path (`MergePrefs` in `LibraryViewModel` and the same structure in `NovelLibraryViewModel`). Moving those to a Sources screen would have separated them from the switches that give them meaning. And Browse already held the extension stores row and the NSFW switch, so a new screen would mostly have moved Browse's own rows to a sibling and left the user choosing between two. So the consolidation target was Browse all along, and only two strays needed collecting.

**What shipped instead.** Browse is renamed "Browse and sources" (a new string, because `MR.strings.browse` is also the Browse tab's own label) and gains a Source settings group holding MangaDex and E-Hentai as drill-downs, so the Settings root loses both. The adult-sources gate moves there from Advanced, directly above the group it reveals: leaving it in Advanced meant flipping a switch made something appear on a screen the user was not looking at. **That gate is passed into the group as observed state rather than read through `isEnabled()`**, which is a plain preference read; without a snapshot dependency the row would not appear until the screen was recreated, which is the same defect an older `// RK` note records for the Settings root. Clearing merges and repairing novel details move into Advanced's Library group, since they are library maintenance, but they stay in Advanced rather than moving beside the everyday merge switches, because dissolving every merge group is destructive and belongs with the other destructive actions. **A cross-link from `SourcePreferencesScreen` to a source's app-owned settings was built and reverted.** That screen hosts a Fragment through `AndroidView` behind a one-time commit guarded by `rememberSaveable`; returning from a pushed screen brings the composition back with that flag already set, so it takes the reflection re-attach path and the fragment's view never returns, leaving an empty body. Plain re-entry is fine, because that builds a new screen instance. Upstream never pushes from there, so the path is untested, and debugging it was not worth a convenience. The file carries a comment saying not to push from it.

**The Library group is renamed "Merged series"**, naming what it holds now that Recommendations has left it.

Recommendations moves out of Library to its own top-level entry in the same pass (owner, 2026-09-03), which fixes both its depth and its truncated breadcrumb. It is already registered as a top-level search route, so search already treats it as a peer of Library.

### Pass 4: two displaced rows

**Added 2026-09-06, after the owner spotted one of them.** The three passes fixed screen structure and never audited individual rows against their screen's subject, which is a different defect and is why they missed these. An audit of every root screen found only two that are Reikai's own to move; everything else it flagged sits where Mihon puts it, including the Advanced screen's "Library" and "Reader" groups, which read as our drift and are upstream's design (`refs/mihon/.../SettingsAdvancedScreen.kt:132-375`). **Upstream placement is not ours to change**, so the audit's other candidates (high quality renderer, disallow non-ASCII filenames, update manga titles, invalidate download cache, images in descriptions) were left alone.

**Page preview rows** left Appearance's Display group for Browse and sources' Sources group. It controls page-preview thumbnails on the details page, gated on the source implementing `PagePreviewSource` (`MangaViewModel.kt:478`), which four sources do, so it is a source-capability setting rather than app-wide look. Komikku, which the feature was ported from, keeps it on Appearance but inside its own fork-only group rather than among Mihon's display rows, so nothing about the port required Display; pass 3's ruling that source settings live on Browse decided the destination.

**The two "Hide missing chapter indicators" rows** now sit together in Library's Behavior group, each content-typed. The manga row has not moved: it is where upstream puts it (`refs/mihon/.../SettingsLibraryScreen.kt:259`, under `pref_behavior`). The novel twin came out of "Library update, Novels", where it had nothing to do with updating, and both gained a `· Manga` / `· Novels` suffix because the pair otherwise renders as one row printed twice, in the screen and in settings search alike. **The reader screens were considered and rejected**: the setting drives only the details chapter list (`MangaEntryAdapter.kt:136`, `NovelDetailsViewModel.kt:506`) and `MissingCount` reaches nothing in the reader, so naming it a reader setting would trade one wrong home for another.

Both are pure relocations. The preference keys do not move, so there is no migration, no `PreferenceRestorer` skip and no behaviour change.

## Key files

- `eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`, splitting into itself plus two new screens.
- `eu/kanade/presentation/more/settings/screen/Commons.kt`, `contentTypedCategory`, which retires with pass 1.
- `eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt`, the hardcoded screen list every new screen must join.
- `eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`, the root entry list.
- `eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt`, rebuilt on the DSL in pass 2.
- `SettingsBrowseScreen.kt`, `SettingsLibraryScreen.kt`, `SettingsMangaDexScreen.kt`, `SettingsEhScreen.kt`, and `reikai/presentation/recommendation/SettingsRecommendationsScreen.kt` for pass 3.
- `eu/kanade/tachiyomi/ui/browse/extension/details/SourcePreferencesScreen.kt`, whose `populateScreen` gained the delegated-source unwrap in pass 3, and which carries a do-not-push-from-here note.

## Status

Ruled 2026-09-03. **Pass 1 shipped**: two top-level reader entries, each self-contained, the suffix retired, both registered for settings search. Verified on the emulator: the root list shows both entries one tap deep, each screen carries its full set, the two same-named settings are independent (toggling the novel keep-screen-on left the manga key untouched), and search returns both volume-key rows with distinct breadcrumbs. Gates green at 1334 app, 75 domain and 35 core:common tests.

**Pass 2 shipped**: About runs on the preference DSL and is registered for search. Verified on the emulator: the screen renders unchanged (logo, version with its build stamp, Legal, Links), searching "licenses" returns it as "About > Legal", and following that result lands on About.

**Both passes were then re-verified on a minified `nightly` build**, which matters because `release`-type builds are minified and the dev build is not. The app launched with no `TypeReference` failure (the R8 hazard the surviving Injekt calls carry), both reader entries and their screens rendered, and settings search still resolved rows on the new screens. The two build-gated About rows only render there: "What's new" needs a non-debug build, and "Check for updates" additionally needs the `enable-updater` Gradle property, so the check was run with `:app:installNightly -Penable-updater`. Tapping it completed end to end and toasted "No new updates available". The spinner in its `widget` slot was not observed, because the check returned inside 350ms.

**Pass 3 shipped**, completing the rewrite. Verified on the emulator: the root list dropped from twelve entries to eleven with both source screens gone and Recommendations added, the Source settings group holds both rows and appears and disappears live as the adult gate is toggled, Advanced's top block lost its three strays and its Library group gained them, the Library group reads "Merged series", and settings search resolves the adult switch to "Browse and sources > NSFW (18+) sources".

**Pass 3 also surfaced a pre-existing bug and fixed it.** Reaching a delegated source's own preference screen rendered blank, for all seven delegated sources. Mihon resolves the source and tests `is ConfigurableSource` directly, which a wrapped source fails, and Reikai's EXH port never carried Komikku's unwrapping patch for that file. Recorded in [feature-ports.md](../feature-ports.md); unrelated to this rewrite beyond being what made it visible.

**Pass 4 shipped.** Verified on the emulator: Appearance's Display group ends at the combined Recents row with no page-preview slider left on it, Browse and sources shows the slider in its Sources group with the stored value carried over untouched, Library's Behavior group holds both indicator rows labelled `· Manga` and `· Novels`, and settings search returns "Page preview rows" as "Browse and sources > Sources" and the two indicator rows as distinguishable entries where they previously read identically.

The reader takeover ([content-layer-reader-surface.md](content-layer-reader-surface.md)) starts at its step 2 now that this is done.

## Decisions & tradeoffs

- **Two top-level reader entries, not one screen with drill-downs.** A hub costs the common case a tap, and the shared section it exists to hold turned out not to be wanted (see pass 1).
- **Recommendations leaves Library entirely** rather than gaining a second route, since two ways in is one of the inconsistencies this work removes.
- **MangaDex and E-Hentai are source settings, not root categories.** They sit at the root only because their preferences are app-owned rather than extension-owned, which is an implementation detail the user should not have to know.
- **E-Hentai's favorites backup stays with the source.** It reads as a Data and storage feature, but it writes to the source's own account rather than to a file, and Data and storage is about the app's own database.
- **Not in scope:** the Tracking, Downloads, Security and Appearance screens. They are consistent enough, and folding them in turns a bounded rewrite into an unbounded one. **Narrowed by pass 4:** that judgement was about those screens' own shape, and it did not cover a row from elsewhere parked on one, which is what Appearance turned out to be holding. Their structure is still not in scope.

## Known gap

The four existing dead-key skips in `PreferenceRestorer` have no test, and neither would a fifth. Nothing pins the rule that a retired key must not be resurrected by a backup restore. Recorded rather than fixed here, since backfilling a suite for four pre-existing skips is its own piece of work.
