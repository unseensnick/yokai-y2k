package eu.kanade.presentation.more.settings.screen.novel

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import reikai.domain.novel.NovelPreferences
import reikai.novel.font.FontError
import reikai.novel.font.NovelFont
import reikai.novel.font.NovelFontManager
import tachiyomi.i18n.MR

@Immutable
data class NovelFontsState(
    val fonts: List<NovelFont> = emptyList(),
    val busy: Boolean = false,
    val dialog: NovelFontDialog? = null,
    /** The last failure, as a string resource the screen shows once and clears. */
    val error: dev.icerock.moko.resources.StringResource? = null,
)

sealed interface NovelFontDialog {
    data object Download : NovelFontDialog
    data class Delete(val font: NovelFont) : NovelFontDialog
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelFontsViewModel(
    private val fontManager: NovelFontManager,
    private val novelPreferences: NovelPreferences,
) : ViewModel() {

    private val mutableState = MutableStateFlow(NovelFontsState())
    val state: StateFlow<NovelFontsState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun showDialog(dialog: NovelFontDialog) = mutableState.update { it.copy(dialog = dialog) }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun import(uri: Uri) = run { fontManager.import(uri) }

    fun download(family: String) {
        dismissDialog()
        if (family.isNotBlank()) run { fontManager.download(family.trim()) }
    }

    fun delete(font: NovelFont) {
        dismissDialog()
        viewModelScope.launch {
            // A font that was in use leaves the reader on the default face, which is what an absent
            // file would produce anyway; clearing the preference says so rather than leaving it
            // pointing at nothing.
            if (novelPreferences.readerFontFamily().get() == font.fileName) {
                novelPreferences.readerFontFamily().set("")
            }
            fontManager.delete(font)
            refresh()
        }
    }

    private fun run(work: suspend () -> Result<NovelFont>) {
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = work()
            mutableState.update { it.copy(busy = false, error = result.exceptionOrNull()?.messageRes()) }
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val fonts = fontManager.installed()
            mutableState.update { it.copy(fonts = fonts) }
        }
    }
}

private fun Throwable.messageRes() = when (this) {
    FontError.UnsupportedFormat -> MR.strings.novel_font_error_format
    FontError.Offline -> MR.strings.novel_font_error_download
    FontError.NoStorage -> MR.strings.novel_font_error_storage
    else -> MR.strings.novel_font_error_read
}
