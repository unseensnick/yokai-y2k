package eu.kanade.presentation.more.settings.screen.novel.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import reikai.novel.content.NovelRegexReplacement
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

/**
 * Adds a rule when [rule] is null and edits it otherwise. Everything typed here is previewed through
 * the reader's own kernel, which [onPreview] runs, so the sample result cannot disagree with what a
 * chapter gets.
 */
@Composable
fun NovelRegexRuleEditDialog(
    rule: NovelRegexReplacement?,
    onDismissRequest: () -> Unit,
    onSave: (NovelRegexReplacement) -> Unit,
    onPreview: (NovelRegexReplacement, String) -> Result<String>,
) {
    var title by remember { mutableStateOf(rule?.title.orEmpty()) }
    var pattern by remember { mutableStateOf(rule?.pattern.orEmpty()) }
    var replacement by remember { mutableStateOf(rule?.replacement.orEmpty()) }
    var isRegex by remember { mutableStateOf(rule?.isRegex ?: true) }
    var matchWholeWord by remember { mutableStateOf(rule?.matchWholeWord ?: false) }
    var caseSensitive by remember { mutableStateOf(rule?.caseSensitive ?: false) }
    var sample by remember { mutableStateOf("") }
    // Held across recompositions: a fresh id per frame would make every save look like a new rule.
    val newId = remember { UUID.randomUUID().toString() }

    val edited = NovelRegexReplacement(
        title = title.trim(),
        pattern = pattern,
        replacement = replacement,
        enabled = rule?.enabled ?: true,
        isRegex = isRegex,
        matchWholeWord = matchWholeWord && !isRegex,
        caseSensitive = caseSensitive,
        id = rule?.id ?: newId,
    )
    // Re-run on every edit rather than behind a button, so a pattern that stops compiling says so
    // while it is being typed and the Save button below can refuse it on the same signal.
    val preview = remember(pattern, replacement, isRegex, matchWholeWord, caseSensitive, sample) {
        if (pattern.isEmpty()) null else onPreview(edited, sample)
    }
    val patternError = preview?.exceptionOrNull()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && pattern.isNotEmpty() && patternError == null,
                onClick = {
                    onSave(edited)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(
                text = stringResource(
                    if (rule == null) MR.strings.action_add else MR.strings.action_edit,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(MR.strings.novel_regex_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(MR.strings.novel_regex_find)) },
                    isError = patternError != null,
                    supportingText = patternError?.let { { Text(it.message.orEmpty()) } },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(MR.strings.novel_regex_replace_with)) },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                )
                CheckboxRow(
                    checked = isRegex,
                    label = stringResource(MR.strings.novel_regex_use_pattern),
                    onCheckedChange = { isRegex = it },
                )
                // Whole words only has no meaning once the pattern can say so itself, so it is hidden
                // rather than shown doing nothing.
                if (!isRegex) {
                    CheckboxRow(
                        checked = matchWholeWord,
                        label = stringResource(MR.strings.novel_regex_whole_words),
                        onCheckedChange = { matchWholeWord = it },
                    )
                }
                CheckboxRow(
                    checked = caseSensitive,
                    label = stringResource(MR.strings.novel_regex_match_case),
                    onCheckedChange = { caseSensitive = it },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    label = { Text(stringResource(MR.strings.novel_regex_sample)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                preview?.getOrNull()?.takeIf { sample.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = MaterialTheme.padding.small),
                    )
                }
            }
        },
    )
}

@Composable
private fun CheckboxRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, modifier = Modifier.padding(start = MaterialTheme.padding.small))
    }
}

@Composable
fun NovelRegexRuleDeleteDialog(
    ruleName: String,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onDelete()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_delete)) },
        text = { Text(text = stringResource(MR.strings.novel_regex_delete_confirmation, ruleName)) },
    )
}
