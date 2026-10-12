package com.quickpdf.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.currentCoroutineContext

/**
 * Renders pages with the platform [PdfRenderer] (fast, native, no extra libraries).
 * PdfRenderer is single-threaded, so all work runs on one dedicated thread.
 */
class PdfPageRenderer(pfd: ParcelFileDescriptor) : Closeable {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }.asCoroutineDispatcher()
    private val renderer = PdfRenderer(pfd)

    val pageCount: Int = renderer.pageCount

    /** Page height / width for every page. */
    val aspectRatios: FloatArray = FloatArray(pageCount) { i ->
        renderer.openPage(i).use { p -> if (p.width > 0) p.height.toFloat() / p.width else 1.414f }
    }

    private val cache = object : LruCache<Long, Bitmap>(cacheBudgetBytes()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    @Volatile
    private var closed = false

    fun cached(page: Int, widthPx: Int): Bitmap? = cache.get(key(page, widthPx))

    /** Best already-rendered bitmap of [page] at any resolution, used as a placeholder while re-rendering. */
    fun cachedAny(page: Int): Bitmap? =
        cache.snapshot().entries.filter { (it.key shr 32).toInt() == page }.maxByOrNull { it.value.width }?.value

    suspend fun render(page: Int, widthPx: Int): Bitmap {
        cached(page, widthPx)?.let { return it }
        return withContext(dispatcher) {
            cached(page, widthPx)?.let { return@withContext it }
            val bmp = renderNow(page, widthPx)
            cache.put(key(page, widthPx), bmp)
            bmp
        }
    }

    /** Renders without caching (for OCR). */
    suspend fun renderUncached(page: Int, widthPx: Int): Bitmap = withContext(dispatcher) { renderNow(page, widthPx) }

    private suspend fun renderNow(page: Int, widthPx: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Renderer closed" }
        var w = widthPx.coerceAtLeast(1)
        var h = (w * aspectRatios[page]).toInt().coerceAtLeast(1)
        // Keep huge zoom levels from allocating giant bitmaps.
        val pixels = w.toLong() * h
        if (pixels > MAX_PIXELS) {
            val f = kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels)
            w = (w * f).toInt().coerceAtLeast(1)
            h = (h * f).toInt().coerceAtLeast(1)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        renderer.openPage(page).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        return bmp
    }

    override fun close() {
        if (closed) return
        // Close on the render thread so an in-flight render never races the native teardown.
        dispatcher.executor.execute {
            closed = true
            runCatching { renderer.close() }
            cache.evictAll()
        }
        dispatcher.close()
    }

    private fun key(page: Int, widthPx: Int): Long = (page.toLong() shl 32) or widthPx.toLong()

    private companion object {
        const val MAX_PIXELS = 9_000_000L

        fun cacheBudgetBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(128L * 1024 * 1024).toInt()
    }
}
