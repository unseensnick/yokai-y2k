package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * The dialogs the reader engine dispatches over one slot. One slot rather than one flag each, so a
 * loading dialog and a user-opened one stay mutually exclusive the way the host's single state
 * field made them.
 */
@Immutable
sealed interface ReaderDialog {

    /** Raised by the host while an adjacent chapter loads; it owns that state, the engine shows it. */
    data object Loading : ReaderDialog

    data object Settings : ReaderDialog

    data object ReadingModeSelect : ReaderDialog

    data object OrientationSelect : ReaderDialog

    data object ChapterList : ReaderDialog

    /**
     * Carries the capability the provider built for the long-pressed page rather than the page
     * itself, which is what keeps a manga type out of this vocabulary. It is also why the actions
     * take no argument: each instance is already bound to its page.
     */
    data class PageActions(val actions: ReaderPageActions) : ReaderDialog
}

/**
 * Acting on one already-identified page. A content type with no page to act on never builds one, so
 * the dialog is unreachable for it rather than shown with dead buttons.
 */
@Stable
interface ReaderPageActions {
    fun save()

    fun share(copyToClipboard: Boolean)

    fun setAsCover()
}
