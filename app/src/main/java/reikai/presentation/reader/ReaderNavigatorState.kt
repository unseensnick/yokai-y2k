package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import reikai.domain.reader.ChapterProgress

/**
 * What the reader's progress navigator shows for the open session. [progress] is null while nothing is
 * loaded, which is what leaves the slider off rather than drawing an empty one.
 */
@Immutable
data class ReaderNavigatorState(
    val progress: ChapterProgress? = null,
    /** The vertical rail on the reader's edge, rather than the horizontal bar above the actions. */
    val useRail: Boolean = false,
    val railOnLeft: Boolean = false,
    val railHeightPercent: Int = 100,
)
