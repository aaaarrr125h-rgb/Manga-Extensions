package app.shura.manga.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import app.shura.source.host.UrlHttpTransport
import java.util.concurrent.Executors

/**
 * Loads cover thumbnails off the main thread and keeps a bounded bitmap cache.
 *
 * It tags each [ImageView] with the URL it is waiting for, so a recycled row never shows the
 * previous row's cover: a late result is dropped when the tag no longer matches. Decoding uses a
 * sampled size, because a full-resolution cover decoded for a small grid cell is the fastest way
 * to run a reader out of memory.
 */
object CoverLoader {

    private val transport = UrlHttpTransport()
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun cacheSizeKb(): Int {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return maxKb / 8
    }

    fun load(image: ImageView, url: String?, targetWidthPx: Int = 0, targetHeightPx: Int = 0) {
        if (url.isNullOrBlank()) {
            image.tag = null
            image.setImageDrawable(null)
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            image.tag = url
            image.setImageBitmap(cached)
            return
        }
        image.tag = url
        image.setImageDrawable(null)
        executor.execute {
            val bitmap = runCatching { fetch(url, targetWidthPx, targetHeightPx) }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap)
            main.post {
                if (image.tag == url && bitmap != null) {
                    image.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun fetch(url: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        val bytes = transport.get(url).body
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidthPx, targetHeightPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }
}
