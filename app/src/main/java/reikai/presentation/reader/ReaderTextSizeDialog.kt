package reikai.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Text-size picker for the novel reader's bottom-bar text-size button: the same font-size slider as the
 * settings sheet's Display tab, reachable in one tap. Applies live (the caller's ScreenModel persists it
 * and the reader reflows the text in place).
 */
@Composable
fun ReaderTextSizeDialog(
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                "${stringResource(MR.strings.pref_reader_text_size)}: $fontSize",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { onFontSize(it.toInt()) },
                valueRange = 12f..32f,
                steps = 19,
            )
        }
    }
}
