package eu.kanade.presentation.more.settings.screen.novel

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import reikai.novel.font.GoogleFont
import reikai.novel.font.isGenericFont
import reikai.presentation.novel.reader.readerFonts
import reikai.presentation.novel.reader.readerGenericFonts
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB
import java.io.File

/**
 * Choosing the reader's font and managing the ones the user added, on one screen. Kept together
 * because adding a font is only ever a step towards reading in it, and a picker somewhere else would
 * mean two places that both claim to answer the same question.
 */
class NovelFontsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<NovelFontsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val lazyListState = rememberLazyListState()
        val snackbarHostState = remember { SnackbarHostState() }
        // The source's own font leads because it is the default, then the three families every device
        // has, then the faces shipped with the app.
        val builtInFonts = remember {
            val (original, bundled) = readerFonts.partition { it.family.isEmpty() }
            original + readerGenericFonts + bundled
        }

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
                    title = stringResource(MR.strings.pref_novel_font),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.novel_font_add)) },
                    icon = { Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { viewModel.showDialog(NovelFontDialog.Add) },
                    expanded = lazyListState.shouldExpandFAB(),
                )
            },
        ) { paddingValues ->
            LazyColumn(
                state = lazyListState,
                contentPadding = paddingValues + topSmallPaddingValues,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            ) {
                if (state.busy) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                item { SectionHeader(stringResource(MR.strings.novel_font_section_built_in)) }
                items(builtInFonts, key = { "b:${it.family}" }) { font ->
                    val isDefault = font.family.isEmpty()
                    FontRow(
                        // The one row whose name does not describe it: it sets no font at all, so it
                        // is the only one that needs saying what happens instead.
                        label = if (isDefault) stringResource(MR.strings.pref_novel_font_default) else font.name,
                        subtitle = stringResource(MR.strings.pref_novel_font_default_summary)
                            .takeIf { isDefault },
                        preview = assetPreview(context, font.family),
                        selected = state.selected == font.family,
                        onClick = { viewModel.select(font.family) },
                    )
                }
                item { SectionHeader(stringResource(MR.strings.novel_font_section_yours)) }
                if (state.fonts.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.information_empty_novel_fonts),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MaterialTheme.padding.medium),
                        )
                    }
                }
                items(state.fonts, key = { "c:${it.fileName}" }) { font ->
                    FontRow(
                        label = font.displayName,
                        preview = filePreview(state.fontFiles[font.fileName]),
                        selected = state.selected == font.fileName,
                        onClick = { viewModel.select(font.fileName) },
                        onDelete = { viewModel.showDialog(NovelFontDialog.Delete(font)) },
                    )
                }
            }
        }

        when (val dialog = state.dialog) {
            null -> {}
            is NovelFontDialog.Add -> AddFontSheet(
                onDismissRequest = viewModel::dismissDialog,
                onImport = { picker.launch(arrayOf("*/*")) },
                onBrowse = { viewModel.showDialog(NovelFontDialog.Browse) },
            )
            is NovelFontDialog.Browse -> BrowseGoogleFontsDialog(
                catalogue = state.catalogue,
                loading = state.catalogueLoading,
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

/**
 * A bundled row draws itself in its own asset, which is the only way to tell nine serifs apart. Null
 * for the source's own font and the generic families, which have nothing of their own to show.
 */
@Composable
private fun assetPreview(context: Context, family: String): FontFamily? = remember(family) {
    if (family.isEmpty() || isGenericFont(family)) return@remember null
    runCatching {
        FontFamily(Font(path = "fonts/$family.ttf", assetManager = context.assets))
    }.getOrNull()
}

/** The same for a font the user added, from the readable copy the screen model resolved off-thread. */
@Composable
private fun filePreview(file: File?): FontFamily? = remember(file) {
    file?.takeIf { it.exists() }?.let { runCatching { FontFamily(Font(it)) }.getOrNull() }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            top = MaterialTheme.padding.medium,
            bottom = MaterialTheme.padding.extraSmall,
        ),
    )
}

@Composable
private fun FontRow(
    label: String,
    preview: FontFamily?,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = preview,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
                    contentDescription = null,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddFontSheet(onDismissRequest: () -> Unit, onImport: () -> Unit, onBrowse: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium)) {
            Text(
                text = stringResource(MR.strings.novel_font_add),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.large),
            )
            SheetAction(
                icon = MaterialSymbols.Rounded.Folder,
                title = stringResource(MR.strings.novel_font_import),
                subtitle = stringResource(MR.strings.novel_font_import_summary),
                onClick = {
                    onDismissRequest()
                    onImport()
                },
            )
            SheetAction(
                icon = MaterialSymbols.Rounded.Download,
                title = stringResource(MR.strings.novel_font_download),
                subtitle = stringResource(MR.strings.novel_font_download_summary),
                onClick = onBrowse,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = MaterialTheme.padding.large,
                vertical = MaterialTheme.padding.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Column(modifier = Modifier.padding(start = MaterialTheme.padding.large)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Searches every family Google Fonts publishes, so a font is found here rather than in a browser and
 * typed back. The field still downloads whatever is in it, which is what the reader falls back to
 * when the catalogue cannot be fetched.
 */
@Composable
private fun BrowseGoogleFontsDialog(
    catalogue: List<GoogleFont>,
    loading: Boolean,
    onDismissRequest: () -> Unit,
    onDownload: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, catalogue) {
        val term = query.trim()
        catalogue
            .filter { it.family.contains(term, ignoreCase = true) }
            // A prefix match is what the reader meant; the rest follow so a partial name still finds it.
            .sortedBy { if (it.family.startsWith(term, ignoreCase = true)) 0 else 1 }
            .take(SEARCH_RESULT_LIMIT)
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = query.isNotBlank(),
                onClick = { onDownload(query.trim()) },
            ) {
                Text(text = stringResource(MR.strings.novel_font_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.novel_font_google)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(MR.strings.novel_font_family_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MaterialTheme.padding.medium),
                    )
                }
                LazyColumn(modifier = Modifier.padding(top = MaterialTheme.padding.small)) {
                    items(matches, key = { it.family }) { font ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDownload(font.family) }
                                .padding(vertical = MaterialTheme.padding.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = font.family, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = font.category,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Download,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** The catalogue runs to nearly two thousand families, which is a list nobody scrolls. */
private const val SEARCH_RESULT_LIMIT = 40
