package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow

/** What the typography controls show while they are open. */
@Immutable
data class ReaderTextState(
    val fontSize: Int,
    val followSystemTheme: Boolean,
    /** The stored background colour, which is what the picker marks as chosen. */
    val backgroundColor: String,
)

/**
 * Typography, for a content type whose text the reader draws. Manga builds none: a page is an image
 * the source ships, so there is nothing for a font size or a page colour to act on, and that is what
 * makes these controls absent for manga rather than present and dead.
 */
@Stable
interface ReaderTextSettings {

    val state: Flow<ReaderTextState>

    fun setFontSize(size: Int)

    fun followSystemTheme()

    /** Both colours together, since a background without its text colour can be unreadable. */
    fun setThemeColors(background: String, textColor: String)
}
