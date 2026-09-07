package eu.kanade.presentation.more.settings.screen.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import reikai.novel.font.NovelFont
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB

/**
 * The reader fonts the user added. Picking one happens on the settings screen's font list, which
 * offers these alongside the bundled faces; this screen only puts them there and takes them away.
 */
class NovelFontsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<NovelFontsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val lazyListState = rememberLazyListState()
        val snackbarHostState = remember { SnackbarHostState() }

        // Any file, because a picker filtered on font MIME types hides fonts on the devices that
        // report them as application/octet-stream. What the file actually is, is checked on import.
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::import)
        }

        val errorMessage = state.error?.let { stringResource(it) }
        LaunchedEffect(errorMessage) {
            errorMessage?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.dismissError()
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_novel_fonts),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.novel_font_download),
                                    icon = MaterialSymbols.Rounded.Download,
                                    onClick = { viewModel.showDialog(NovelFontDialog.Download) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.novel_font_import)) },
                    icon = { Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { picker.launch(arrayOf("*/*")) },
                    expanded = lazyListState.shouldExpandFAB(),
                )
            },
        ) { paddingValues ->
            if (state.fonts.isEmpty() && !state.busy) {
                EmptyScreen(
                    stringRes = MR.strings.information_empty_novel_fonts,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = paddingValues + topSmallPaddingValues,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                ) {
                    if (state.busy) {
                        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    }
                    items(state.fonts, key = { it.fileName }) { font ->
                        FontItem(font = font, onDelete = { viewModel.showDialog(NovelFontDialog.Delete(font)) })
                    }
                }
            }
        }

        when (val dialog = state.dialog) {
            null -> {}
            is NovelFontDialog.Download -> DownloadDialog(
                onDismissRequest = viewModel::dismissDialog,
                onDownload = viewModel::download,
            )
            is NovelFontDialog.Delete -> AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                confirmButton = {
                    TextButton(onClick = { viewModel.delete(dialog.font) }) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(text = stringResource(MR.strings.action_delete)) },
                text = {
                    Text(
                        text = stringResource(
                            MR.strings.novel_font_delete_confirmation,
                            dialog.font.displayName,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun FontItem(font: NovelFont, onDelete: () -> Unit) {
    ElevatedCard(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = font.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
}

@Composable
private fun DownloadDialog(onDismissRequest: () -> Unit, onDownload: (String) -> Unit) {
    var family by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(enabled = family.isNotBlank(), onClick = { onDownload(family) }) {
                Text(text = stringResource(MR.strings.novel_font_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.novel_font_download)) },
        text = {
            OutlinedTextField(
                value = family,
                onValueChange = { family = it },
                label = { Text(stringResource(MR.strings.novel_font_family_name)) },
                supportingText = { Text(stringResource(MR.strings.novel_font_family_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
