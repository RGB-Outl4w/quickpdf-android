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
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Annotation
import com.quickpdf.reader.data.AnnotationStore
import com.quickpdf.reader.data.Library
import com.quickpdf.reader.data.Settings
import com.quickpdf.reader.pdf.IndexCache
import com.quickpdf.reader.pdf.MlKitOcr
import com.quickpdf.reader.pdf.Ocr
import com.quickpdf.reader.pdf.OutlineItem
import com.quickpdf.reader.pdf.PageLink
import com.quickpdf.reader.pdf.PageText
import com.quickpdf.reader.pdf.PdfPageRenderer
import com.quickpdf.reader.pdf.PdfTextSource
import com.quickpdf.reader.pdf.Search
import com.quickpdf.reader.pdf.SearchHit
import com.quickpdf.reader.pdf.TessData
import com.quickpdf.reader.pdf.TesseractOcr
import com.quickpdf.reader.pdf.cleanSelection
import com.quickpdf.reader.ui.UiText
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

/** Pages recognized, or kilobytes downloaded while [downloadingModels]. */
/** Selected text on one page, [start, end) in that page's indexed text. */
data class TextSelection(val page: Int, val start: Int, val end: Int)

data class OcrProgress(val done: Int, val total: Int, val downloadingModels: Boolean = false)

sealed interface DocState {
    data object Loading : DocState
    class Error(val message: UiText) : DocState
    data class NeedsPassword(val wrong: Boolean) : DocState
    class Ready(val name: String, val renderer: PdfPageRenderer, val initialPage: Int) : DocState
}

class ReaderViewModel(app: Application, val uri: Uri, private val startPage: Int?) : AndroidViewModel(app) {
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

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiText> = _messages

    private var annotationStore: AnnotationStore? = null
    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations.asStateFlow()

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    /** The document's URI in the library; another one than [uri] if it was first opened through another app. */
    private val libraryUri = MutableStateFlow(uri.toString())

    val bookmarks: StateFlow<Set<Int>> = combine(library.recents, libraryUri) { list, u -> list.firstOrNull { it.uri == u }?.bookmarks ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val settings: StateFlow<Settings> = library.settings

    private var renderer: PdfPageRenderer? = null
    private var textSource: PdfTextSource? = null
    private var cache: IndexCache? = null
    private var pages: Array<PageText?> = emptyArray()
    private var ocrJob: Job? = null
    private var password: String? = null
    private val boxCache = LruCache<Int, FloatArray>(32)
    private val linkCache = LruCache<Int, List<PageLink>>(64)

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
                try {
                    val pdfBox = openPdfBox(ctx, pfd)
                    textSource = pdfBox?.let(::PdfTextSource)
                    openRenderer(ctx, pfd, pdfBox)
                } catch (e: Throwable) {
                    runCatching { pfd.close() }
                    throw e
                }
            }
            renderer = r
            // Notes and the index used to be stored per URI; pick up those of every URI this file was opened under.
            val legacyUris = library.sameDocument(uri, info.name, info.size).map { it.uri } + uri.toString()
            val recent = library.touch(uri, info.name, r.pageCount, info.size)
            libraryUri.value = recent.uri
            val store = AnnotationStore(ctx.filesDir, recent.key, legacyUris)
            _annotations.value = withContext(Dispatchers.IO) { store.load() }
            annotationStore = store
            currentPage = (startPage ?: recent.lastPage).coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
            _doc.value = DocState.Ready(info.name, r, currentPage)
            // Don't keep the text of password-protected documents on disk.
            cache = if (password == null) IndexCache(ctx.filesDir, recent.key, info.size, legacyUris) else null
            viewModelScope.launch { r.measurePages(fromPage = currentPage) }
            viewModelScope.launch { _outline.value = runCatching { textSource?.outline() }.getOrNull().orEmpty() }
            buildIndex(r.pageCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidPasswordException) {
            _doc.value = DocState.NeedsPassword(wrong = password != null)
        } catch (e: SecurityException) {
            _doc.value = DocState.Error(
                if (e.message?.contains("password", ignoreCase = true) == true) UiText.Res(R.string.error_password_protected)
                else UiText.Res(R.string.error_no_permission)
            )
        } catch (e: Exception) {
            _doc.value = DocState.Error(UiText.Res(R.string.error_open_failed, e.message ?: e.javaClass.simpleName))
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

    fun submitPassword(value: String) {
        password = value
        _doc.value = DocState.Loading
        viewModelScope.launch { open() }
    }

    /**
     * PdfBox document for text and the outline, or null if PdfBox can't read the file (pages still render).
     * Throws [InvalidPasswordException] when the file needs a password, or a different one.
     */
    private fun openPdfBox(ctx: Context, pfd: ParcelFileDescriptor): PDDocument? {
        val pw = password.orEmpty()
        return runCatching {
            // Read the file directly through the descriptor: no copy, low memory.
            pfd.dup().use { dup -> PDDocument.load(File("/proc/self/fd/${dup.fd}"), pw) }
        }.recoverCatching { e ->
            if (e is InvalidPasswordException) throw e
            ctx.contentResolver.openInputStream(uri)!!.use {
                PDDocument.load(it, pw, MemoryUsageSetting.setupTempFileOnly())
            }
        }.onFailure { if (it is InvalidPasswordException) throw it }.getOrNull()
    }

    /** Before Android 15 PdfRenderer can't open encrypted files, so those render from a decrypted private copy. */
    private fun openRenderer(ctx: Context, pfd: ParcelFileDescriptor, pdfBox: PDDocument?): PdfPageRenderer {
        try {
            return PdfPageRenderer(pfd, password)
        } catch (e: SecurityException) {
            if (pdfBox == null || !pdfBox.isEncrypted) throw e
            val copy = decryptedCopy(ctx)
            pdfBox.isAllSecurityToBeRemoved = true
            pdfBox.save(copy)
            pfd.close()
            return PdfPageRenderer(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
        }
    }

    private fun decryptedCopy(ctx: Context) =
        File(ctx.cacheDir, "opened").apply { mkdirs() }.resolve("${uri.toString().hashCode()}_decrypted.pdf")

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

    /** Links on [page], empty if there are none or PdfBox can't read the file. */
    suspend fun links(page: Int): List<PageLink> {
        linkCache.get(page)?.let { return it }
        val ts = textSource ?: return emptyList()
        val links = withContext(Dispatchers.IO) { runCatching { ts.links(page) }.getOrDefault(emptyList()) }
        linkCache.put(page, links)
        return links
    }

    // ---- OCR ----------------------------------------------------------------------------------

    /** [cyrillic] uses Tesseract (models downloaded on first use) instead of ML Kit's Latin recognizer. */
    fun startOcr(cyrillic: Boolean) {
        if (ocrJob?.isActive == true) return
        val r = renderer ?: return
        val targets = pages.indices.filter { val p = pages[it]; p == null || (p.source != PageText.SOURCE_OCR && !p.hasText) }
        if (targets.isEmpty()) {
            _messages.tryEmit(UiText.Res(R.string.ocr_nothing_to_do))
            return
        }
        ocrJob = viewModelScope.launch {
            var ocr: Ocr? = null
            var recognized = 0
            try {
                val engine: Ocr = if (cyrillic) {
                    val totalKb = (TessData.totalBytes / 1024).toInt()
                    val path = TessData.ensure(getApplication<Application>()) { bytes ->
                        _ocr.value = OcrProgress((bytes / 1024).toInt(), totalKb, downloadingModels = true)
                    }
                    withContext(Dispatchers.Default) { TesseractOcr(path) }
                } else {
                    MlKitOcr()
                }
                ocr = engine
                _ocr.value = OcrProgress(0, targets.size)
                var lastPublish = 0L
                for ((k, page) in targets.withIndex()) {
                    val bmp = r.renderUncached(page, OCR_WIDTH)
                    val text = try {
                        engine.recognize(bmp)
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
                _messages.tryEmit(UiText.Plural(R.plurals.ocr_done, targets.size, recognized, targets.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                _messages.tryEmit(
                    when {
                        !cyrillic -> UiText.Res(R.string.ocr_failed_mlkit, reason)
                        e is IOException -> UiText.Res(R.string.ocr_download_failed, reason)
                        else -> UiText.Res(R.string.ocr_failed, reason)
                    }
                )
            } finally {
                ocr?.close()
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
        library.setLastPage(libraryUri.value, page)
    }

    fun toggleBookmark(page: Int) = library.toggleBookmark(libraryUri.value, page)

    fun updateSettings(change: (Settings) -> Settings) = library.updateSettings(change)

    fun pageText(page: Int): PageText? = pages.getOrNull(page)

    // ---- Selection, highlights and notes ------------------------------------------------------

    fun select(selection: TextSelection?) {
        _selection.value = selection
    }

    /** The selected text, ready to copy or share. */
    fun selectedText(): String = _selection.value?.let { selectedText(it) }.orEmpty()

    fun selectedText(sel: TextSelection): String {
        val text = pages.getOrNull(sel.page)?.text ?: return ""
        return cleanSelection(text.substring(sel.start.coerceIn(0, text.length), sel.end.coerceIn(0, text.length)))
    }

    fun addAnnotation(sel: TextSelection, color: Int, note: String = ""): Annotation {
        val now = System.currentTimeMillis()
        val a = Annotation(now, sel.page, sel.start, sel.end, color, note.trim(), selectedText(sel), now)
        saveAnnotations((_annotations.value + a).sortedWith(compareBy({ it.page }, { it.start })))
        _selection.value = null
        return a
    }

    fun updateAnnotation(a: Annotation) = saveAnnotations(_annotations.value.map { if (it.id == a.id) a.copy(note = a.note.trim()) else it })

    fun deleteAnnotation(id: Long) = saveAnnotations(_annotations.value.filterNot { it.id == id })

    private fun saveAnnotations(list: List<Annotation>) {
        _annotations.value = list
        val store = annotationStore ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) { store.save(list) }
    }

    override fun onCleared() {
        renderer?.close()
        textSource?.close()
        library.flush()
        decryptedCopy(getApplication<Application>()).delete()
    }

    companion object {
        private const val OCR_WIDTH = 1700

        fun factory(app: Application, uri: Uri, startPage: Int?) = viewModelFactory {
            initializer { ReaderViewModel(app, uri, startPage) }
        }
    }
}
