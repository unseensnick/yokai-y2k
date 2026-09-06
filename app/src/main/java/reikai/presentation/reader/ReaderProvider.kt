package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import kotlinx.coroutines.flow.Flow

/**
 * One content type's answers for the reader engine. The host is passed per call rather than held, so
 * a provider never outlives the Activity that built its views. Naming `ReaderActivity` is deliberate,
 * since one host serves both types; what must not appear in this file is a manga type.
 *
 * **Every flow here is cold.** The engine holds the provider across an Activity recreation, so state
 * shared in the Activity's scope would freeze the first time the reader is rotated.
 */
interface ReaderProvider {

    /**
     * What the chrome shows for this entry. Neutral so the app bar renders the same way whichever
     * content type is open, rather than the host reaching into one engine's model for a title.
     */
    val chrome: Flow<ReaderChromeState>

    /**
     * The bottom-bar buttons this content type offers, as [ReaderBottomButton] value codes. Each type
     * stores its own selection, so a manga action cannot surface in a novel session or the reverse.
     */
    val bottomButtons: Flow<Set<String>>

    /**
     * Where the reader is in the open chapter and which navigator shows it. Both are the session's to
     * answer: a novel scrolls one continuous page, so it always uses the rail, while manga offers it
     * per reading mode.
     */
    val navigator: Flow<ReaderNavigatorState>

    /** Steps a chapter. Both types resolve their own neighbour, since what is next depends on the
     *  reading order and skip settings of that type's own chapter list. */
    suspend fun previousChapter()

    suspend fun nextChapter()

    /**
     * Whether a chapter is in flight and whether the last attempt failed. Shared because a blank
     * page with no explanation is the same defect whatever the content type, and only the session
     * knows which of its chapters is being fetched.
     */
    val loadState: Flow<ReaderLoadState>

    /** Re-runs the load that failed. Only reachable from the failure the host raises. */
    fun retryLoad()

    /**
     * Whether the open chapter is bookmarked, and the verb that flips it. Every content type has
     * chapters and every one can bookmark them, so the bar asks the session rather than reading one
     * engine's model, which is how this control ended up permanently empty for novels.
     */
    val bookmarked: Flow<Boolean>

    fun toggleBookmark()

    /**
     * The open chapter's page on the source site, or null where it has none. Null hides the web,
     * browser and share actions rather than showing them dead, which is the capability-slot rule.
     */
    val webUrl: Flow<String?>

    /** The chapter sheet's rows and its verbs, which every content type has. */
    val chapterList: ReaderChapterList

    /** Typography, or null for a type whose pages are images rather than text. */
    val textSettings: ReaderTextSettings?

    /**
     * The entry's own rotation flag, a [eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation]
     * `flagValue` where 0 means follow that content type's global default. Both types store one per
     * entry, so the bar icon and the picker read it here rather than from a model.
     */
    val orientation: Flow<Int>

    fun setOrientation(flagValue: Int)

    /** Each type has its own keep-screen-on preference, and only novels offer it as a bar button. */
    val keepScreenOn: Flow<Boolean>

    fun setKeepScreenOn(enabled: Boolean)

    /**
     * Builds the viewport for this content type. Called again whenever the shape changes, for
     * instance on a reading-mode switch, and the engine destroys the previous one.
     */
    fun createViewport(host: ReaderActivity): ReaderViewport
}
