package eu.kanade.tachiyomi.ui.reader.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ReaderSettingsViewModel(
    readerState: StateFlow<ReaderViewModel.State>,
    // RK: the engine owns the viewport now, so the live viewer arrives as its own flow rather than
    // as a field of the reader state.
    viewerState: StateFlow<Viewer?>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences,
    // RK: the mode the reader actually resolved, so the quick menu can show what is on screen
    // rather than nothing. Supplied as a lambda over ReaderViewModel's own predicate, which already
    // folds in the global default and auto webtoon; deriving it from the viewer would not work,
    // since one viewer class serves both Webtoon and Continuous vertical.
    val resolvedReadingMode: () -> Int = { ReadingMode.DEFAULT.flagValue },
) : ViewModel() {

    val viewerFlow = viewerState

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
}
