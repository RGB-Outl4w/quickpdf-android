package com.quickpdf.reader.pdf

import android.graphics.RectF

/** A word with its bounding box in page-relative coordinates (0..1). Used for OCR text. */
class WordBox(val start: Int, val end: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Searchable text of one page. */
class PageText(val text: String, val source: Int, val words: List<WordBox> = emptyList()) {
    val hasText: Boolean by lazy { text.count { !it.isWhitespace() } >= MIN_CHARS }

    /** [text] as search matches it; same length, so offsets carry over. */
    val folded: String by lazy { Search.fold(text) }

    /** Approximate per-character boxes (4 floats per char, NaN for characters without a box). */
    fun charBoxesFromWords(): FloatArray {
        val boxes = FloatArray(text.length * 4) { Float.NaN }
        for (w in words) {
            val n = (w.end - w.start).coerceAtLeast(1)
            val step = (w.right - w.left) / n
            for (i in w.start until w.end.coerceAtMost(text.length)) {
                val k = i - w.start
                boxes[i * 4] = w.left + step * k
                boxes[i * 4 + 1] = w.top
                boxes[i * 4 + 2] = w.left + step * (k + 1)
                boxes[i * 4 + 3] = w.bottom
            }
        }
        return boxes
    }

    companion object {
        const val SOURCE_PDF = 1
        const val SOURCE_OCR = 2
        const val MIN_CHARS = 16
    }
}

/** Merges the boxes of characters [start, end) into one rectangle per text line. */
fun highlightRects(boxes: FloatArray, start: Int, end: Int): List<RectF> {
    val out = ArrayList<RectF>()
    var cur: RectF? = null
    for (i in start until end) {
        val b = i * 4
        if (b + 3 >= boxes.size) break
        val l = boxes[b]
        if (l.isNaN()) continue
        val r = RectF(l, boxes[b + 1], boxes[b + 2], boxes[b + 3])
        val c = cur
        val sameLine = c != null && r.top < c.bottom && r.bottom > c.top && r.left >= c.left - 0.02f
        if (c != null && sameLine) {
            c.union(r)
        } else {
            c?.let(out::add)
            cur = r
        }
    }
    cur?.let(out::add)
    return out
}
