package reikai.presentation.reader.text

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Html
import android.util.Base64
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import coil3.asDrawable
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import coil3.size.Dimension as CoilDimension
import coil3.size.Size as CoilSize

/** Stands in for an image while it loads, so the span keeps its place and can be swapped in later. */
class DrawableWrapper : Drawable() {
    var innerDrawable: Drawable? = null

    override fun draw(canvas: Canvas) {
        innerDrawable?.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        innerDrawable?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        innerDrawable?.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSPARENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}

/**
 * Resolves the images in a chapter for `Html.fromHtml`.
 *
 * Ported from tsundoku (`textview/NovelImageGetter.kt`), with their host Activity replaced by a
 * [Context] and the Referer taken from the chapter's own base URL, which the session already
 * resolved. Their page-loader scheme is not taken: it serves images out of a local archive, which
 * our content never produces (a downloaded chapter carries its images inline as data URLs).
 */
class NovelImageGetter(
    private val context: Context,
    private val scope: CoroutineScope,
    contentWidthPx: Int,
    /** Some hosts refuse an image without one, so the chapter's own site is sent. */
    private val refererUrl: String?,
    private val resolveView: (Drawable) -> TextView?,
) : Html.ImageGetter {

    private val contentWidth: Int =
        contentWidthPx.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels

    private data class PendingLoad(val source: String, val wrapper: DrawableWrapper)

    private val pendingLoads = mutableListOf<PendingLoad>()
    private val dirtyViews = ConcurrentHashMap.newKeySet<TextView>()
    private val outstandingLoads = AtomicInteger(0)

    /**
     * Called by the parser, off the main thread. A network image only gets queued here: starting it
     * now would race the views it has to measure against, so [startLoading] does that.
     */
    override fun getDrawable(source: String?): Drawable {
        val wrapper = DrawableWrapper()
        val placeholderHeight = (PLACEHOLDER_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        val placeholder = Color.LTGRAY.toDrawable()
        placeholder.setBounds(0, 0, contentWidth, placeholderHeight)
        wrapper.innerDrawable = placeholder
        wrapper.setBounds(0, 0, contentWidth, placeholderHeight)

        if (source.isNullOrBlank()) return wrapper
        when {
            source.startsWith("data:") -> decodeInlineImage(source, wrapper)
            source.startsWith("http://") || source.startsWith("https://") ->
                pendingLoads += PendingLoad(source, wrapper)
            source.startsWith("//") -> pendingLoads += PendingLoad("https:$source", wrapper)
            else -> logcat(LogPriority.DEBUG) { "Skipping unsupported image source" }
        }
        return wrapper
    }

    /** Main thread: the queued images load once the views they measure against exist. */
    fun startLoading() {
        outstandingLoads.set(pendingLoads.size)
        pendingLoads.forEach { (source, wrapper) -> loadFromNetwork(source, wrapper) }
        pendingLoads.clear()
    }

    /** A downloaded chapter stores its images inline, so this is the offline path. */
    private fun decodeInlineImage(source: String, wrapper: DrawableWrapper) {
        try {
            val commaIndex = source.indexOf(',')
            if (commaIndex <= 0) return
            val bytes = Base64.decode(source.substring(commaIndex + 1), Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return
            fitToWidth(bitmap.toDrawable(context.resources), wrapper)
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "Failed to decode an inline chapter image" }
        }
    }

    private fun loadFromNetwork(imageUrl: String, wrapper: DrawableWrapper) {
        scope.launch {
            try {
                val headers = NetworkHeaders.Builder().apply {
                    set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    refererUrl?.let { set("Referer", it) }
                }.build()
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .httpHeaders(headers)
                    .size(CoilSize(CoilDimension.Pixels(contentWidth), CoilDimension.Undefined))
                    .build()
                val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources)
                if (drawable != null) fitToWidthAndInvalidate(drawable, wrapper)
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG, e) { "Failed to load a chapter image" }
            } finally {
                withContext(Dispatchers.Main) { onLoadFinished() }
            }
        }
    }

    /** Re-measuring once at the end, rather than per image, so a chapter of pictures reflows once. */
    private fun onLoadFinished() {
        if (outstandingLoads.decrementAndGet() > 0) return
        val views = dirtyViews.toList()
        dirtyViews.clear()
        views.forEach { if (it.isAttachedToWindow) it.requestLayout() }
    }

    private fun fitToWidth(drawable: Drawable, wrapper: DrawableWrapper) {
        val imgWidth = drawable.intrinsicWidth
        val imgHeight = drawable.intrinsicHeight
        if (imgWidth <= 0 || imgHeight <= 0) return
        val width = contentWidth.coerceAtLeast(1)
        val height = (imgHeight * (width.toFloat() / imgWidth)).toInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, width, height)
        wrapper.innerDrawable = drawable
        wrapper.setBounds(0, 0, width, height)
    }

    private fun fitToWidthAndInvalidate(drawable: Drawable, wrapper: DrawableWrapper) {
        fitToWidth(drawable, wrapper)
        val textView = resolveView(wrapper) ?: return
        textView.invalidate()
        dirtyViews.add(textView)
    }

    /** Decodes no larger than the column it will be drawn in, which is what keeps a big scan cheap. */
    private fun sampleSizeFor(sourceWidth: Int): Int {
        if (sourceWidth <= 0 || contentWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= contentWidth) sample *= 2
        return sample
    }

    private companion object {
        const val PLACEHOLDER_HEIGHT_DP = 200
    }
}
