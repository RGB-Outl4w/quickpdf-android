package com.quickpdf.reader.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.text.Normalizer

class OutlineItem(val title: String, val page: Int, val depth: Int)

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

    suspend fun outline(): List<OutlineItem> = lock.withLock {
        val root = doc.documentCatalog.documentOutline ?: return@withLock emptyList()
        val pageIndex = HashMap<PDPage, Int>()
        doc.pages.forEachIndexed { i, p -> pageIndex[p] = i }
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
