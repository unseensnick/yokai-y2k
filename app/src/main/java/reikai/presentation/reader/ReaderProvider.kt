package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import kotlinx.coroutines.flow.StateFlow

/**
 * One content type's answers for the reader engine. The host is passed per call rather than held,
 * so a provider never outlives the Activity that built its views.
 *
 * Naming `ReaderActivity` here is deliberate: the amendment keeps one host for both content types,
 * because a window, its system bars and its orientation lock cannot exist twice. What must not
 * appear in this file is a manga type.
 */
interface ReaderProvider {

    /**
     * What the chrome shows for this entry. Neutral so the app bar renders the same way whichever
     * content type is open, rather than the host reaching into one engine's model for a title.
     */
    val chrome: StateFlow<ReaderChromeState>

    /**
     * The bottom-bar buttons this content type offers, as [ReaderBottomButton] value codes. Each type
     * stores its own selection, so a manga action cannot surface in a novel session or the reverse.
     */
    val bottomButtons: StateFlow<Set<String>>

    /**
     * Builds the viewport for this content type. Called again whenever the shape changes, for
     * instance on a reading-mode switch, and the engine destroys the previous one.
     */
    fun createViewport(host: ReaderActivity): ReaderViewport
}
