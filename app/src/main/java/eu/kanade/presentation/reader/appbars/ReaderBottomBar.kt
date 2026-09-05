package eu.kanade.presentation.reader.appbars

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import reikai.presentation.reader.ReaderActionRow

@Composable
fun ReaderBottomBar(
    // RK -->
    enabledButtons: Set<String>,
    // RK <--
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // RK -->
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    keepScreenOn: Boolean,
    onClickKeepScreenOn: () -> Unit,
    // RK <--
    modifier: Modifier = Modifier,
) {
    // RK: delegate to the shared reader action row (also used by the novel reader) so the two bottom
    // bars can't drift. Manga-only buttons pass their state; novel-only buttons stay null here.
    ReaderActionRow(
        modifier = modifier,
        enabledButtons = enabledButtons,
        onClickChapterList = onClickChapterList,
        onClickWebView = onClickWebView,
        onClickBrowser = onClickBrowser,
        onClickShare = onClickShare,
        keepScreenOn = keepScreenOn,
        onClickKeepScreenOn = onClickKeepScreenOn,
        orientation = orientation,
        onClickOrientation = onClickOrientation,
        onClickSettings = onClickSettings,
        readingMode = readingMode,
        onClickReadingMode = onClickReadingMode,
        cropEnabled = cropEnabled,
        onClickCropBorder = onClickCropBorder,
    )
}
