package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.leadingLabel
import reikai.domain.reader.trailingLabel

@Composable
fun ReaderPageIndicator(
    progress: ChapterProgress?,
    modifier: Modifier = Modifier,
) {
    if (progress == null) return
    // RK: upstream's `totalPages <= 0` guard, kept through the retyping. A chapter whose pages have
    // not loaded reports a count of zero, and "1 / 0" is worse than showing nothing.
    if (progress is ChapterProgress.Pages && progress.pageCount <= 0L) return

    // RK: labels come from the position kernel, so this reads a page count for manga and a percentage
    // for a continuously scrolled chapter without knowing which it has.
    val text = "${progress.leadingLabel} / ${progress.trailingLabel}"

    val style = TextStyle(
        color = Color(235, 235, 235),
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    val strokeStyle = style.copy(
        color = Color(45, 45, 45),
        drawStyle = Stroke(width = 4f),
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = strokeStyle,
        )
        Text(
            text = text,
            style = style,
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderPageIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderPageIndicator(ChapterProgress.Pages(lastPageRead = 9, pageCount = 69))
        }
    }
}
