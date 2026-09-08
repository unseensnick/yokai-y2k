package reikai.presentation.recents

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.category.RecentsSurface
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeManager
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.components.EntryDuplicateCardUi
import reikai.presentation.browse.components.EntrySourceLabel
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR

/**
 * The engine over hand-built providers. The real adapters need their surface's live models and cannot run
 * here, so these fakes stand in for them; the chip is preset before construction because the in-memory
 * preference store's `changes()` never emits, which makes a mid-test flip untestable rather than false.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsEngineTest {

    private val store = InMemoryPreferenceStore()
    private val sourcePreferences = ReikaiSourcePreferences(store)
    private val updatesPreferences = UpdatesPreferences(store)
    private val libraryPreferences = LibraryPreferences(store)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun engine(
        providers: List<RecentsProvider>,
        chip: ContentType = ContentType.ALL,
        modes: Set<RecentsMode> = setOf(RecentsMode.UPDATES),
    ): RecentsEngine {
        sourcePreferences.updatesContentType.set(chip)
        return RecentsEngine(
            providers = providers,
            surface = RecentsSurface.UPDATES,
            modes = modes,
            sourcePreferences = sourcePreferences,
            updatesPreferences = updatesPreferences,
            libraryPreferences = libraryPreferences,
        )
    }

    /**
     * An engine over a store whose preference flows emit, which the in-memory one's do not. Only the
     * derivations that combine a preference with something else need it; everything above reads a
     * value seeded from `get()` and is fine on the cheaper store.
     */
    private val emittingStore = EmittingPreferenceStore()
    private val emittingUpdatesPreferences = UpdatesPreferences(emittingStore)

    private fun multiModeEngine(modes: Set<RecentsMode>) = emittingEngine(provider(ContentType.MANGA), modes)

    private fun emittingEngine(provider: RecentsProvider, modes: Set<RecentsMode>) = RecentsEngine(
        providers = listOf(provider),
        surface = RecentsSurface.UPDATES,
        modes = modes,
        sourcePreferences = ReikaiSourcePreferences(emittingStore),
        updatesPreferences = emittingUpdatesPreferences,
        libraryPreferences = LibraryPreferences(emittingStore),
    )

    private suspend fun RecentsEngine.firstAssembly(): RecentsAssembled = assembled.filterNotNull().first()

    /**
     * The drawn rows, which is also what prunes the selection. Collecting the assembly instead would
     * miss it: the prune moved onto this flow precisely because the assembly is not what is on screen.
     */
    private suspend fun RecentsEngine.firstRendered(): RecentsRendered = rendered.filterNotNull().first()

    /**
     * The derived value once it has been computed. A flow shared while subscribed answers its seed
     * until something collects it, so reading `value` straight out asserts the seed rather than the
     * derivation, which is exactly how a false-expecting assertion here used to pass for free.
     */
    private fun <T> TestScope.settled(flow: StateFlow<T>): T {
        backgroundScope.launch { flow.collect { } }
        advanceUntilIdle()
        return flow.value
    }

    private fun item(entryId: EntryId, at: Long, lane: RecentsLane = RecentsLane.Added) =
        RecentsItem(entryId = entryId, timestamp = at, lane = lane, payload = Unit)

    private val manga1 = EntryId.Manga(1)
    private val manga2 = EntryId.Manga(2)
    private val novel1 = EntryId.Novel(1)
    private val category = Category(id = 3L, name = "Reading", order = 0L, flags = 0L)

    private fun duplicates(entry: EntryId) = RecentsDuplicates(
        duplicates = listOf(
            RecentsDuplicate(
                entry,
                EntryDuplicateCardUi(
                    id = entry.rawId,
                    coverModel = Unit,
                    title = "already there",
                    author = null,
                    artist = null,
                    status = 0L,
                    source = EntrySourceLabel.Installed("a source"),
                    chapterCount = 0L,
                ),
            ),
        ),
        groupIdByRawId = emptyMap(),
        suggestGroup = false,
    )

    /**
     * The two preference names are crossed, so binding them the obvious way puts each swipe on the
     * wrong side, silently and identically on both content types. Reading `value` is the assertion
     * here rather than a shortcut: the seed is what `get()` resolved, which is the binding itself.
     */
    @Test
    fun `the start side runs the preference named for the end`() = runTest {
        libraryPreferences.swipeToEndAction.set(LibraryPreferences.ChapterSwipeAction.Download)
        libraryPreferences.swipeToStartAction.set(LibraryPreferences.ChapterSwipeAction.ToggleBookmark)

        val engine = engine(listOf(provider(ContentType.MANGA)))

        engine.swipeActions.value shouldBe RecentsSwipeActions(
            start = LibraryPreferences.ChapterSwipeAction.Download,
            end = LibraryPreferences.ChapterSwipeAction.ToggleBookmark,
        )
    }

    @Test
    fun `both content types assemble into one feed, newest first`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = rows(item(novel1, at = 200))),
            ),
        )

        engine.firstAssembly().items.map { it.entryId } shouldContainExactly listOf(novel1, manga1)
    }

    @Test
    fun `the chip selects whose rows assemble`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = rows(item(novel1, at = 200))),
            ),
            chip = ContentType.MANGA,
        )

        engine.firstAssembly().items.map { it.entryId } shouldContainExactly listOf(manga1)
    }

    @Test
    fun `the assembly is tagged with the chip that selected it`() = runTest {
        val engine = engine(listOf(provider(ContentType.NOVELS)), chip = ContentType.NOVELS)

        engine.firstAssembly().chip shouldBe ContentType.NOVELS
    }

    @Test
    fun `an unloaded lane on a hidden content type does not hold the visible one`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = RecentsLaneRows.Loading),
            ),
            chip = ContentType.MANGA,
        )

        engine.firstAssembly().loading shouldBe false
    }

    @Test
    fun `an unloaded lane does hold a view that shows it`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updated = rows(item(manga1, at = 100))),
                provider(ContentType.NOVELS, updated = RecentsLaneRows.Loading),
            ),
        )

        engine.firstAssembly().loading shouldBe true
    }

    @Test
    fun `only the lanes this surface renders are collected`() = runTest {
        val readRow = item(manga2, at = 300, lane = RecentsLane.Read(ChapterRef(manga2, chapterId = 1)))
        val engine = engine(
            listOf(provider(ContentType.MANGA, read = rows(readRow))),
            modes = setOf(RecentsMode.UPDATES),
        )

        engine.firstAssembly().items shouldContainExactly emptyList()
    }

    @Test
    fun `the last updated line is the newer library under All`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updatedAt = 5),
                provider(ContentType.NOVELS, updatedAt = 9),
            ),
        )

        engine.lastUpdated.first { it != 0L } shouldBe 9
    }

    @Test
    fun `a running update behind the chip shows as refreshing`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updating = false),
                provider(ContentType.NOVELS, updating = true),
            ),
        )

        engine.refreshing.first { it } shouldBe true
    }

    @Test
    fun `an update on the content type the chip hides is not this surface's refresh`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updating = false),
                provider(ContentType.NOVELS, updating = true),
            ),
            chip = ContentType.MANGA,
        )

        settled(engine.refreshing) shouldBe false
    }

    @Test
    fun `the last updated line ignores the content type the chip hides`() = runTest {
        val engine = engine(
            listOf(
                provider(ContentType.MANGA, updatedAt = 5),
                provider(ContentType.NOVELS, updatedAt = 9),
            ),
            chip = ContentType.MANGA,
        )

        engine.lastUpdated.first { it != 0L } shouldBe 5
    }

    @Test
    fun `a provider flow is not opened until something collects what it feeds`() = runTest {
        val provider = provider(ContentType.MANGA, updating = true)
        val engine = engine(listOf(provider))

        // Building a derived flow is not collecting it. Shared eagerly, this would open the provider's
        // flow here and hold it for as long as the surface's engine lived. The other direction, that
        // collecting does open it, is what every derived-value test above already reads. Pinned on
        // refreshing rather than the assembly, whose upstream runs off the test scheduler.
        engine.refreshing
        advanceUntilIdle()

        provider.updatingSubscriptions shouldBe 0
    }

    // Merge membership, pinned here rather than on the kernel: whether merging-off reaches the feed as
    // an empty map is the clause that can actually break, and it lives on this side of the seam.

    private fun mergeManager(memberships: Map<Long, Long>): MergeManager {
        val manager = mockk<MergeManager>(relaxed = true)
        every { manager.membershipChanges() } returns flowOf(memberships)
        return manager
    }

    private fun mergingPreference(enabled: Boolean): Preference<Boolean> {
        val preference = mockk<Preference<Boolean>>(relaxed = true)
        every { preference.changes() } returns flowOf(enabled)
        return preference
    }

    @Test
    fun `memberships arrive keyed by entry so the two content types cannot cross`() = runTest {
        val flow = mergeManager(mapOf(1L to 7L)).membershipFlow(mergingPreference(true), EntryId::Novel)

        flow.first() shouldBe mapOf(EntryId.Novel(1) to 7L)
    }

    @Test
    fun `with merging off the feed is handed no groups at all`() = runTest {
        val flow = mergeManager(mapOf(1L to 7L)).membershipFlow(mergingPreference(false), EntryId::Manga)

        flow.first() shouldBe emptyMap()
    }

    // Search, selection and the verbs: the values the two replaced screens each stored twice.

    private fun ref(entryId: EntryId, chapterId: Long) = ChapterRef(entryId, chapterId)

    @Test
    fun `a search keeps only the rows whose displayed title matches`() = runTest {
        val engine = engine(
            listOf(
                provider(
                    ContentType.MANGA,
                    updated = rows(item(manga1, at = 100), item(manga2, at = 90)),
                    titles = mapOf(manga1 to "Dandadan", manga2 to "Berserk"),
                ),
            ),
        )
        engine.search("dan")

        engine.firstAssembly().items.map { it.entryId } shouldContainExactly listOf(manga1)
    }

    @Test
    fun `an empty query leaves every row`() = runTest {
        val engine = engine(
            listOf(
                provider(
                    ContentType.MANGA,
                    updated = rows(item(manga1, at = 100), item(manga2, at = 90)),
                    titles = mapOf(manga1 to "Dandadan", manga2 to "Berserk"),
                ),
            ),
        )
        engine.search("")

        engine.firstAssembly().items.size shouldBe 2
    }

    @Test
    fun `a range selection spans both content types in the rendered order`() {
        val engine = engine(listOf(provider(ContentType.MANGA), provider(ContentType.NOVELS)))
        val ordered = listOf(ref(manga1, 1), ref(novel1, 2), ref(manga2, 3))

        engine.toggleSelection(ordered.first())
        engine.toggleRangeSelection(ordered.last(), ordered)

        engine.selection.value shouldContainExactlyInAnyOrder ordered
    }

    @Test
    fun `a long press on an already-selected row drops it, as it does everywhere else`() {
        val engine = engine(listOf(provider(ContentType.MANGA)))
        val ordered = listOf(ref(manga1, 1), ref(manga2, 2))

        engine.toggleSelection(ordered.first())
        engine.toggleRangeSelection(ordered.last(), ordered)
        engine.toggleRangeSelection(ordered.last(), ordered)

        engine.selection.value shouldContainExactly listOf(ordered.first())
    }

    @Test
    fun `a range with no anchor selects only the row that was pressed`() {
        val engine = engine(listOf(provider(ContentType.MANGA)))
        val ordered = listOf(ref(manga1, 1), ref(manga2, 2))

        engine.toggleRangeSelection(ordered.last(), ordered)

        engine.selection.value shouldContainExactly listOf(ordered.last())
    }

    @Test
    fun `inverting swaps selected for unselected across the rendered order`() {
        val engine = engine(listOf(provider(ContentType.MANGA), provider(ContentType.NOVELS)))
        val ordered = listOf(ref(manga1, 1), ref(novel1, 2))

        engine.toggleSelection(ordered.first())
        engine.invertSelection(ordered)

        engine.selection.value shouldContainExactly listOf(ordered.last())
    }

    @Test
    fun `a selected chapter the feed no longer holds leaves the selection`() = runTest {
        val chapter = ref(manga2, 3)
        val engine = engine(
            listOf(
                provider(
                    ContentType.MANGA,
                    updated = rows(
                        item(manga1, at = 100, lane = RecentsLane.Updated(ref(manga1, 1))),
                        item(manga2, at = 90, lane = RecentsLane.Updated(chapter)),
                    ),
                    titles = mapOf(manga1 to "Dandadan", manga2 to "Berserk"),
                ),
            ),
        )
        engine.toggleSelection(chapter)
        engine.search("dan")
        engine.firstRendered()

        engine.selection.value shouldBe emptySet()
    }

    /**
     * The cap is the digest's whole shape, so a row can stop being drawn while nothing about it
     * changed: an arriving update burst takes the read section's budget. Selected, it would have kept
     * counting in the toolbar and kept being acted on, with nothing on screen to say so.
     */
    @Test
    fun `a selected row a section cap dropped leaves the selection`() = runTest {
        val items = (1..12).map { n ->
            val entry = EntryId.Manga(n.toLong())
            item(entry, at = 100L - n, lane = RecentsLane.Read(ChapterRef(entry, chapterId = n.toLong())))
        }
        val engine = engine(
            listOf(provider(ContentType.MANGA, read = rows(*items.toTypedArray()))),
            modes = setOf(RecentsMode.DIGEST),
        )
        val drawn = ref(items.first().entryId, chapterId = 1)
        val capped = ref(items.last().entryId, chapterId = 12)
        engine.toggleSelection(drawn)
        engine.toggleSelection(capped)

        engine.firstRendered()

        engine.selection.value shouldContainExactly listOf(drawn)
    }

    @Test
    fun `a selected row the chapter filter hides leaves the selection`() = runTest {
        emittingUpdatesPreferences.filterBookmarked.set(TriState.ENABLED_IS)
        val kept = EntryId.Manga(1)
        val hidden = EntryId.Manga(2)
        val engine = emittingEngine(
            provider(
                ContentType.MANGA,
                read = rows(
                    item(kept, at = 20, lane = RecentsLane.Read(ChapterRef(kept, 1))),
                    item(hidden, at = 10, lane = RecentsLane.Read(ChapterRef(hidden, 2))),
                ),
                states = mapOf(
                    kept to RecentsChapterState(read = false, bookmark = true, progress = null),
                    hidden to RecentsChapterState(read = false, bookmark = false, progress = null),
                ),
                // Both still have something to read, so the show-read gate stands aside and the
                // chapter filter is what decides, which is what this test is about.
                unreadEntries = setOf(kept, hidden),
            ),
            modes = setOf(RecentsMode.FEED),
        )
        engine.toggleSelection(ref(kept, 1))
        engine.toggleSelection(ref(hidden, 2))

        engine.firstRendered()

        engine.selection.value shouldContainExactly listOf(ref(kept, 1))
    }

    @Test
    fun `select all takes every row the surface draws`() {
        val engine = engine(listOf(provider(ContentType.MANGA)))
        val ordered = listOf(ref(manga1, 1), ref(novel1, 2))

        engine.selectAll(ordered)

        engine.selection.value shouldContainExactlyInAnyOrder ordered
    }

    @Test
    fun `a bulk action reaches every content type on screen and clears the selection`() {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)
        val engine = engine(listOf(manga, novel))
        val chapter = ref(manga1, 1)

        engine.toggleSelection(chapter)
        engine.markReadSelection(setOf(chapter), read = true)

        manga.markedRead shouldBe setOf(chapter)
        novel.markedRead shouldBe setOf(chapter)
        engine.selection.value shouldBe emptySet()
    }

    @Test
    fun `a bulk action passes over a provider that acts on no chapters`() {
        val acting = provider(ContentType.MANGA)
        val inert = provider(ContentType.NOVELS, actsOnChapters = false)
        val engine = engine(listOf(acting, inert))
        val chapter = ref(manga1, 1)

        engine.toggleSelection(chapter)
        engine.markReadSelection(setOf(chapter), read = true)

        acting.markedRead shouldBe setOf(chapter)
        inert.markedRead shouldBe null
    }

    @Test
    fun `removing one read record reaches only the provider that owns the entry`() {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)
        val engine = engine(listOf(manga, novel))
        val row = item(novel1, at = 1)

        engine.removeHistoryRecord(row)

        novel.removedRecord shouldBe row
        manga.removedRecord shouldBe null
    }

    @Test
    fun `switching mode drops a selection the new mode need not show`() {
        val engine = engine(
            listOf(provider(ContentType.MANGA)),
            modes = setOf(RecentsMode.UPDATES, RecentsMode.HISTORY),
        )
        engine.toggleSelection(ref(manga1, 1))

        engine.setMode(RecentsMode.HISTORY)

        engine.selection.value shouldBe emptySet()
    }

    // The lane decides the reader's scope, and the pairing is what keeps a launch openable: only the
    // updated lane resolves its target inside the row's own source.

    @Test
    fun `the updated lane opens inside the row's own source`() {
        RecentsLane.Updated(ref(manga1, 1)).sourceScoped shouldBe true
    }

    @Test
    fun `the lanes that resolve over the merge group do not open source-scoped`() {
        listOf(RecentsLane.Read(ref(manga1, 1)), RecentsLane.Added)
            .map { it.sourceScoped } shouldContainExactly listOf(false, false)
    }

    @Test
    fun `each single-lane mode opens only its own feed`() {
        RecentsMode.UPDATES.lanes shouldBe setOf(RecentsLaneKind.UPDATED)
        RecentsMode.HISTORY.lanes shouldBe setOf(RecentsLaneKind.READ)
    }

    @Test
    fun `a combined mode opens every feed`() {
        RecentsMode.FEED.lanes shouldBe RecentsLaneKind.entries.toSet()
    }

    @Test
    fun `a row's own download control carries its action and spares the selection`() {
        val provider = provider(ContentType.MANGA)
        val engine = engine(listOf(provider))
        val selected = ref(manga2, 2)
        val row = ref(manga1, 1)
        engine.toggleSelection(selected)

        engine.download(setOf(row), ChapterDownloadAction.START_NOW)

        provider.downloaded shouldBe (setOf(row) to ChapterDownloadAction.START_NOW)
        engine.selection.value shouldContainExactly listOf(selected)
    }

    @Test
    fun `flipping the chip drops a selection it may now hide`() {
        val engine = engine(listOf(provider(ContentType.MANGA)))
        engine.toggleSelection(ref(manga1, 1))

        engine.setContentType(ContentType.NOVELS)

        engine.selection.value shouldBe emptySet()
    }

    /**
     * History takes a selection because every verb behind one acts on the chapter a row names, which
     * a history row has. It does not take grouping (no burst to group) or the chapter-state filters
     * (those preferences are the Updates view's, and obeying them here would narrow this feed with
     * nothing on screen saying so).
     */
    @Test
    fun `History takes a selection and nothing aimed at an update burst`() {
        RecentsMode.HISTORY.capabilities shouldBe setOf(RecentsCapability.SELECTION)
    }

    /**
     * One line for every view is how History came to report itself as having no recent updates. The
     * lanes answer it, so a view of one lane names that lane and a mixed one can only speak of activity.
     */
    @Test
    fun `an empty view names the lanes it draws`() {
        RECENTS_MODE_ORDER.map { it.emptyRes } shouldContainExactly listOf(
            MR.strings.information_no_recent_activity,
            MR.strings.information_no_recent_activity,
            MR.strings.information_no_recent_manga,
            MR.strings.information_no_recent,
        )
    }

    @Test
    fun `the grouping toggle belongs to the Updates mode alone`() {
        RecentsMode.entries.filter { it.can(RecentsCapability.GROUPING) } shouldContainExactly
            listOf(RecentsMode.UPDATES)
    }

    @Test
    fun `a refresh that starts one library reports a start, not an already-running`() {
        val manga = provider(ContentType.MANGA, refreshStarts = false)
        val novel = provider(ContentType.NOVELS, refreshStarts = true)

        engine(listOf(manga, novel)).refresh() shouldBe true
    }

    @Test
    fun `a refresh reaches the second library even when the first already started`() {
        val manga = provider(ContentType.MANGA, refreshStarts = true)
        val novel = provider(ContentType.NOVELS, refreshStarts = false)

        engine(listOf(manga, novel)).refresh()

        novel.refreshed shouldBe true
    }

    @Test
    fun `a refresh under one chip leaves the other library alone`() {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel), chip = ContentType.MANGA).refresh()

        novel.refreshed shouldBe false
    }

    @Test
    fun `clearing history reaches both content types under All`() = runTest {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel)).clearHistory()

        (manga.historyCleared to novel.historyCleared) shouldBe (true to true)
    }

    @Test
    fun `clearing history under one chip spares the other content type`() = runTest {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel), chip = ContentType.MANGA).clearHistory()

        novel.historyCleared shouldBe false
    }

    /**
     * The shell announces the wipe, so it is owed the truth about it. A clear that reached nothing used
     * to report itself as done, which is the one message about a wipe the user cannot check.
     */
    @Test
    fun `a clear that nothing managed reports no clear`() = runTest {
        val manga = provider(ContentType.MANGA, historyClears = false)
        val novel = provider(ContentType.NOVELS, historyClears = false)

        engine(listOf(manga, novel)).clearHistory() shouldBe false
    }

    @Test
    fun `a clear one content type managed is a clear`() = runTest {
        val manga = provider(ContentType.MANGA, historyClears = false)
        val novel = provider(ContentType.NOVELS, historyClears = true)

        engine(listOf(manga, novel)).clearHistory() shouldBe true
    }

    // The chapter-state filters over the lanes no query filters. The updated lane is filtered in SQL,
    // so only the read lane is judged here, and only where the mode draws the controls.

    private val unreadOnly = RecentsChapterFilters(unread = TriState.ENABLED_IS)

    private fun readRow(entryId: EntryId) =
        item(entryId, at = 100, lane = RecentsLane.Read(ChapterRef(entryId, chapterId = 1)))

    private fun readState(read: Boolean = false, bookmark: Boolean = false) =
        RecentsChapterState(read = read, bookmark = bookmark, progress = null)

    @Test
    fun `a read row failing a filter is not drawn`() {
        val provider = provider(ContentType.MANGA, states = mapOf(manga1 to readState(read = true)))

        engine(listOf(provider)).showsRow(readRow(manga1), gate(unreadOnly), RecentsMode.HISTORY) shouldBe false
    }

    @Test
    fun `a read row matching a filter is drawn`() {
        val provider = provider(ContentType.MANGA, states = mapOf(manga1 to readState()))

        engine(listOf(provider)).showsRow(readRow(manga1), gate(unreadOnly), RecentsMode.HISTORY) shouldBe true
    }

    /**
     * Judging it here would filter it twice, against a rule written twice: its own query already
     * applied these, and the row that survived that is the row the feed is meant to show.
     */
    @Test
    fun `an updated row is left to the query that already filtered it`() {
        val row = item(manga1, at = 100, lane = RecentsLane.Updated(ChapterRef(manga1, 1)))
        val provider = provider(ContentType.MANGA, states = mapOf(manga1 to readState(read = true)))

        engine(listOf(provider)).showsRow(row, gate(unreadOnly), RecentsMode.HISTORY) shouldBe true
    }

    /**
     * The lane is what spares it, which is why the row is given a chapter state here: without one it
     * would survive for having nothing to answer with, and the rule this pins would go untested.
     */
    @Test
    fun `a newly added row is not judged by a question about chapters`() {
        val provider = provider(ContentType.MANGA, states = mapOf(manga1 to readState(read = true)))

        engine(listOf(provider))
            .showsRow(item(manga1, at = 100), gate(unreadOnly), RecentsMode.HISTORY) shouldBe true
    }

    @Test
    fun `the downloaded filter reads the row's own download state`() {
        val downloaded = RecentsChapterFilters(downloaded = TriState.ENABLED_IS)
        val provider = provider(
            ContentType.MANGA,
            states = mapOf(manga1 to readState(), manga2 to readState()),
            downloadedEntries = setOf(manga1),
        )
        val engine = engine(listOf(provider))

        val show = { entry: EntryId ->
            engine.showsRow(readRow(entry), gate(downloaded), RecentsMode.HISTORY)
        }
        (show(manga1) to show(manga2)) shouldBe (true to false)
    }

    /**
     * The four preferences are shared with the Updates mode, so a mode drawing no control for them must
     * filter by none: obeying a filter with nothing on screen saying so is a feed silently narrowed.
     */
    @Test
    fun `a mode that offers no chapter filters is judged by none`() = runTest {
        emittingUpdatesPreferences.filterUnread.set(TriState.ENABLED_IS)
        val engine = multiModeEngine(setOf(RecentsMode.UPDATES, RecentsMode.HISTORY))

        settled(engine.chapterFilters).isActive shouldBe true

        engine.setMode(RecentsMode.HISTORY)

        settled(engine.chapterFilters) shouldBe RecentsChapterFilters.NONE
    }

    /**
     * The rule below is pinned as a pure function, but which lanes reach it is the engine's call, and
     * a surface rendering several modes always has the updated lane in one of them. Asking about the
     * surface rather than the mode on screen reports a history feed as filtered by a filter that
     * cannot touch it.
     */
    @Test
    fun `a chapter state filter marks only the mode whose lane it can reach`() = runTest {
        emittingUpdatesPreferences.filterUnread.set(TriState.ENABLED_IS)
        val engine = multiModeEngine(setOf(RecentsMode.UPDATES, RecentsMode.HISTORY))

        settled(engine.filterActive) shouldBe true

        engine.setMode(RecentsMode.HISTORY)

        settled(engine.filterActive) shouldBe false
    }

    @Test
    fun `a chapter state filter marks a view that offers those filters`() {
        recentsFilterActive(
            byCategory = false,
            byChapterState = true,
            byScanlator = false,
            chipShowsManga = true,
            mode = RecentsMode.UPDATES,
        ) shouldBe true
    }

    @Test
    fun `a chapter state filter does not mark a view that offers none`() {
        recentsFilterActive(
            byCategory = false,
            byChapterState = true,
            byScanlator = false,
            chipShowsManga = true,
            mode = RecentsMode.HISTORY,
        ) shouldBe false
    }

    @Test
    fun `a category filter marks every view`() {
        recentsFilterActive(
            byCategory = true,
            byChapterState = false,
            byScanlator = false,
            chipShowsManga = true,
            mode = RecentsMode.HISTORY,
        ) shouldBe true
    }

    @Test
    fun `excluded scanlators mark a manga feed that offers the chapter filters`() {
        recentsFilterActive(
            byCategory = false,
            byChapterState = false,
            byScanlator = true,
            chipShowsManga = true,
            mode = RecentsMode.UPDATES,
        ) shouldBe true
    }

    @Test
    fun `excluded scanlators do not mark a feed with no manga behind it`() {
        recentsFilterActive(
            byCategory = false,
            byChapterState = false,
            byScanlator = true,
            chipShowsManga = false,
            mode = RecentsMode.UPDATES,
        ) shouldBe false
    }

    /**
     * The rule and the filtering must ask one question. They agree on all four views today, so this
     * is what would catch them diverging: a view that judged rows it reported as unfiltered would
     * hide entries with nothing on screen saying why.
     */
    @Test
    fun `every view that judges rows says it is filtered`() {
        RecentsMode.entries.map {
            recentsFilterActive(
                byCategory = false,
                byChapterState = true,
                byScanlator = false,
                chipShowsManga = true,
                mode = it,
            )
        } shouldContainExactly RecentsMode.entries.map { it.can(RecentsCapability.CHAPTER_FILTER) }
    }

    @Test
    fun `an add runs through the provider that owns the entry's content type`() = runTest {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel)).startAdd(manga1)

        (manga.addedEntry to novel.addedEntry) shouldBe (manga1 to null)
    }

    @Test
    fun `an entry already in the library is not added again`() = runTest {
        val manga = provider(ContentType.MANGA, decision = AddDecision.Remove)

        engine(listOf(manga)).startAdd(manga1)

        manga.addedEntry shouldBe null
    }

    @Test
    fun `an entry that has gone is not added`() = runTest {
        val manga = provider(ContentType.MANGA, decision = null)

        engine(listOf(manga)).startAdd(manga1)

        manga.addedEntry shouldBe null
    }

    @Test
    fun `a possible duplicate is asked about instead of added`() = runTest {
        val duplicates = duplicates(novel1)
        val manga = provider(ContentType.MANGA, decision = AddDecision.ConfirmDuplicate(duplicates))
        val engine = engine(listOf(manga))

        engine.startAdd(manga1)

        engine.dialog.value shouldBe RecentsDialog.Duplicate(manga1, duplicates)
        manga.addedEntry shouldBe null
    }

    @Test
    fun `an add with no usable default opens the picker the provider answered with`() = runTest {
        val selection = listOf(CheckboxState.State.None(category))
        val manga = provider(ContentType.MANGA, addResult = AddFavoriteResult.NeedsCategoryChoice(selection))
        val engine = engine(listOf(manga))

        engine.startAdd(manga1)

        engine.dialog.value shouldBe RecentsDialog.ChangeCategory(manga1, selection)
    }

    @Test
    fun `adding to a group still asks for categories when the group has none`() = runTest {
        val selection = listOf(CheckboxState.State.None(category))
        val manga = provider(ContentType.MANGA, addResult = AddFavoriteResult.NeedsCategoryChoice(selection))
        val engine = engine(listOf(manga))

        engine.groupAdd(manga1, listOf(manga2))

        manga.groupedWith shouldBe listOf(manga2)
        engine.dialog.value shouldBe RecentsDialog.ChangeCategory(manga1, selection)
    }

    @Test
    fun `a surface comes back to the mode it was left on`() = runTest {
        sourcePreferences.recentsMode.set(RecentsMode.HISTORY)

        val engine = engine(
            listOf(provider(ContentType.MANGA)),
            modes = setOf(RecentsMode.UPDATES, RecentsMode.HISTORY),
        )

        engine.mode.value shouldBe RecentsMode.HISTORY
    }

    /**
     * One key serves every surface, so a stored mode routinely names something the surface does not
     * draw. Obeying it would leave a single-mode tab rendering nothing it has an engine for; the same
     * filter is what absorbs a mode that has since been removed.
     */
    @Test
    fun `a stored mode this surface does not render is ignored`() = runTest {
        sourcePreferences.recentsMode.set(RecentsMode.DIGEST)

        val engine = engine(listOf(provider(ContentType.MANGA)), modes = setOf(RecentsMode.UPDATES))

        engine.mode.value shouldBe RecentsMode.UPDATES
    }

    @Test
    fun `switching mode is remembered for next time`() = runTest {
        val engine = engine(
            listOf(provider(ContentType.MANGA)),
            modes = setOf(RecentsMode.UPDATES, RecentsMode.HISTORY),
        )

        engine.setMode(RecentsMode.HISTORY)

        sourcePreferences.recentsMode.get() shouldBe RecentsMode.HISTORY
    }

    /**
     * Resume used to compare both types whatever the chip was, so the Novels chip could open a manga.
     * The chip decides which providers are asked at all, which is why it cannot come back.
     */
    @Test
    fun `resume opens the newest read of the type the chip is showing`() = runTest {
        val mangaRead = item(manga1, at = 300, lane = RecentsLane.Read(ChapterRef(manga1, 11)))
        val novelRead = item(novel1, at = 100, lane = RecentsLane.Read(ChapterRef(novel1, 22)))
        val manga = provider(ContentType.MANGA, latestRead = mangaRead)
        val novel = provider(ContentType.NOVELS, latestRead = novelRead)

        engine(listOf(manga, novel), chip = ContentType.NOVELS).resumeLatest()

        (manga.openedItem to novel.openedItem) shouldBe (null to novelRead)
    }

    @Test
    fun `resume under All opens whichever type was read last`() = runTest {
        val mangaRead = item(manga1, at = 300, lane = RecentsLane.Read(ChapterRef(manga1, 11)))
        val novelRead = item(novel1, at = 100, lane = RecentsLane.Read(ChapterRef(novel1, 22)))
        val manga = provider(ContentType.MANGA, latestRead = mangaRead)
        val novel = provider(ContentType.NOVELS, latestRead = novelRead)

        engine(listOf(manga, novel)).resumeLatest()

        (manga.openedItem to novel.openedItem) shouldBe (mangaRead to null)
    }

    @Test
    fun `resume with nothing read anywhere opens nothing`() = runTest {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel)).resumeLatest() shouldBe null

        (manga.openedItem to novel.openedItem) shouldBe (null to null)
    }

    @Test
    fun `the picker's confirm files through the provider that owns the entry`() = runTest {
        val manga = provider(ContentType.MANGA)
        val novel = provider(ContentType.NOVELS)

        engine(listOf(manga, novel)).fileAddCategories(novel1, listOf(3L))

        (manga.filedCategories to novel.filedCategories) shouldBe (null to (novel1 to listOf(3L)))
    }

    // The continue-reading row: which rows pay for a target, and what the verbs then act on.

    private fun readRow(entry: EntryId, chapterId: Long, at: Long = 100) =
        item(entry, at = at, lane = RecentsLane.Read(ChapterRef(entry, chapterId)))

    /** Feed, so the read lane renders and the row is combined-mode; the fake's rows carry the states. */
    private fun feedEngine(provider: RecentsProvider) =
        engine(listOf(provider), chip = ContentType.MANGA, modes = setOf(RecentsMode.FEED))

    @Test
    fun `a read record still unread resolves nothing, since it resumes itself`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val engine = feedEngine(
            provider(ContentType.MANGA, read = rows(row), states = mapOf(manga1 to readState(read = false))),
        )

        engine.resolvesTarget(row, RecentsMode.FEED, membership = emptyMap()) shouldBe false
    }

    @Test
    fun `a merged entry resolves even where its own record reads as unread`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val engine = feedEngine(
            provider(ContentType.MANGA, read = rows(row), states = mapOf(manga1 to readState(read = false))),
        )

        engine.resolvesTarget(row, RecentsMode.FEED, membership = mapOf(manga1 to 7L)) shouldBe true
    }

    @Test
    fun `History resolves nothing, because its rows are about the record`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val engine = feedEngine(
            provider(ContentType.MANGA, read = rows(row), states = mapOf(manga1 to readState(read = true))),
        )

        engine.resolvesTarget(row, RecentsMode.HISTORY, membership = emptyMap()) shouldBe false
    }

    @Test
    fun `an updated row resolves nothing, whatever its state`() = runTest {
        val row = item(manga1, at = 100, lane = RecentsLane.Updated(ChapterRef(manga1, 5)))
        val engine = feedEngine(
            provider(ContentType.MANGA, updated = rows(row), states = mapOf(manga1 to readState(read = true))),
        )

        engine.resolvesTarget(row, RecentsMode.FEED, membership = emptyMap()) shouldBe false
    }

    @Test
    fun `a resolved row is remembered, so a second reader pays nothing`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val target = targetRow(ref(manga1, 2), readState(read = false))
        val fake = provider(
            ContentType.MANGA,
            read = rows(row),
            states = mapOf(manga1 to readState(read = true)),
            targetRows = mapOf(ref(manga1, 5) to target),
        )
        val engine = feedEngine(fake)

        engine.targetRow(row)
        engine.targetRow(row)

        engine.targets.value shouldBe mapOf(ref(manga1, 5) to target)
        fake.targetRowResolutions shouldBe 1
    }

    /**
     * The memo hangs off the lane data, which only a live assembly runs. Without a collector the two
     * tests below pass on a memo nothing ever reached, which is how a clear on the wrong flow would
     * have gone unnoticed.
     */
    private suspend fun TestScope.resolvedFeed(
        fake: FakeRecentsProvider,
        row: RecentsItem,
    ): RecentsEngine {
        val engine = feedEngine(fake)
        backgroundScope.launch { engine.rendered.collect { } }
        // Awaited, not advanced: the first lane emission empties the memo on its own dispatcher, and
        // resolving before it lands leaves the test asserting against a clear that has yet to happen.
        engine.assembled.filterNotNull().first()
        engine.targetRow(row)
        return engine
    }

    private fun resolvingProvider(row: RecentsItem, target: RecentsTargetRow) = provider(
        ContentType.MANGA,
        read = rows(row),
        states = mapOf(manga1 to readState(read = true)),
        targetRows = mapOf(ref(manga1, 5) to target),
    )

    /**
     * The assembly runs on its own dispatcher, so virtual time says nothing about whether an emission
     * has been through it. Awaiting the emission itself is what makes an assertion about the memo land
     * after the clear rather than racing it.
     */
    private suspend fun RecentsEngine.assemblyWhere(predicate: (RecentsAssembled) -> Boolean) {
        assembled.filterNotNull().first(predicate)
    }

    @Test
    fun `a search keystroke leaves the resolved rows alone`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val target = targetRow(ref(manga1, 2), readState(read = false))
        val engine = resolvedFeed(resolvingProvider(row, target), row)

        engine.search("nothing this row is called")
        engine.assemblyWhere { it.items.isEmpty() }

        engine.targets.value shouldBe mapOf(ref(manga1, 5) to target)
    }

    @Test
    fun `a lane re-emission drops the resolved rows, because the target may now be read`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val fake = resolvingProvider(row, targetRow(ref(manga1, 2), readState(read = false)))
        val engine = resolvedFeed(fake, row)

        fake.readLaneRows.value = rows(readRow(manga1, chapterId = 5, at = 200))
        engine.assemblyWhere { assembly -> assembly.items.any { it.timestamp == 200L } }

        engine.targets.value shouldBe emptyMap()
    }

    @Test
    fun `switching to History drops the resolved rows`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val engine = engine(
            listOf(
                provider(
                    ContentType.MANGA,
                    read = rows(row),
                    states = mapOf(manga1 to readState(read = true)),
                    targetRows = mapOf(ref(manga1, 5) to targetRow(ref(manga1, 2), readState())),
                ),
            ),
            chip = ContentType.MANGA,
            modes = setOf(RecentsMode.FEED, RecentsMode.HISTORY),
        )
        engine.setMode(RecentsMode.FEED)
        engine.targetRow(row)

        engine.setMode(RecentsMode.HISTORY)

        engine.targets.value shouldBe emptyMap()
    }

    @Test
    fun `a bulk verb acts on the chapter a continue-reading row names`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val engine = feedEngine(
            provider(
                ContentType.MANGA,
                read = rows(row),
                states = mapOf(manga1 to readState(read = true)),
                targetRows = mapOf(ref(manga1, 5) to targetRow(ref(manga1, 2), readState())),
            ),
        )

        engine.actingChapters(listOf(row), RecentsMode.FEED, membership = emptyMap()) shouldBe
            setOf(ref(manga1, 2))
    }

    @Test
    fun `a selection mixing lanes maps only the rows that name a target`() = runTest {
        val read = readRow(manga1, chapterId = 5)
        val updated = item(manga2, at = 50, lane = RecentsLane.Updated(ChapterRef(manga2, 9)))
        val engine = feedEngine(
            provider(
                ContentType.MANGA,
                read = rows(read),
                updated = rows(updated),
                states = mapOf(manga1 to readState(read = true), manga2 to readState(read = true)),
                targetRows = mapOf(
                    ref(manga1, 5) to targetRow(ref(manga1, 2), readState()),
                    ref(manga2, 9) to targetRow(ref(manga2, 3), readState()),
                ),
            ),
        )

        engine.actingChapters(listOf(read, updated), RecentsMode.FEED, membership = emptyMap()) shouldBe
            setOf(ref(manga1, 2), ref(manga2, 9))
    }

    @Test
    fun `a row whose target is its own record acts on that record`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val engine = feedEngine(
            provider(
                ContentType.MANGA,
                read = rows(row),
                states = mapOf(manga1 to readState(read = true)),
                targetRows = mapOf(ref(manga1, 5) to targetRow(ref(manga1, 5), readState())),
            ),
        )

        engine.actingChapters(listOf(row), RecentsMode.FEED, membership = emptyMap()) shouldBe
            setOf(ref(manga1, 5))
    }

    @Test
    fun `a row with nothing left to open still acts on its own record`() = runTest {
        val row = readRow(manga1, chapterId = 5)
        val engine = feedEngine(
            provider(
                ContentType.MANGA,
                read = rows(row),
                states = mapOf(manga1 to readState(read = true)),
            ),
        )

        engine.actingChapters(listOf(row), RecentsMode.FEED, membership = emptyMap()) shouldBe
            setOf(ref(manga1, 5))
    }
}

private fun rows(vararg items: RecentsItem) = RecentsLaneRows(items.toList(), loaded = true)

private fun provider(
    type: ContentType,
    read: RecentsLaneRows = rows(),
    updated: RecentsLaneRows = rows(),
    added: RecentsLaneRows = rows(),
    updatedAt: Long = 0L,
    titles: Map<EntryId, String> = emptyMap(),
    refreshStarts: Boolean = true,
    updating: Boolean = false,
    decision: AddDecision<RecentsDuplicates>? = AddDecision.Add,
    addResult: AddFavoriteResult = AddFavoriteResult.Added,
    actsOnChapters: Boolean = true,
    latestRead: RecentsItem? = null,
    historyClears: Boolean = true,
    states: Map<EntryId, RecentsChapterState> = emptyMap(),
    downloadedEntries: Set<EntryId> = emptySet(),
    unreadEntries: Set<EntryId> = emptySet(),
    targetRows: Map<ChapterRef, RecentsTargetRow> = emptyMap(),
    membership: Map<EntryId, Long> = emptyMap(),
) = FakeRecentsProvider(
    type,
    read,
    updated,
    added,
    updatedAt,
    titles,
    refreshStarts,
    updating,
    decision,
    addResult,
    actsOnChapters,
    latestRead,
    historyClears,
    states,
    downloadedEntries,
    unreadEntries,
    targetRows,
    membership,
)

/** A resolved target row, carrying only what the engine and the bar read off one. */
internal fun targetRow(ref: ChapterRef, state: RecentsChapterState, downloaded: Boolean = false) =
    RecentsTargetRow(
        ref = ref,
        chapter = RecentsChapterUi.Number(ref.chapterId.toDouble()),
        state = state,
        download = RecentsDownloadUi(
            state = { if (downloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED },
            progress = RecentsDownloadProgress.Unsupported,
        ),
    )

/** The chapter-state filters alone, with the show-read rule open so only the filters are judged. */
private fun gate(filters: RecentsChapterFilters) =
    RecentsRowGate(filters = filters, showRead = true, unread = emptySet())

/** A provider with canned lanes, recording the verbs the engine dispatched to it. */
private class FakeRecentsProvider(
    override val contentType: ContentType,
    readRows: RecentsLaneRows,
    updatedRows: RecentsLaneRows,
    addedRows: RecentsLaneRows,
    updatedAt: Long,
    private val titles: Map<EntryId, String>,
    private val refreshStarts: Boolean,
    updating: Boolean,
    private val decision: AddDecision<RecentsDuplicates>?,
    private val addResult: AddFavoriteResult,
    actsOnChapters: Boolean,
    private val latestRead: RecentsItem?,
    private val historyClears: Boolean,
    private val states: Map<EntryId, RecentsChapterState>,
    private val downloadedEntries: Set<EntryId>,
    unread: Set<EntryId>,
    private val targetRows: Map<ChapterRef, RecentsTargetRow>,
    memberships: Map<EntryId, Long>,
) : RecentsProvider {

    var historyCleared = false
        private set
    var markedRead: Set<ChapterRef>? = null
        private set
    var removedRecord: RecentsItem? = null
        private set
    var downloaded: Pair<Set<ChapterRef>, ChapterDownloadAction>? = null
        private set
    var refreshed = false
        private set
    var addedEntry: EntryId? = null
        private set
    var filedCategories: Pair<EntryId, List<Long>>? = null
        private set
    var groupedWith: List<EntryId>? = null
        private set

    /** How many times the engine has opened this provider's update signal, which it pays to render. */
    var updatingSubscriptions = 0
        private set

    /** Mutable, so a test can re-emit the lane the way a chapter write does. */
    val readLaneRows = MutableStateFlow(readRows)

    override val readLane: Flow<RecentsLaneRows> = readLaneRows
    override val updatedLane: Flow<RecentsLaneRows> = flowOf(updatedRows)
    override val addedLane: Flow<RecentsLaneRows> = flowOf(addedRows)
    override val lastUpdated: Flow<Long> = flowOf(updatedAt)
    override val updating: Flow<Boolean> = flowOf(updating).onStart { updatingSubscriptions++ }
    override val membership: Flow<Map<EntryId, Long>> = flowOf(memberships)

    // Named apart from the property on purpose: a same-named constructor parameter reads back as the
    // property here, which is null while the object is still being built.
    override val unreadEntries: Flow<Set<EntryId>> = flowOf(unread)

    override fun rowUi(item: RecentsItem): RecentsRowUi =
        EMPTY_RECENTS_ROW.copy(title = titles[item.entryId].orEmpty(), state = states[item.entryId])

    override fun downloadUi(item: RecentsItem): RecentsDownloadUi = RecentsDownloadUi(
        state = { if (item.entryId in downloadedEntries) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED },
        progress = RecentsDownloadProgress.Unsupported,
    )

    override suspend fun targetChapter(item: RecentsItem): ChapterRef? = null

    /** How many times the engine paid for a resolution, which is what the memo is meant to bound. */
    var targetRowResolutions = 0
        private set

    override suspend fun targetRow(item: RecentsItem): RecentsTargetRow? {
        targetRowResolutions++
        return targetRows[item.lane.chapterRef]
    }

    /** What the engine asked this provider to open, which is how a resume test says who answered. */
    var openedItem: RecentsItem? = null
        private set

    override suspend fun open(item: RecentsItem): RecentsOpen? {
        openedItem = item
        return null
    }

    override suspend fun latestRead(): RecentsItem? = latestRead

    override val chapterActions: RecentsChapterActions? =
        if (actsOnChapters) {
            object : RecentsChapterActions {
                override suspend fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
                    markedRead = chapters
                }

                override suspend fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) = Unit

                override suspend fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction) {
                    downloaded = chapters to action
                }

                override suspend fun deleteDownloads(chapters: Set<ChapterRef>) = Unit
            }
        } else {
            null
        }

    override fun removeFromHistory(entries: Set<EntryId>) = Unit

    override fun removeHistoryRecord(item: RecentsItem) {
        removedRecord = item
    }

    override suspend fun addDecision(entry: EntryId): AddDecision<RecentsDuplicates>? = decision

    override suspend fun addToLibrary(entry: EntryId): AddFavoriteResult {
        addedEntry = entry
        return addResult
    }

    override suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>) {
        filedCategories = entry to categoryIds
    }

    override suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult {
        groupedWith = duplicates
        return addResult
    }

    override suspend fun clearHistory(): Boolean {
        historyCleared = true
        return historyClears
    }

    override fun refresh(): Boolean {
        refreshed = true
        return refreshStarts
    }

    override suspend fun detailsScreen(entry: EntryId): Screen? = null
}
