package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress

class ReaderEngineTest {

    // The engine shares the provider's flows in its own scope, which is the main one.
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun engine(provider: FakeReaderProvider = FakeReaderProvider()) = ReaderEngine(provider)

    @Test
    fun `nothing is raised to begin with`() {
        engine().dialog.value shouldBe null
    }

    @Test
    fun `raising a dialog shows it`() {
        val engine = engine()

        engine.openDialog(ReaderDialog.Settings)

        engine.dialog.value shouldBe ReaderDialog.Settings
    }

    /**
     * The single slot is the mechanism: the host raises Loading while a chapter loads, and it has to
     * take over from whatever the reader had open rather than stacking on top of it.
     */
    @Test
    fun `raising a second dialog replaces the first rather than stacking`() {
        val engine = engine()
        engine.openDialog(ReaderDialog.ChapterList)

        engine.openDialog(ReaderDialog.Loading)

        engine.dialog.value shouldBe ReaderDialog.Loading
    }

    @Test
    fun `dismissing clears the raised dialog`() {
        val engine = engine()
        engine.openDialog(ReaderDialog.ChapterList)

        engine.dismissDialog()

        engine.dialog.value shouldBe null
    }

    @Test
    fun `dismissing with nothing raised stays empty`() {
        val engine = engine()

        engine.dismissDialog()

        engine.dialog.value shouldBe null
    }

    /**
     * Page actions travel as the capability built for that page, so the engine hands back the same
     * instance it was given. Reading the page back out of the slot is what this replaces.
     */
    @Test
    fun `page actions keep the capability they were raised with`() {
        val engine = engine()
        val actions = RecordingPageActions()

        engine.openDialog(ReaderDialog.PageActions(actions))

        val raised = engine.dialog.value as ReaderDialog.PageActions
        raised.actions shouldBeSameInstanceAs actions
    }

    @Test
    fun `a page action reaches the capability it was raised with`() {
        val engine = engine()
        val actions = RecordingPageActions()
        engine.openDialog(ReaderDialog.PageActions(actions))

        (engine.dialog.value as ReaderDialog.PageActions).actions.share(copyToClipboard = true)

        actions.shared shouldBe true
    }

    /**
     * The chrome names the entry whichever content type is open, so the engine must pass the
     * provider's answer through rather than hold one of its own that could go stale.
     */
    @Test
    fun `the chrome comes from the provider`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        provider.chrome.value = ReaderChromeState("Some Novel", "Chapter 2")

        engine.chrome.value shouldBe ReaderChromeState("Some Novel", "Chapter 2")
    }

    /**
     * The bar used to render manga's stored selection in every session, so a novel reader offered
     * reading mode and crop borders. The engine asking the provider is what stops that.
     */
    @Test
    fun `the bottom bar buttons come from the provider`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        provider.bottomButtons.value = setOf("as", "th")

        engine.bottomButtons.value shouldBe setOf("as", "th")
    }

    /**
     * The bar's verbs used to act on the manga model whatever was open, so rotating in a novel
     * session wrote a flag on a manga that was not there.
     */
    @Test
    fun `rotating goes to the session rather than a model the host picked`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        engine.setOrientation(ReaderOrientation.PORTRAIT.flagValue)

        provider.orientation.value shouldBe ReaderOrientation.PORTRAIT.flagValue
        engine.orientation.value shouldBe ReaderOrientation.PORTRAIT.flagValue
    }

    @Test
    fun `keeping the screen on goes to the session too`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        engine.setKeepScreenOn(true)

        provider.keepScreenOn.value shouldBe true
        engine.keepScreenOn.value shouldBe true
    }

    /**
     * The scrub reaches the installed viewport carrying the session's own unit, so a percentage can
     * never arrive somewhere that would read it as a page number.
     */
    @Test
    fun `seeking moves the installed viewport`() {
        val engine = engine()
        val viewport = FakeViewport()
        engine.installViewport(viewport)

        engine.seek(ChapterProgress.Percent(4200L))

        viewport.sought shouldBe ChapterProgress.Percent(4200L)
    }

    @Test
    fun `seeking with no viewport installed does nothing`() {
        engine().seek(ChapterProgress.Percent(4200L))
    }

    /**
     * The viewer is told after the step, not before: a viewer that keeps one long view of several
     * chapters would otherwise be pointed at the chapter it is leaving.
     */
    @Test
    fun `stepping a chapter tells the session first and then the viewport`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)
        val viewport = FakeViewport()
        engine.installViewport(viewport)

        engine.nextChapter()

        provider.stepped shouldBe 1
        viewport.chapterSteps shouldBe 1
    }

    @Test
    fun `stepping back goes back`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        engine.previousChapter()

        provider.stepped shouldBe -1
    }

    @Test
    fun `no viewport is installed to begin with`() {
        engine().viewport.value shouldBe null
    }

    @Test
    fun `installing puts the viewport in the slot`() {
        val engine = engine()
        val viewport = FakeViewport()

        engine.installViewport(viewport)

        engine.viewport.value shouldBeSameInstanceAs viewport
    }

    /**
     * Losing this step leaks the whole previous view tree, and nothing fails loudly when it happens,
     * which is why the engine owns it rather than the call site.
     */
    @Test
    fun `replacing destroys the outgoing viewport`() {
        val engine = engine()
        val first = FakeViewport()
        engine.installViewport(first)

        engine.installViewport(FakeViewport())

        first.destroyed shouldBe true
    }

    @Test
    fun `destroying clears the slot as well as the viewport`() {
        val engine = engine()
        val installed = FakeViewport()
        engine.installViewport(installed)

        engine.destroyViewport()

        installed.destroyed shouldBe true
        engine.viewport.value shouldBe null
    }

    /**
     * The manga shape. A session that offers no continuous scroll still has to answer the bar, and
     * answering false is what leaves the button off rather than lit over nothing.
     */
    @Test
    fun `a session without auto-scroll reports it off`() {
        val engine = engine()

        engine.autoScroll shouldBe null
        engine.autoScrollEnabled.value shouldBe false
    }

    @Test
    fun `auto-scroll follows the session that offers it`() {
        val provider = FakeReaderProvider()
        val auto = FakeAutoScroll()
        provider.autoScrollSlot = auto
        val engine = engine(provider)

        auto.toggle()

        engine.autoScrollEnabled.value shouldBe true
    }

    /**
     * A scrub is an explicit position choice, and a running auto-scroll would carry the reader off it
     * within a frame, so the engine stops it before the viewport moves.
     */
    @Test
    fun `scrubbing stops a running auto-scroll`() {
        val provider = FakeReaderProvider()
        val auto = FakeAutoScroll()
        provider.autoScrollSlot = auto
        val engine = engine(provider)
        engine.installViewport(FakeViewport())
        auto.toggle()

        engine.seek(ChapterProgress.Percent(hundredths = 5000))

        engine.autoScrollEnabled.value shouldBe false
    }

    /** A session with no auto-scroll still scrubs, rather than the engine reaching through a null. */
    @Test
    fun `scrubbing a session without auto-scroll still moves the viewport`() {
        val engine = engine()
        val viewport = FakeViewport()
        engine.installViewport(viewport)

        engine.seek(ChapterProgress.Percent(hundredths = 5000))

        viewport.sought shouldBe ChapterProgress.Percent(hundredths = 5000)
    }
}

private class FakeAutoScroll : ReaderAutoScroll {
    override val enabled = MutableStateFlow(false)

    override fun toggle() {
        enabled.value = !enabled.value
    }

    override fun stop() {
        enabled.value = false
    }
}

/**
 * The engine only ever holds the provider for the host to build through, and no test here builds a
 * viewport, so this never has to answer. Building is the half that needs a real Activity.
 */
private class FakeReaderProvider : ReaderProvider {
    override val chrome = MutableStateFlow(ReaderChromeState())

    override val bottomButtons = MutableStateFlow(emptySet<String>())

    override val orientation = MutableStateFlow(0)

    override val keepScreenOn = MutableStateFlow(false)

    override val textSettings: ReaderTextSettings? = null

    /** Set by the test that needs a session offering it; null is the manga shape. */
    var autoScrollSlot: ReaderAutoScroll? = null

    override val autoScroll: ReaderAutoScroll? get() = autoScrollSlot

    override val navigator = MutableStateFlow(ReaderNavigatorState())

    override val showProgress = MutableStateFlow(false)

    override val loadState = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Idle)

    override fun retryLoad() {
        retried++
    }

    var retried = 0
        private set

    override val bookmarked = MutableStateFlow(false)

    override val webUrl = MutableStateFlow<String?>(null)

    override fun toggleBookmark() {
        bookmarked.value = !bookmarked.value
    }

    override fun setOrientation(flagValue: Int) {
        orientation.value = flagValue
    }

    var stepped = 0
        private set

    override suspend fun previousChapter() {
        stepped--
    }

    override suspend fun nextChapter() {
        stepped++
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOn.value = enabled
    }

    override val chapterList: ReaderChapterList = FakeChapterList()

    override fun createViewport(host: ReaderActivity): ReaderViewport =
        error("a unit test never builds a viewport")
}

/** The engine only hands this through, so it answers nothing beyond the type. */
private class FakeChapterList : ReaderChapterList {
    override val rows = MutableStateFlow(emptyList<ReaderChapterRow>())

    override val currentChapterId = MutableStateFlow(-1L)

    override fun open(chapterId: Long) = Unit

    override fun setRead(chapterId: Long, read: Boolean) = Unit

    override fun setBookmark(chapterId: Long, bookmarked: Boolean) = Unit

    override fun download(chapterId: Long, action: ChapterDownloadAction) = Unit
}

private class FakeViewport : ReaderViewport {
    var sought: ChapterProgress? = null
        private set

    var destroyed = false
        private set

    override val view: View get() = error("no view in a unit test")

    override val isRtl = false

    override fun destroy() {
        destroyed = true
    }

    override fun handleKeyEvent(event: KeyEvent) = false

    override fun handleGenericMotionEvent(event: MotionEvent) = false

    var chapterSteps = 0
        private set

    override fun onChapterStepped() {
        chapterSteps++
    }

    override fun seekTo(progress: ChapterProgress) {
        sought = progress
    }
}

private class RecordingPageActions : ReaderPageActions {
    var shared = false
        private set

    override fun save() = Unit

    override fun share(copyToClipboard: Boolean) {
        shared = true
    }

    override fun setAsCover() = Unit
}
