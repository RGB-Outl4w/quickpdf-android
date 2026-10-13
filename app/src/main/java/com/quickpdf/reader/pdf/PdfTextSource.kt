package com.quickpdf.reader.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.text.Normalizer

class OutlineItem(val title: String, val page: Int, val depth: Int)

/** A link area on a page in page-relative coordinates (0..1), to another [page] of the document or to a web [url]. */
class PageLink(val left: Float, val top: Float, val right: Float, val bottom: Float, val page: Int, val url: String?) {
    fun contains(x: Float, y: Float, slop: Float = 0.01f) =
        x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
}

/**
 * Maps a point in PDF user space (y up) onto the displayed page (0..1, y down), which PdfRenderer shows cropped to
 * [crop] = (left, bottom, right, top) and turned clockwise by [rotation] degrees.
 */
fun toPageRelative(x: Float, y: Float, crop: FloatArray, rotation: Int): Pair<Float, Float> {
    val u = (x - crop[0]) / (crop[2] - crop[0]).coerceAtLeast(1f)
    val v = (crop[3] - y) / (crop[3] - crop[1]).coerceAtLeast(1f)
    return when (Math.floorMod(rotation, 360)) {
        90 -> (1 - v) to u
        180 -> (1 - u) to (1 - v)
        270 -> v to (1 - u)
        else -> u to v
    }
}

/** Text layer and table of contents via PdfBox. Not thread-safe internally, so every call is serialized. */
class PdfTextSource(private val doc: PDDocument) : Closeable {
    private val lock = Mutex()

    val pageCount: Int get() = doc.numberOfPages

    suspend fun pageText(page: Int): String = lock.withLock {
        extract(page, withBoxes = false).first
    }

    /** Text plus per-character boxes (4 floats per char, page-relative). */
    suspend fun pageTextWithBoxes(page: Int): Pair<String, FloatArray> = lock.withLock {
        val (text, boxes) = extract(page, withBoxes = true)
        text to boxes!!
    }

    private val pageIndex: Map<PDPage, Int> by lazy {
        HashMap<PDPage, Int>().apply { doc.pages.forEachIndexed { i, p -> put(p, i) } }
    }

    suspend fun outline(): List<OutlineItem> = lock.withLock {
        val root = doc.documentCatalog.documentOutline ?: return@withLock emptyList()
        val out = ArrayList<OutlineItem>()
        fun walk(node: PDOutlineNode, depth: Int) {
            for (child in node.children()) {
                if (out.size >= MAX_OUTLINE) return
                val page = runCatching { child.findDestinationPage(doc) }.getOrNull()?.let { pageIndex[it] } ?: -1
                val title = child.title?.trim().orEmpty()
                if (title.isNotEmpty()) out += OutlineItem(title, page, depth)
                if (depth < 6) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        out
    }

    /** Links on [page] to other pages and to web addresses; other kinds of links are left out. */
    suspend fun links(page: Int): List<PageLink> = lock.withLock {
        val p = doc.getPage(page)
        val cb = p.cropBox
        val crop = floatArrayOf(cb.lowerLeftX, cb.lowerLeftY, cb.upperRightX, cb.upperRightY)
        p.annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
            val r = link.rectangle ?: return@mapNotNull null
            var target = -1
            var url: String? = null
            when (val action = link.action) {
                is PDActionURI -> url = action.uri?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                is PDActionGoTo -> target = pageOf(runCatching { action.destination }.getOrNull())
                null -> target = pageOf(runCatching { link.destination }.getOrNull())
            }
            if (url == null && target !in 0 until doc.numberOfPages) return@mapNotNull null
            val (x1, y1) = toPageRelative(r.lowerLeftX, r.lowerLeftY, crop, p.rotation)
            val (x2, y2) = toPageRelative(r.upperRightX, r.upperRightY, crop, p.rotation)
            PageLink(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), target, url)
        }
    }

    private fun pageOf(dest: PDDestination?): Int {
        val d = when (dest) {
            is PDPageDestination -> dest
            is PDNamedDestination -> runCatching { doc.documentCatalog.findNamedDestinationPage(dest) }.getOrNull()
            else -> null
        } ?: return -1
        return d.page?.let { pageIndex[it] } ?: d.pageNumber
    }

    private fun extract(page: Int, withBoxes: Boolean): Pair<String, FloatArray?> {
        val stripper = PageStripper(withBoxes)
        stripper.setStartPage(page + 1)
        stripper.setEndPage(page + 1)
        stripper.getText(doc)
        return stripper.sb.toString() to stripper.boxes?.toArray()
    }

    override fun close() {
        runCatching { doc.close() }
    }

    private companion object {
        const val MAX_OUTLINE = 3000
    }
}

/**
 * Builds the page text ourselves (instead of using getText's output) so every character
 * can be mapped back to its position on the page for search highlighting.
 */
private class PageStripper(captureBoxes: Boolean) : PDFTextStripper() {
    val sb = StringBuilder()
    val boxes: FloatList? = if (captureBoxes) FloatList() else null
    private var dispW = 1f
    private var dispH = 1f

    override fun startPage(page: PDPage) {
        super.startPage(page)
        val box = page.cropBox
        val rotated = page.rotation % 180 != 0
        dispW = (if (rotated) box.height else box.width).coerceAtLeast(1f)
        dispH = (if (rotated) box.width else box.height).coerceAtLeast(1f)
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        for (tp in textPositions) {
            val raw = tp.unicode ?: continue
            val u = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            if (u.isEmpty()) continue
            sb.append(u)
            val b = boxes ?: continue
            val h = maxOf(tp.height, tp.fontSizeInPt * 0.75f)
            val left = tp.x / dispW
            val right = (tp.x + tp.width) / dispW
            val top = (tp.y - h) / dispH
            val bottom = (tp.y + h * 0.2f) / dispH
            val step = (right - left) / u.length
            for (k in u.indices) b.add(left + step * k, top, left + step * (k + 1), bottom)
        }
    }

    override fun writeWordSeparator() = separator(' ')

    override fun writeLineSeparator() = separator('\n')

    private fun separator(c: Char) {
        sb.append(c)
        boxes?.add(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    }
}

private class FloatList {
    private var data = FloatArray(1024)
    private var size = 0

    fun add(a: Float, b: Float, c: Float, d: Float) {
        if (size + 4 > data.size) data = data.copyOf(data.size * 2)
        data[size] = a; data[size + 1] = b; data[size + 2] = c; data[size + 3] = d
        size += 4
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
