package com.quickpdf.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.currentCoroutineContext

/**
 * Renders pages with the platform [PdfRenderer] (fast, native, no extra libraries).
 * PdfRenderer is single-threaded, so all work runs on one dedicated thread.
 * [password] is only used on Android 15+, where PdfRenderer can open encrypted files.
 */
class PdfPageRenderer(pfd: ParcelFileDescriptor, password: String? = null) : Closeable {
    private val renderer =
        if (password != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
        } else {
            PdfRenderer(pfd)
        }
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }.asCoroutineDispatcher()

    val pageCount: Int = renderer.pageCount

    /**
     * Page height / width for every page. Opening every page up front is slow on long books, so this starts
     * as page 1's ratio everywhere and is corrected by [measurePages] and whenever a page renders.
     * Only written on the render thread.
     */
    val aspectRatios: SnapshotStateList<Float> = mutableStateListOf<Float>().apply {
        val first = if (pageCount > 0) renderer.openPage(0).use { ratioOf(it) } else DEFAULT_RATIO
        addAll(List(pageCount) { first })
    }

    private val cache = object : LruCache<Long, Bitmap>(cacheBudgetBytes()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    /** Every width rendered so far: just a few (zoom buckets, thumbnails), so [cachedAny] can probe them. */
    private val widths: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var closed = false

    fun cached(page: Int, widthPx: Int): Bitmap? = cache.get(key(page, widthPx))

    /** Best already-rendered bitmap of [page] at any resolution, used as a placeholder while re-rendering. */
    fun cachedAny(page: Int): Bitmap? = widths.sortedDescending().firstNotNullOfOrNull { cached(page, it) }

    suspend fun render(page: Int, widthPx: Int): Bitmap {
        cached(page, widthPx)?.let { return it }
        return withContext(dispatcher) {
            cached(page, widthPx)?.let { return@withContext it }
            val bmp = renderNow(page, widthPx)
            widths += widthPx
            cache.put(key(page, widthPx), bmp)
            bmp
        }
    }

    /** Renders without caching (for OCR). */
    suspend fun renderUncached(page: Int, widthPx: Int): Bitmap = withContext(dispatcher) { renderNow(page, widthPx) }

    /**
     * Measures every page, starting at [fromPage] and wrapping around, in small batches so renders
     * queued in between aren't held up.
     */
    suspend fun measurePages(fromPage: Int) {
        val start = fromPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val order = (start until pageCount) + (0 until start)
        for (batch in order.chunked(MEASURE_BATCH)) {
            withContext(dispatcher) {
                for (page in batch) {
                    if (closed) return@withContext
                    renderer.openPage(page).use { measured(page, it) }
                }
            }
        }
    }

    private suspend fun renderNow(page: Int, widthPx: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Renderer closed" }
        renderer.openPage(page).use { p ->
            var w = widthPx.coerceAtLeast(1)
            var h = (w * measured(page, p)).toInt().coerceAtLeast(1)
            // Keep huge zoom levels from allocating giant bitmaps.
            val pixels = w.toLong() * h
            if (pixels > MAX_PIXELS) {
                val f = kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels)
                w = (w * f).toInt().coerceAtLeast(1)
                h = (h * f).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    private fun measured(page: Int, p: PdfRenderer.Page): Float {
        val ratio = ratioOf(p)
        if (aspectRatios[page] != ratio) aspectRatios[page] = ratio
        return ratio
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
        const val MEASURE_BATCH = 32
        const val DEFAULT_RATIO = 1.414f

        fun ratioOf(p: PdfRenderer.Page): Float = if (p.width > 0) p.height.toFloat() / p.width else DEFAULT_RATIO

        fun cacheBudgetBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(128L * 1024 * 1024).toInt()
    }
}
