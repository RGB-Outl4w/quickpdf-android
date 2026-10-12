package com.quickpdf.reader.ui.reader

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.quickpdf.reader.QuickPdfApp
import com.quickpdf.reader.data.Library
import com.quickpdf.reader.pdf.IndexCache
import com.quickpdf.reader.pdf.Ocr
import com.quickpdf.reader.pdf.OutlineItem
import com.quickpdf.reader.pdf.PageText
import com.quickpdf.reader.pdf.PdfPageRenderer
import com.quickpdf.reader.pdf.PdfTextSource
import com.quickpdf.reader.pdf.Search
import com.quickpdf.reader.pdf.SearchHit
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class IndexState(
    val pages: Array<PageText?>,
    val indexed: Int,
    val total: Int,
    val done: Boolean,
    /** Pages that have no text layer and were not OCR'd yet (scanned images). */
    val scannedPages: Int,
)

data class SearchState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val byPage: Map<Int, List<SearchHit>> = emptyMap(),
    val current: Int = -1,
    val searching: Boolean = false,
) {
    val currentHit: SearchHit? get() = hits.getOrNull(current)
}

data class OcrProgress(val done: Int, val total: Int)

sealed interface DocState {
    data object Loading : DocState
    data class Error(val message: String) : DocState
    class Ready(val name: String, val renderer: PdfPageRenderer, val initialPage: Int) : DocState
}

class ReaderViewModel(app: Application, val uri: Uri) : AndroidViewModel(app) {
    private val library = (app as QuickPdfApp).library

    private val _doc = MutableStateFlow<DocState>(DocState.Loading)
    val doc: StateFlow<DocState> = _doc.asStateFlow()

    private val _index = MutableStateFlow(IndexState(emptyArray(), 0, 0, false, 0))
    val index: StateFlow<IndexState> = _index.asStateFlow()

    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()

    private val _query = MutableStateFlow("")
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _ocr = MutableStateFlow<OcrProgress?>(null)
    val ocr: StateFlow<OcrProgress?> = _ocr.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val bookmarks: StateFlow<Set<Int>> = library.recents
        .map { list -> list.firstOrNull { it.uri == uri.toString() }?.bookmarks ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val nightPages: StateFlow<Boolean> = library.nightPages

    private var renderer: PdfPageRenderer? = null
    private var textSource: PdfTextSource? = null
    private var cache: IndexCache? = null
    private var pages: Array<PageText?> = emptyArray()
    private var ocrJob: Job? = null
    private val boxCache = LruCache<Int, FloatArray>(32)

    var currentPage = 0
        private set

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch { runSearches() }
    }

    // ---- Opening & indexing -------------------------------------------------------------------

    private suspend fun open() {
        val ctx = getApplication<Application>()
        try {
            val info = withContext(Dispatchers.IO) { Library.queryFileInfo(ctx, uri) }
            val r = withContext(Dispatchers.IO) {
                val pfd = openSeekable(ctx, info.size)
                textSource = openTextSource(ctx, pfd)
                try {
                    PdfPageRenderer(pfd)
                } catch (e: Throwable) {
                    runCatching { pfd.close() }
                    throw e
                }
            }
            renderer = r
            val recent = library.touch(uri, info.name, r.pageCount)
            currentPage = recent.lastPage.coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
            _doc.value = DocState.Ready(info.name, r, currentPage)
            cache = IndexCache(ctx.filesDir, uri.toString(), info.size)
            viewModelScope.launch { _outline.value = runCatching { textSource?.outline() }.getOrNull().orEmpty() }
            buildIndex(r.pageCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            _doc.value = DocState.Error(
                if (e.message?.contains("password", ignoreCase = true) == true) "This PDF is password-protected."
                else "No permission to open this file. Please open it again from your files."
            )
        } catch (e: Exception) {
            _doc.value = DocState.Error("Couldn't open this PDF.\n${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** PdfRenderer needs a seekable descriptor; streams from some apps are pipes, so copy those first. */
    private fun openSeekable(ctx: Context, size: Long): ParcelFileDescriptor {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("File not found")
        if (pfd.statSize >= 0) return pfd
        pfd.close()
        val copy = File(ctx.cacheDir, "opened").apply { mkdirs() }.resolve("${uri.toString().hashCode()}_$size.pdf")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun openTextSource(ctx: Context, pfd: ParcelFileDescriptor): PdfTextSource? {
        val doc = runCatching {
            // Read the file directly through the descriptor: no copy, low memory.
            pfd.dup().use { dup -> PDDocument.load(File("/proc/self/fd/${dup.fd}")) }
        }.recoverCatching {
            ctx.contentResolver.openInputStream(uri)!!.use {
                PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
            }
        }.getOrNull()
        return doc?.let(::PdfTextSource)
    }

    private suspend fun buildIndex(pageCount: Int) {
        val cached = withContext(Dispatchers.IO) { cache?.load(pageCount) }
        pages = cached ?: arrayOfNulls(pageCount)
        if (pages.all { it != null }) {
            publish(pageCount, done = true)
            return
        }
        val ts = textSource
        var lastPublish = 0L
        for (i in 0 until pageCount) {
            if (pages[i] != null) continue
            val text = if (ts == null) "" else withContext(Dispatchers.IO) { runCatching { ts.pageText(i) }.getOrDefault("") }
            pages[i] = PageText(text, PageText.SOURCE_PDF)
            val now = System.currentTimeMillis()
            if (now - lastPublish > 400) {
                publish(i + 1, done = false)
                lastPublish = now
            }
        }
        publish(pageCount, done = true)
        saveIndex()
    }

    private fun publish(indexed: Int, done: Boolean) {
        val snapshot = pages.copyOf()
        val scanned = snapshot.count { it != null && it.source == PageText.SOURCE_PDF && !it.hasText }
        _index.value = IndexState(snapshot, indexed, snapshot.size, done, scanned)
    }

    private suspend fun saveIndex() {
        val snapshot = pages.copyOf()
        withContext(Dispatchers.IO + NonCancellable) { cache?.save(snapshot) }
    }

    // ---- Search -------------------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    private suspend fun runSearches() {
        combine(_query.debounce { if (it.isBlank()) 0L else 250L }, _index) { q, idx -> q to idx }
            .collectLatest { (q, idx) ->
                val regex = Search.regexFor(q)
                if (regex == null) {
                    _search.value = SearchState(query = q)
                    return@collectLatest
                }
                _search.update { if (it.query == q) it.copy(searching = true) else SearchState(query = q, searching = true) }
                val hits = withContext(Dispatchers.Default) { Search.run(idx.pages, regex) }
                val byPage = hits.groupBy { it.page }
                _search.update { prev ->
                    val keep = prev.currentHit?.takeIf { prev.query == q }
                    val current = when {
                        hits.isEmpty() -> -1
                        keep != null -> hits.indexOfFirst { it.page == keep.page && it.start == keep.start }
                            .takeIf { it >= 0 } ?: 0
                        else -> hits.indexOfFirst { it.page >= currentPage }.takeIf { it >= 0 } ?: 0
                    }
                    SearchState(q, hits, byPage, current, searching = false)
                }
            }
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun selectHit(i: Int): SearchHit? {
        val s = _search.value
        if (i !in s.hits.indices) return null
        _search.value = s.copy(current = i)
        return s.hits[i]
    }

    fun nextHit(): SearchHit? = step(+1)

    fun prevHit(): SearchHit? = step(-1)

    private fun step(dir: Int): SearchHit? {
        val s = _search.value
        if (s.hits.isEmpty()) return null
        return selectHit(Math.floorMod(s.current + dir, s.hits.size))
    }

    /** Per-character boxes for highlighting search matches on [page], or null if unavailable. */
    suspend fun charBoxes(page: Int): FloatArray? {
        boxCache.get(page)?.let { return it }
        val pt = pages.getOrNull(page) ?: return null
        val boxes = if (pt.source == PageText.SOURCE_OCR) {
            pt.charBoxesFromWords()
        } else {
            val ts = textSource ?: return null
            val (text, b) = withContext(Dispatchers.IO) { runCatching { ts.pageTextWithBoxes(page) }.getOrNull() } ?: return null
            if (text.length != pt.text.length) return null
            b
        }
        boxCache.put(page, boxes)
        return boxes
    }

    // ---- OCR ----------------------------------------------------------------------------------

    fun startOcr() {
        if (ocrJob?.isActive == true) return
        val r = renderer ?: return
        val targets = pages.indices.filter { val p = pages[it]; p == null || (p.source != PageText.SOURCE_OCR && !p.hasText) }
        if (targets.isEmpty()) {
            _messages.tryEmit("All pages already have searchable text")
            return
        }
        ocrJob = viewModelScope.launch {
            val ocr = Ocr()
            var recognized = 0
            try {
                _ocr.value = OcrProgress(0, targets.size)
                var lastPublish = 0L
                for ((k, page) in targets.withIndex()) {
                    val bmp = r.renderUncached(page, OCR_WIDTH)
                    val text = try {
                        ocr.recognize(bmp)
                    } finally {
                        bmp.recycle()
                    }
                    pages[page] = text
                    if (text.hasText) recognized++
                    boxCache.remove(page)
                    _ocr.value = OcrProgress(k + 1, targets.size)
                    val now = System.currentTimeMillis()
                    if (now - lastPublish > 1000) {
                        publish(pages.size, done = true)
                        lastPublish = now
                    }
                    if (k % 20 == 19) saveIndex()
                }
                _messages.tryEmit("Text recognized on $recognized of ${targets.size} pages")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Text recognition failed (needs Google Play services): ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ocr.close()
                _ocr.value = null
                publish(pages.size, done = true)
                saveIndex()
            }
        }
    }

    fun cancelOcr() {
        ocrJob?.cancel()
    }

    // ---- Reading state ------------------------------------------------------------------------

    fun onPageChanged(page: Int) {
        currentPage = page
        library.setLastPage(uri, page)
    }

    fun toggleBookmark(page: Int) = library.toggleBookmark(uri, page)

    fun setNightPages(enabled: Boolean) = library.setNightPages(enabled)

    fun pageText(page: Int): PageText? = pages.getOrNull(page)

    override fun onCleared() {
        renderer?.close()
        textSource?.close()
    }

    companion object {
        private const val OCR_WIDTH = 1700

        fun factory(app: Application, uri: Uri) = viewModelFactory {
            initializer { ReaderViewModel(app, uri) }
        }
    }
}
