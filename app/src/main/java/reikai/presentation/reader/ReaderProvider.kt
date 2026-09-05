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
