package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress

/**
 * The adapter is all delegation, and a member that silently stops delegating breaks the reader in a
 * way only a device pass would catch, so each one is pinned here instead.
 *
 * `isRtl` is the exception: it reads the concrete viewer classes, which cannot be built without an
 * Activity, so it is verified on device by the chapter navigator changing direction.
 */
class MangaViewportTest {

    @Test
    fun `destroy reaches the viewer`() {
        val viewer = RecordingViewer()

        MangaViewport(viewer, pageAt = { null }).destroy()

        viewer.destroyed shouldBe true
    }

    @Test
    fun `a key event reaches the viewer and its answer comes back`() {
        val viewer = RecordingViewer(handlesKeys = true)

        val handled = MangaViewport(viewer, pageAt = { null }).handleKeyEvent(KEY_EVENT)

        handled shouldBe true
        viewer.keyEvents shouldBe 1
    }

    @Test
    fun `a key event the viewer declines is reported as unhandled`() {
        val viewer = RecordingViewer(handlesKeys = false)

        MangaViewport(viewer, pageAt = { null }).handleKeyEvent(KEY_EVENT) shouldBe false
    }

    @Test
    fun `a motion event reaches the viewer and its answer comes back`() {
        val viewer = RecordingViewer(handlesMotion = true)

        val handled = MangaViewport(viewer, pageAt = { null }).handleGenericMotionEvent(MOTION_EVENT)

        handled shouldBe true
        viewer.motionEvents shouldBe 1
    }

    /**
     * A scrub arrives as the neutral position, and this is where it becomes a page. Looking the page
     * up rather than passing an index is what keeps `ReaderPage` out of the shared vocabulary.
     */
    @Test
    fun `seeking to a page moves the viewer to that page`() {
        val viewer = RecordingViewer()
        val page: ReaderPage = mockk()

        MangaViewport(viewer, pageAt = { index -> page.takeIf { index == 3 } })
            .seekTo(ChapterProgress.Pages(lastPageRead = 3L, pageCount = 10L))

        viewer.movedTo shouldBe page
    }

    @Test
    fun `seeking past the chapter's pages moves nothing`() {
        val viewer = RecordingViewer()

        MangaViewport(viewer, pageAt = { null })
            .seekTo(ChapterProgress.Pages(lastPageRead = 99L, pageCount = 10L))

        viewer.movedTo shouldBe null
    }

    /** A percentage is the novel unit, so it names no page here and must not move the viewer. */
    @Test
    fun `seeking by percentage moves nothing`() {
        val viewer = RecordingViewer()
        val page: ReaderPage = mockk()

        MangaViewport(viewer, pageAt = { page }).seekTo(ChapterProgress.Percent(4200L))

        viewer.movedTo shouldBe null
    }

    @Test
    fun `the wrapped viewer stays reachable for the questions the contract does not answer`() {
        val viewer = RecordingViewer()

        MangaViewport(viewer, pageAt = { null }).viewer shouldBe viewer
    }
}

// Both are framework value objects whose real constructors throw outside Android, and the adapter
// only passes them through, so a stand-in at that boundary is what the test needs.
private val KEY_EVENT: KeyEvent = mockk()
private val MOTION_EVENT: MotionEvent = mockk()

private class RecordingViewer(
    private val handlesKeys: Boolean = false,
    private val handlesMotion: Boolean = false,
) : Viewer {
    var destroyed = false
        private set
    var keyEvents = 0
        private set
    var movedTo: ReaderPage? = null
        private set
    var motionEvents = 0
        private set

    override fun getView(): View = error("no view in a unit test")

    override fun destroy() {
        destroyed = true
    }

    override fun setChapters(chapters: ViewerChapters) = Unit

    override fun moveToPage(page: ReaderPage) {
        movedTo = page
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        keyEvents++
        return handlesKeys
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        motionEvents++
        return handlesMotion
    }
}
