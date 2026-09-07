package eu.kanade.presentation.more.settings.screen.novel

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
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
import reikai.novel.font.GoogleFont
import reikai.novel.font.NovelFont
import reikai.novel.font.NovelFontManager
import tachiyomi.i18n.MR
import java.io.File

@Immutable
data class NovelFontsState(
    val selected: String = "",
    val fonts: List<NovelFont> = emptyList(),
    val busy: Boolean = false,
    val dialog: NovelFontDialog? = null,
    /** The last failure, shown once and then cleared. */
    val error: StringResource? = null,
    /** Where each added font can be read from, so the picker can draw its row in that face. */
    val fontFiles: Map<String, File> = emptyMap(),
    /** Every family Google Fonts offers, empty until the browse sheet asks for it. */
    val catalogue: List<GoogleFont> = emptyList(),
    val catalogueLoading: Boolean = false,
)

sealed interface NovelFontDialog {
    /** The add sheet, offering the two ways a font can arrive. */
    data object Add : NovelFontDialog
    data object Browse : NovelFontDialog
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
        viewModelScope.launch {
            novelPreferences.readerFontFamily().changes().collect { family ->
                mutableState.update { it.copy(selected = family) }
            }
        }
        refresh()
    }

    fun select(family: String) = novelPreferences.readerFontFamily().set(family)

    fun showDialog(dialog: NovelFontDialog) {
        mutableState.update { it.copy(dialog = dialog) }
        if (dialog is NovelFontDialog.Browse && state.value.catalogue.isEmpty()) loadCatalogue()
    }

    /** Left empty on a failure rather than raised: the dialog still takes a name typed by hand. */
    private fun loadCatalogue() {
        mutableState.update { it.copy(catalogueLoading = true) }
        viewModelScope.launch {
            val families = fontManager.googleFontCatalogue()
            mutableState.update { it.copy(catalogue = families, catalogueLoading = false) }
        }
    }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    fun import(uri: Uri) {
        dismissDialog()
        add { fontManager.import(uri) }
    }

    fun download(family: String) {
        dismissDialog()
        add { fontManager.download(family) }
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

    /** Selecting what just arrived, because adding a font is only ever a step towards reading in it. */
    private fun add(work: suspend () -> Result<NovelFont>) {
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = work()
            result.getOrNull()?.let { select(it.fileName) }
            mutableState.update { it.copy(busy = false, error = result.exceptionOrNull()?.messageRes()) }
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val fonts = fontManager.installed()
            val files = fonts.mapNotNull { font ->
                fontManager.localFile(font.fileName)?.let { font.fileName to it }
            }
            mutableState.update { it.copy(fonts = fonts, fontFiles = files.toMap()) }
        }
    }
}

private fun Throwable.messageRes() = when (this) {
    FontError.UnsupportedFormat -> MR.strings.novel_font_error_format
    FontError.Offline -> MR.strings.novel_font_error_download
    FontError.NoStorage -> MR.strings.novel_font_error_storage
    else -> MR.strings.novel_font_error_read
}
