package reikai.presentation.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.reader.components.ModeSelectionDialog
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsIconGrid
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.i18n.stringResource

private val OrientationsWithoutDefault = ReaderOrientation.entries - ReaderOrientation.DEFAULT

/**
 * Orientation picker for the reader's rotation button, both content types. Takes the entry's own flag
 * and hands one back, so the caller owns where it is stored; Mihon's `OrientationSelectDialog`, which
 * read the manga off a settings model, was deleted for it.
 */
@Composable
fun ReaderOrientationDialog(
    currentOrientation: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = ReaderOrientation.fromPreference(currentOrientation)
    var selected by remember { mutableStateOf(current) }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        ModeSelectionDialog(
            onUseDefault = {
                onChange(ReaderOrientation.DEFAULT.flagValue)
                onDismiss()
            }.takeIf { current != ReaderOrientation.DEFAULT },
            onApply = {
                onChange(selected.flagValue)
                onDismiss()
            },
        ) {
            SettingsIconGrid(MR.strings.rotation_type) {
                items(OrientationsWithoutDefault) { mode ->
                    IconToggleButton(
                        checked = mode == selected,
                        onCheckedChange = { selected = mode },
                        modifier = Modifier.fillMaxWidth(),
                        imageVector = mode.icon,
                        title = stringResource(mode.stringRes),
                    )
                }
            }
        }
    }
}
