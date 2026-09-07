# Off-path manifest

Every Mihon file Reikai has **deleted** because a Reikai-owned twin (`reikai.*`) fully replaced it. Most are UI files whose twin renders the surface instead; a few are domain interactors whose twin took over the behavior. Reikai is a standalone repo ported from Mihon by hand (see [upstream-sync.md](upstream-sync.md)), so a deleted upstream file leaves no local copy for the next sync to diff against. This manifest is that record, and [`scripts/off-path-check.ps1`](../../scripts/off-path-check.ps1) reads it during a sync to diff each listed path across the sync range in the matching `refs/` clone and fail loudly if one changed, so an upstream change can never land on a file Reikai no longer uses.

When the check flags a path, open its **Replacement** and reconcile the upstream change into that twin by hand, exactly as if the file were still `// RK: inert`. The `refs/mihon` clone holds the pre-delete blob, so the change is a diff of upstream-before against upstream-after, applied deliberately into the twin.

**Replacement names the entry point into the surface, not the file every piece lives in.** A collapsed surface usually became several composables, and the row names the one that reaches the rest: `MangaInfoHeader`'s row points at `EntryDetailsColumn.kt`, whose `entryInfoItems` composes `EntryInfoBox`, `EntryActionRow` and the expandable description, because the header's pieces are siblings and opening any one of them is a dead end. Expect to read one hop down from the named file, and search its package before concluding a past change was dropped: a 2026-08-14 audit found every hunk present, but two of them one file over from the row that named them.

## What enforces this

The manifest used to be enforced by remembering to run the sync check. Three things now fail loudly instead,
because the two worst failures (a deleted file coming back, and a reroute nobody declared) were previously
silent and left no trace.

- **`pre-commit`, every commit.** No manifested path may exist in the working tree, and every `Replacement`
  must exist. A resurrected file means two implementations of one surface; a replacement that does not exist
  means the row protects nothing.
- **`pre-commit`, when `refs/mihon` is present.** Staging the deletion **or the rename** of a file Mihon
  still has requires a manifest row for it in the same commit, which closes the "a new reroute that skips
  the manifest is invisible" hole. Renames count because git records one as `R`, not `D`, and a file moved
  off its upstream path is just as unwatched as a deleted one. Without the clone it warns instead of
  blocking, so a fresh clone is never stuck.
- **`commit-msg`, on sync commits.** `scripts/off-path-check.ps1` writes `.git/off-path-checked` recording the
  upstream HEAD it ran against, and a `chore: sync Mihon...` subject is rejected unless that stamp exists and
  matches the current `refs/mihon` HEAD. Running the check stops being optional.
- **`docs-lint` CI** mirrors the first of these. CI has no `refs/` clones, so it cannot diff against upstream.

Install the hooks on a fresh clone with the command in [upstream-sync.md](upstream-sync.md).

## What is NOT here

- **Engine files** (a ViewModel, repository, or the source manager) are never deleted; they stay live and minimally patched on the render path, and sync normally. **One ruled exception, an orchestration takeover**: the migrate takeover retired the migration flow's ScreenModels (`MigrationListScreenModel`, the config screen's embedded model, `MigrateSearchScreenModel`, `MigrateMangaScreenModel`), which are listed below. What those files did was orchestration, and the engine floor beneath them stayed live and synced: `MigrateMangaUseCase`, `MigrationFlag`, the smart-search engines under `list/search/`, and every interactor they call. A ScreenModel is manifested only under such a ruling, never as a side effect of replacing a screen. The library takeover is the case that shows where the line falls: it took over the surface's orchestration but `LibraryViewModel` stayed live behind its adapter, so it is not manifested and syncs normally. An interactor that is still called stays too: the category interactors listed below went off-path only because nothing calls them any more, while their type-agnostic siblings (`GetCategories`, `RenameCategory`, `ResetCategoryFlags`, `SetDisplayMode`, `SetMangaCategories`, `SetSortModeForCategory`) remain live and are not listed. Example still pending its surface: `eu/kanade/tachiyomi/ui/download/DownloadQueueViewModel.kt` (replaced by `MangaDownloadQueueViewModel`) is a dead model kept `// RK: inert` until the download-subsystem unification (Road B) retires it there.
- **Partially collapsed files** keep their live remainder in place, marked `// RK` with what moved out, so they stay on the render path and are not listed here. Once nothing live remains, the file moves to the manifest below, as `MangaInfoHeader` did once its last live piece (the expandable description) became `ExpandableEntryDescription`.
- **Reikai-own files**, even under a shared `tachiyomi/` path. A file Reikai added (e.g. the retired `novel_categories.sq`) has no `refs/mihon` counterpart, so deleting it is not a Mihon reroute and the check has nothing to diff. Only files that exist in `refs/mihon` belong here.
- **Mihon's branding and project metadata** (owner, 2026-08-28). The Mihon logo and launcher-icon drawables, `.github/` CI, funding and renovate config, and the `fastlane/` store listing are Mihon's identity rather than a surface Reikai replaced, so removing them is not a reroute and no row records them. A diff of the upstream file set will always show them absent, and that is expected rather than a gap.

## Manifest

**A row with an empty Replacement is a declined feature, not an oversight.** Most rows name the twin
that renders the surface instead. A few name nothing, because Reikai deleted the file rather than
replacing it: the feature is one this fork does not run at all. On a sync the action for those is to
confirm the decision still holds, not to reconcile the change into anything. The `pre-commit`
replacement check skips an empty cell for exactly this case.

The path is relative to the repo root and matches the `refs/` clone layout. `Upstream` selects which clone the check diffs (`mihon`, or `tsundoku` once the reader migrates). Every row whose first column starts with a lower-case module directory is machine-read by the sync script; keep the three-column shape.

| Upstream path | Upstream | Replacement |
|---|---|---|
| app/src/main/java/mihon/feature/support/SupportUsScreen.kt | mihon |  |
| app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt | mihon | reikai/presentation/details/EntryDetailsContent.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaToolbar.kt | mihon | reikai/presentation/details/EntryToolbar.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaCoverDialog.kt | mihon | reikai/presentation/components/EntryCoverDialog.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt | mihon | reikai/presentation/browse/EntrySearchCardRow.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceDialogs.kt | mihon | reikai/presentation/browse/components/EntryRemoveDialog.kt |
| app/src/main/java/eu/kanade/presentation/manga/DuplicateMangaDialog.kt | mihon | reikai/presentation/browse/components/EntryDuplicateDialog.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/SourcesTab.kt | mihon | reikai/presentation/browse/source/ReikaiSourcesTab.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionsTab.kt | mihon | reikai/presentation/browse/extension/ReikaiExtensionsTab.kt |
| app/src/main/java/eu/kanade/presentation/browse/MigrateSourceScreen.kt | mihon | reikai/presentation/browse/migrate/ReikaiMigrateSourceTab.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/sources/MigrateSourceTab.kt | mihon | reikai/presentation/browse/migrate/ReikaiMigrateSourceTab.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt | mihon | reikai/presentation/track/EntryTrackInfoDialog.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverViewModel.kt | mihon | reikai/presentation/details/EntryCoverViewModel.kt |
| app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt | mihon | reikai/presentation/details/EntryDetailsColumn.kt |
| app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt | mihon | reikai/presentation/library/LibrarySettingsSheet.kt |
| app/src/main/java/mihon/feature/library/QueryNodeExtensions.kt | mihon | reikai/presentation/library/LibraryQueryMatch.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/CreateCategoryWithName.kt | mihon | reikai/presentation/category/CategoryActions.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/ReorderCategory.kt | mihon | reikai/presentation/category/CategoryActions.kt |
| domain/src/main/java/tachiyomi/domain/category/interactor/DeleteCategory.kt | mihon | reikai/domain/category/DeleteCategoryCleanup.kt |
| app/src/main/java/mihon/feature/migration/config/MigrationConfigScreen.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationConfigScreen.kt |
| app/src/main/java/mihon/feature/migration/config/MigrationConfigScreenSheet.kt | mihon | reikai/presentation/migrate/flow/MigrationTuningSheet.kt |
| app/src/main/java/mihon/feature/migration/dialog/MigrateMangaDialog.kt | mihon | reikai/presentation/migrate/flow/EntryMigrateDialog.kt |
| app/src/main/java/mihon/feature/migration/list/MigrationListScreen.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationListScreen.kt |
| app/src/main/java/mihon/feature/migration/list/MigrationListScreenContent.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationListScreen.kt |
| app/src/main/java/mihon/feature/migration/list/MigrationListViewModel.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationListViewModel.kt |
| app/src/main/java/mihon/feature/migration/list/models/MigratingManga.kt | mihon | reikai/presentation/migrate/flow/MigratingEntryRow.kt |
| app/src/main/java/mihon/feature/migration/list/components/MigrationExitDialog.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationListScreen.kt |
| app/src/main/java/mihon/feature/migration/list/components/MigrationMangaDialog.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationListScreen.kt |
| app/src/main/java/mihon/feature/migration/list/components/MigrationProgressDialog.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationListScreen.kt |
| app/src/main/java/eu/kanade/presentation/browse/MigrateSearchScreen.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationSearchScreen.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/search/MigrateSearchScreen.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationSearchScreen.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/search/MigrateSearchViewModel.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationSearchScreen.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/search/MigrateSourceSearchScreen.kt | mihon | reikai/presentation/migrate/flow/MigrationDeepPicker.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/manga/MigrateMangaScreen.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationFavoritesScreen.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/manga/MigrateMangaViewModel.kt | mihon | reikai/presentation/migrate/flow/EntryMigrationFavoritesScreen.kt |
| app/src/main/java/eu/kanade/presentation/history/HistoryviewModelStateProvider.kt | mihon | reikai/presentation/recents/RecentsScreen.kt |
| app/src/main/java/eu/kanade/presentation/history/components/HistoryItem.kt | mihon | reikai/presentation/recents/RecentsRows.kt |
| app/src/main/java/eu/kanade/presentation/history/components/HistoryWithRelationsProvider.kt | mihon | reikai/presentation/recents/RecentsRows.kt |
| app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt | mihon | reikai/presentation/recents/RecentsScreen.kt |
| app/src/main/java/eu/kanade/presentation/updates/UpdatesFilterDialog.kt | mihon | reikai/presentation/recents/RecentsFilterSheet.kt |
| app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt | mihon | reikai/presentation/recents/RecentsScreen.kt |
| app/src/main/java/eu/kanade/presentation/updates/UpdatesUiItem.kt | mihon | reikai/presentation/recents/RecentsRows.kt |
| app/src/main/java/eu/kanade/presentation/browse/GlobalSearchScreen.kt | mihon | reikai/presentation/browse/globalsearch/EntryGlobalSearchScreen.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreen.kt | mihon | reikai/presentation/browse/globalsearch/EntryGlobalSearchScreen.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceComfortableGrid.kt | mihon | reikai/presentation/browse/catalogue/EntryBrowseCatalogue.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt | mihon | reikai/presentation/browse/catalogue/EntryBrowseCatalogue.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceList.kt | mihon | reikai/presentation/browse/catalogue/EntryBrowseCatalogue.kt |
| app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt | mihon | reikai/presentation/browse/catalogue/EntryCatalogueScreen.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt | mihon | reikai/presentation/browse/catalogue/EntryCatalogueScreen.kt |
| app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceToolbar.kt | mihon | reikai/presentation/browse/catalogue/EntryCatalogueToolbar.kt |
| app/src/main/java/eu/kanade/presentation/browse/SourcesFilterScreen.kt | mihon | reikai/presentation/browse/source/EntrySourcesFilterScreen.kt |
| app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/SourcesFilterScreen.kt | mihon | reikai/presentation/browse/source/EntrySourcesFilterScreen.kt |
| app/src/main/java/mihon/app/di/MihonViewModelFactory.kt | mihon | mihon/app/di/ReikaiViewModelFactory.kt |
| app/src/main/java/eu/kanade/tachiyomi/util/chapter/ChapterRemoveDuplicates.kt | mihon | reikai/domain/reader/DuplicateChapters.kt |
| app/src/main/java/eu/kanade/presentation/reader/OrientationSelectDialog.kt | mihon | reikai/presentation/reader/ReaderOrientationDialog.kt |
| app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt | mihon | eu/kanade/presentation/more/settings/screen/SettingsMangaReaderScreen.kt |

**A row tracks the file's CURRENT upstream path, not the name Reikai deleted.** When upstream renames a
manifested file, repoint the row at the new path, because the check `cat-file`s the path at upstream HEAD and,
finding nothing, reports VANISHED and **skips the diff entirely**. A renamed row therefore reports the same
message forever while silently covering up every later change to it, which is the opposite of what the manifest
is for. The cover model is the worked example: Reikai deleted `MangaCoverScreenModel.kt`, the ViewModel
migration (`mihonapp/mihon#3594`, mihon `c3b99aea0`) renamed it to `MangaCoverViewModel.kt` upstream, and the row
now names the new path so a real change to it is caught. Reikai has since made that rename too.

So treat **VANISHED as unresolved, never as expected**: find whether upstream renamed the file
(`git log --oneline --follow --diff-filter=R -- <new path>`) and repoint the row, or confirm it was genuinely
deleted and drop the row with a note. Only a deliberate, recorded conclusion closes one.

`TrackInfoDialog.kt` had two deferred upstream changes, both now carried by the twin. Mihon `98705910e`
(`mihonapp/mihon#3609`) dropped `private` from the eight nested models so the factory could reach them, and
mihon `b2015d1ef` converted all eight to Metro assisted injection; the twin's own conversion lands both, since
a graph-contributed factory cannot be private either. Reikai diverges in two places upstream has no reason to
have: the writer is picked by content type through `trackWriterFor`, and the score model declares its tracker
above its state rather than seeding the state from an `init` block. On a sync, still confirm nothing new
touched the file (`git log --oneline <base>..HEAD -- "*TrackInfoDialog.kt"`).

The history and updates rows are the surfaces' UI leaves, replaced when the shared row composables took over both feeds. `HistoryScreen.kt` and `UpdatesUiItem.kt` were partially collapsed for a while and are now listed too, each having reached the manifest the way `MangaInfoHeader` did: `HistoryUiModel` was retired outright, since only its `Item` case survived the takeover and the engine dates its own rows, so `HistoryViewModel` now emits stored rows; the last-updated line moved into `RecentsRows.kt`, which is the row emitter for every mode. The state provider's row names upstream's current filename, `HistoryviewModelStateProvider.kt`; Reikai deleted it as `HistoryScreenModelStateProvider.kt`, before the deferred ViewModel migration (mihon `c3b99aea0`) renamed it. Deleting `UpdatesScreen.kt` also dropped `UpdatesViewModel.State.getUiModel()`, which nothing else called.

The three category interactors each scoped themselves to the manga-visible rows. Once a category can span both libraries those rows overlap the novel-visible ones, so a create, reorder or delete that only sees one library writes an order or a preference scrub that is wrong for the other. `CategoryActions` does all three over the whole table instead.

`SupportUsScreen` is the manifest's first declined-feature row, so it has no replacement. It asked users to fund Mihon through Patreon or OpenCollective and told them Mihon is "backed by %d+ patrons"; Reikai runs no donation campaign, and its `More` screen never offered the entry that reached the screen, so nothing in this fork could open it. Soliciting money on another project's behalf is not something to keep sitting in the binary, and the code rules say dead code is deleted rather than marked. Its seven strings went with it, as did `donationCampaign`'s six, which were already unreachable. If Reikai ever wants its own support screen, port it fresh from upstream and rewrite it for Reikai rather than reviving this.

The three browse-grid containers went when one catalogue body took over both per-source screens. Only the containers left: `EntryBrowseGridCell` was already the shared leaf all three delegated to, and `BrowseSourceEHentaiList` stays live as the adult-source layout, re-typed to the neutral row. `BrowseSourceScreen.kt` in `eu.kanade.presentation.browse` is manifested rather than left partially collapsed: its `MissingSourceScreen` remainder went with the shared catalogue screen, which renders that state off the neutral one. `BrowseSourceToolbar` went the same way: it was already neutral apart from taking a manga `Source` to read three booleans off, so it moved rather than being replaced.

The two sources-filter rows went when one screen took over both halves. The Browse filter action could only reach the manga screen from the All and Manga chips, so a plugin could not be enabled, disabled or language-filtered from the chip most readers are on; the shared screen carries a Manga / Novels chip and renders each half from its own ViewModel. Only the chrome is shared: manga still writes Mihon's enabled-language set and novels their own deny-list, so neither preference model moved. `SourcesFilterScreen.kt` in `eu.kanade.presentation.browse` is manifested rather than left partially collapsed, because its content, header and item composables moved into the shared screen along with the chrome that called them. Reikai's own `NovelSourcesFilterScreen` was absorbed the same way and needs no row; `NovelSourcesFilterViewModel` stays live behind the novel half.

`SettingsMangaReaderScreen` is the second rename-rather-than-takeover row, and it is Reikai's own
rename rather than a debrand: the settings restructure split the reader screen into a manga one and a
novel one, and the manga half is upstream's file under a new name, still in upstream's package and
still hand-merged on every sync. Without a row, `SettingsReaderScreen.kt` is a path the check never
looks at, so an upstream change to the reader settings would land nowhere and nothing would say so.
It was missed at the time because git records a rename as `R` while the `pre-commit` guard only read
`D`; the guard reads both now, so the next one cannot arrive unrecorded the same way.

`MihonViewModelFactory` is the manifest's first debrand rename rather than a surface takeover. `ReikaiViewModelFactory` is that file with the class renamed and a KDoc added, nothing else, so its Replacement sits under `mihon/app/di/` rather than `reikai/`: it kept upstream's package. It reached the manifest by a sweep rather than by the hook, and could only ever have arrived that way. Upstream added it in mihon `b2015d1ef` (`mihonapp/mihon#3608`), which is inside the synced base, but the Mihon-base seed brought in the renamed copy instead, so the file never existed here and no deletion was ever staged for `pre-commit` to see. **A file that arrives absent is invisible to that check by construction**, so a periodic diff of the whole upstream file set against this tree is the only thing that finds one.
