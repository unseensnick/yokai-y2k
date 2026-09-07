package reikai.novel.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * The reader fonts the user added: what is installed, and importing, downloading and removing them.
 *
 * Files live in a `fonts` folder under the storage location the user picked, so they survive a
 * reinstall and can be managed with a file manager. Everything the renderers need a real path for is
 * mirrored into the app's own files directory, because a picked folder is usually a SAF tree with no
 * file path, and both `Typeface.createFromFile` and the WebView need one.
 */
@Inject
@SingleIn(AppScope::class)
class NovelFontManager(
    private val context: Context,
    private val storageManager: StorageManager,
    private val networkHelper: NetworkHelper,
) {

    private val mirrorDir: File by lazy { File(context.filesDir, "fonts").apply { mkdirs() } }

    private val typefaces = HashMap<String, Typeface?>()

    suspend fun installed(): List<NovelFont> = withContext(Dispatchers.IO) {
        storageManager.getFontsDirectory()?.listFiles().orEmpty()
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                if (!file.isFile || !isSupportedFontFile(name)) return@mapNotNull null
                NovelFont(name, fontDisplayName(name))
            }
            .sortedBy { it.displayName.lowercase() }
    }

    /**
     * Copies the picked file in, refusing anything that is not a readable TTF or OTF. The name is
     * checked first because it is what the reader stores, and the header after the copy because a
     * picker reports whatever the source called the file.
     */
    suspend fun import(uri: Uri): Result<NovelFont> = withContext(Dispatchers.IO) {
        val source = UniFile.fromUri(context, uri)
        val name = source?.name
        if (source == null || name == null) return@withContext Result.failure(FontError.Unreadable)
        if (!isSupportedFontFile(name)) return@withContext Result.failure(FontError.UnsupportedFormat)

        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
            ?: return@withContext Result.failure(FontError.Unreadable)
        if (!isSfntHeader(bytes)) return@withContext Result.failure(FontError.UnsupportedFormat)
        write(name, bytes)
    }

    /**
     * Fetches [family] from Google Fonts. The User-Agent is our own rather than the client default,
     * because Google picks the format from it and answers a modern browser entirely in woff2, which
     * Android cannot load. Measured: our default agent returns ten woff2 files and no TTF.
     */
    suspend fun download(family: String): Result<NovelFont> = withContext(Dispatchers.IO) {
        val css = runCatching {
            val request = Request.Builder()
                .url(CSS_URL.format(family.replace(' ', '+')))
                .header("User-Agent", "Reikai/${BuildConfig.VERSION_NAME}")
                .build()
            networkHelper.client.newCall(request).awaitSuccess().use { it.body.string() }
        }.getOrElse { return@withContext Result.failure(FontError.Offline) }

        val url = firstSupportedFontUrl(css) ?: return@withContext Result.failure(FontError.UnsupportedFormat)
        val bytes = runCatching {
            networkHelper.client.newCall(Request.Builder().url(url).build())
                .awaitSuccess()
                .use { it.body.bytes() }
        }.getOrElse { return@withContext Result.failure(FontError.Offline) }

        if (!isSfntHeader(bytes)) return@withContext Result.failure(FontError.UnsupportedFormat)
        val extension = url.substringBefore('?').substringAfterLast('.')
        write("${family.replace(' ', '_')}.$extension", bytes)
    }

    suspend fun delete(font: NovelFont): Boolean = withContext(Dispatchers.IO) {
        typefaces.remove(font.fileName)
        File(mirrorDir, font.fileName).delete()
        storageManager.getFontsDirectory()?.findFile(font.fileName)?.delete() == true
    }

    /**
     * The face for a font the user added, or null when its file has gone. Cached because a chapter
     * builds one view per few thousand characters and each would otherwise re-read the file.
     */
    fun typeface(fileName: String): Typeface? = typefaces.getOrPut(fileName) {
        val mirror = mirror(fileName) ?: return@getOrPut null
        runCatching { Typeface.createFromFile(mirror) }
            .onFailure { logcat(LogPriority.WARN, it) { "Unreadable reader font: $fileName" } }
            .getOrNull()
    }

    /** The `file://` URL the WebView renderers load the same font from, or null when its file has gone. */
    fun webUrl(fileName: String): String? = mirror(fileName)?.let { "file://${it.absolutePath}" }

    /**
     * The app-private copy, made on first use. A picked folder is normally a SAF tree, which has no
     * path for the framework to open, so neither renderer can read the original directly.
     */
    private fun mirror(fileName: String): File? {
        val local = File(mirrorDir, fileName)
        if (local.exists() && local.length() > 0) return local
        val source = storageManager.getFontsDirectory()?.findFile(fileName) ?: return null
        return runCatching {
            source.openInputStream().use { input -> local.outputStream().use(input::copyTo) }
            local
        }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not copy reader font: $fileName" } }
            .getOrNull()
    }

    private fun write(fileName: String, bytes: ByteArray): Result<NovelFont> {
        val dir = storageManager.getFontsDirectory() ?: return Result.failure(FontError.NoStorage)
        val target = dir.createFile(fileName) ?: return Result.failure(FontError.NoStorage)
        return runCatching {
            target.openOutputStream().use { it.write(bytes) }
            // The mirror and the cached face are stale the moment the file behind them changes.
            File(mirrorDir, fileName).delete()
            typefaces.remove(fileName)
            NovelFont(fileName, fontDisplayName(fileName))
        }.onFailure {
            target.delete()
            logcat(LogPriority.WARN, it) { "Could not save reader font: $fileName" }
        }
    }

    private companion object {
        const val CSS_URL = "https://fonts.googleapis.com/css2?family=%s:wght@400&display=swap"
    }
}

/** Why an import or a download did not produce a usable font, so the screen can say which. */
sealed class FontError : Exception() {
    data object Unreadable : FontError()
    data object UnsupportedFormat : FontError()
    data object Offline : FontError()
    data object NoStorage : FontError()
}
