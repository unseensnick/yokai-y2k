package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.novel.components.NovelRegexRuleDeleteDialog
import eu.kanade.presentation.more.settings.screen.novel.components.NovelRegexRuleEditDialog
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.RadioButtonUnchecked
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import reikai.novel.content.NovelRegexReplacement
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB

/**
 * The reader's find-and-replace rules. Novel-only by mechanism: a rule rewrites chapter text, and a
 * manga chapter is images the source ships, so there is nothing for one to act on.
 */
class NovelRegexRulesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<NovelRegexRulesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val lazyListState = rememberLazyListState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_novel_regex_rules),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_add)) },
                    icon = { Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { viewModel.showDialog(NovelRegexRuleDialog.Edit(null)) },
                    expanded = lazyListState.shouldExpandFAB(),
                )
            },
        ) { paddingValues ->
            if (state.rules.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_empty_novel_regex_rules,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = paddingValues + topSmallPaddingValues,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                ) {
                    items(state.rules, key = { it.id }) { rule ->
                        RuleItem(
                            rule = rule,
                            onClick = { viewModel.showDialog(NovelRegexRuleDialog.Edit(rule)) },
                            onToggle = { viewModel.toggle(rule) },
                            onDelete = { viewModel.showDialog(NovelRegexRuleDialog.Delete(rule)) },
                        )
                    }
                }
            }
        }

        when (val dialog = state.dialog) {
            null -> {}
            is NovelRegexRuleDialog.Edit -> NovelRegexRuleEditDialog(
                rule = dialog.rule,
                onDismissRequest = viewModel::dismissDialog,
                onSave = viewModel::save,
                onPreview = viewModel::preview,
            )
            is NovelRegexRuleDialog.Delete -> NovelRegexRuleDeleteDialog(
                ruleName = dialog.rule.title,
                onDismissRequest = viewModel::dismissDialog,
                onDelete = { viewModel.delete(dialog.rule) },
            )
        }
    }
}

/**
 * The row shows what the rule does, because a name alone cannot be checked against what a chapter
 * comes out looking like. The tick toggles it; the rest of the row opens it for editing.
 */
@Composable
private fun RuleItem(
    rule: NovelRegexReplacement,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (rule.enabled) {
                        MaterialSymbols.RoundedFilled.CheckCircle
                    } else {
                        MaterialSymbols.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = stringResource(MR.strings.action_enable),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MaterialTheme.padding.small)
                    .alpha(if (rule.enabled) 1f else DISABLED_ALPHA),
            ) {
                Text(text = rule.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(
                        MR.strings.novel_regex_rule_summary,
                        rule.pattern,
                        rule.replacement.ifEmpty { stringResource(MR.strings.novel_regex_removes) },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.5f
