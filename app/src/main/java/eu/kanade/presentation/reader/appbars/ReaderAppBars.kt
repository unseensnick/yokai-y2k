package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.ReaderBarsFadeSpec
import reikai.presentation.reader.ReaderBarsSlideSpec
import reikai.presentation.reader.readerBarEnter
import reikai.presentation.reader.readerBarExit
import reikai.presentation.reader.readerChromeColor
import tachiyomi.presentation.core.components.material.padding

// RK: bar animation specs + scrim color moved to reikai.presentation.reader.ReaderChrome so the manga
// and novel readers share one definition. Values unchanged.

@Composable
fun ReaderAppBars(
    visible: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    chapterNavigatorType: ChapterNavigatorType,
    verticalNavigatorHeight: Float,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    progress: ChapterProgress?,
    onSeek: (ChapterProgress) -> Unit,
    onSeekFinished: () -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // RK -->
    bottomButtons: Set<String>,
    onClickChapterList: () -> Unit,
    keepScreenOn: Boolean,
    onClickKeepScreenOn: () -> Unit,
    onClickTextSize: (() -> Unit)?,
    onClickTheme: (() -> Unit)?,
    autoScrollActive: Boolean,
    onClickAutoScroll: (() -> Unit)?,
    // RK <--
) {
    val backgroundColor = readerChromeColor() // RK: shared scrim (see ReaderChrome)

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            // RK: shared top-bar transition (see ReaderChrome)
            enter = readerBarEnter(fromBottom = false),
            exit = readerBarExit(fromBottom = false),
        ) {
            ReaderTopBar(
                modifier = Modifier
                    .background(backgroundColor)
                    .clickable(onClick = onClickTopAppBar),
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                navigateUp = navigateUp,
                bookmarked = bookmarked,
                onToggleBookmarked = onToggleBookmarked,
                onOpenInWebView = onOpenInWebView,
                onOpenInBrowser = onOpenInBrowser,
                onShare = onShare,
            )
        }

        if (!chapterNavigatorType.isHorizontal()) {
            val sliderOnLeft = chapterNavigatorType == ChapterNavigatorType.VERTICAL_LEFT
            CompositionLocalProvider(
                LocalLayoutDirection provides if (sliderOnLeft) LayoutDirection.Ltr else LayoutDirection.Rtl,
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    AnimatedVisibility(
                        visible = visible,
                        // RK: shared bar animation specs (see ReaderChrome); horizontal is manga-only.
                        enter = slideInHorizontally(ReaderBarsSlideSpec) { if (sliderOnLeft) -it else it } +
                            fadeIn(ReaderBarsFadeSpec),
                        exit = slideOutHorizontally(ReaderBarsSlideSpec) { if (sliderOnLeft) -it else it } +
                            fadeOut(ReaderBarsFadeSpec),
                    ) {
                        Row {
                            Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
                            Box(
                                modifier = Modifier.fillMaxHeight(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                ChapterNavigator(
                                    modifier = Modifier.fillMaxHeight(verticalNavigatorHeight),
                                    type = chapterNavigatorType,
                                    onNextChapter = onNextChapter,
                                    enabledNext = enabledNext,
                                    onPreviousChapter = onPreviousChapter,
                                    enabledPrevious = enabledPrevious,
                                    progress = progress,
                                    onSeek = onSeek,
                                    onSeekFinished = onSeekFinished,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = visible,
            // RK: shared bottom-bar transition (see ReaderChrome)
            enter = readerBarEnter(fromBottom = true),
            exit = readerBarExit(fromBottom = true),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                if (chapterNavigatorType.isHorizontal()) {
                    ChapterNavigator(
                        type = chapterNavigatorType,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        progress = progress,
                        onSeek = onSeek,
                        onSeekFinished = onSeekFinished,
                    )
                }
                ReaderBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .padding(horizontal = MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    // RK -->
                    enabledButtons = bottomButtons,
                    // RK <--
                    readingMode = readingMode,
                    onClickReadingMode = onClickReadingMode,
                    orientation = orientation,
                    onClickOrientation = onClickOrientation,
                    cropEnabled = cropEnabled,
                    onClickCropBorder = onClickCropBorder,
                    onClickSettings = onClickSettings,
                    // RK -->
                    onClickChapterList = onClickChapterList,
                    onClickWebView = onOpenInWebView,
                    onClickBrowser = onOpenInBrowser,
                    onClickShare = onShare,
                    keepScreenOn = keepScreenOn,
                    onClickKeepScreenOn = onClickKeepScreenOn,
                    onClickTextSize = onClickTextSize,
                    onClickTheme = onClickTheme,
                    autoScrollActive = autoScrollActive,
                    onClickAutoScroll = onClickAutoScroll,
                    // RK <--
                )
            }
        }
    }
}
