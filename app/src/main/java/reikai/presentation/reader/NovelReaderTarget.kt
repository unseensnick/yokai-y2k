package reikai.presentation.reader

import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.app.di.appGraph
import reikai.domain.novel.NovelRenderingMode
import reikai.presentation.novel.reader.NovelReaderScreen

/**
 * Where a novel opens, decided in one place so the three call sites cannot drift apart. The two
 * readers launch differently, a Voyager screen against an Activity, so this answers what to open and
 * the caller does the opening.
 */
fun novelReaderTarget(
    context: Context,
    novelId: Long,
    chapterId: Long,
    sourceScoped: Boolean = false,
): NovelReaderTarget {
    val mode = context.appGraph.novelPreferences.readerRenderingMode().get()
    return when (mode) {
        NovelRenderingMode.LEGACY ->
            NovelReaderTarget.LegacyScreen(NovelReaderScreen(novelId, chapterId, sourceScoped))
        // Both shared-host modes launch identically; which renderer the host installs is the
        // viewport's question, answered in NovelReaderProvider.createViewport.
        NovelRenderingMode.WEBVIEW, NovelRenderingMode.NATIVE ->
            NovelReaderTarget.Host(ReaderActivity.newNovelIntent(context, novelId, chapterId, sourceScoped))
    }
}

sealed interface NovelReaderTarget {
    data class LegacyScreen(val screen: Screen) : NovelReaderTarget

    data class Host(val intent: Intent) : NovelReaderTarget
}

/** Opens the target the Voyager way. The recents engine has no navigator and hands its target back
 *  to the screen instead, which is why the opening is not folded into [novelReaderTarget]. */
fun NovelReaderTarget.open(context: Context, navigator: Navigator) = when (this) {
    is NovelReaderTarget.LegacyScreen -> navigator.push(screen)
    is NovelReaderTarget.Host -> context.startActivity(intent)
}
