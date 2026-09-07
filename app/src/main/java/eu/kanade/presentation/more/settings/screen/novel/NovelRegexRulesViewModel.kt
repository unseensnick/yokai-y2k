package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.NovelRegexReplacement
import reikai.novel.content.NovelRegexReplacements
import tachiyomi.core.common.util.system.logcat

@Immutable
data class NovelRegexRulesState(
    val rules: List<NovelRegexReplacement> = emptyList(),
    val dialog: NovelRegexRuleDialog? = null,
)

sealed interface NovelRegexRuleDialog {
    /** A null [rule] is the add case, so one dialog serves both and cannot drift between them. */
    data class Edit(val rule: NovelRegexReplacement?) : NovelRegexRuleDialog
    data class Delete(val rule: NovelRegexReplacement) : NovelRegexRuleDialog
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelRegexRulesViewModel(
    private val novelPreferences: NovelPreferences,
) : ViewModel() {

    private val dialog = MutableStateFlow<NovelRegexRuleDialog?>(null)

    val state: StateFlow<NovelRegexRulesState> = combine(
        novelPreferences.readerRegexReplacements().changes(),
        dialog,
    ) { json, dialog ->
        NovelRegexRulesState(decode(json), dialog)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NovelRegexRulesState())

    fun showDialog(dialog: NovelRegexRuleDialog) {
        this.dialog.value = dialog
    }

    fun dismissDialog() {
        dialog.value = null
    }

    /** Replaces the rule sharing [rule]'s id, or appends it when it is new. */
    fun save(rule: NovelRegexReplacement) {
        val current = rules()
        val updated = if (current.any { it.id == rule.id }) {
            current.map { if (it.id == rule.id) rule else it }
        } else {
            current + rule
        }
        write(updated)
        dismissDialog()
    }

    fun delete(rule: NovelRegexReplacement) {
        write(rules().filterNot { it.id == rule.id })
        dismissDialog()
    }

    fun toggle(rule: NovelRegexReplacement) {
        write(rules().map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it })
    }

    /**
     * What the rule would do to [input], run through the same kernel the reader uses, so the answer
     * here cannot disagree with what a chapter gets. Null where the pattern will not compile, with
     * the reason, which is what the dialog shows instead of an output.
     */
    fun preview(rule: NovelRegexReplacement, input: String): Result<String> = runCatching {
        NovelRegexReplacements.compile(rule).applyTo(input)
    }

    // Read back from the preference rather than from state, so a save cannot drop an edit made while
    // the dialog was open.
    private fun rules() = decode(novelPreferences.readerRegexReplacements().get())

    private fun write(rules: List<NovelRegexReplacement>) {
        novelPreferences.readerRegexReplacements().set(Json.encodeToString(rules))
    }

    private fun decode(json: String): List<NovelRegexReplacement> = try {
        Json.decodeFromString(json)
    } catch (e: Exception) {
        // An unreadable preference shows as an empty list rather than taking the screen down; saving
        // over it is how the user recovers.
        logcat(LogPriority.WARN, e) { "Failed to parse regex replacements" }
        emptyList()
    }
}
