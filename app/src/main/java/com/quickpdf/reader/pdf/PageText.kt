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

/**
 * Index of the character whose box is nearest to page-relative ([x], [y]), preferring the line under the point;
 * -1 if the page has no boxes.
 */
fun charIndexAt(boxes: FloatArray, x: Float, y: Float): Int {
    var best = -1
    var bestScore = Float.MAX_VALUE
    for (i in 0 until boxes.size / 4) {
        val l = boxes[i * 4]
        if (l.isNaN()) continue
        val t = boxes[i * 4 + 1]
        val r = boxes[i * 4 + 2]
        val b = boxes[i * 4 + 3]
        val dx = if (x < l) l - x else if (x > r) x - r else 0f
        val dy = if (y < t) t - y else if (y > b) y - b else 0f
        val score = dy * 8 + dx
        if (score < bestScore) {
            bestScore = score
            best = i
        }
    }
    return best
}

/** The word around [index] as [start, end); a single character if it isn't part of a word. */
fun wordRangeAt(text: String, index: Int): IntRange {
    if (index !in text.indices) return IntRange.EMPTY
    if (!text[index].isLetterOrDigit()) return index until index + 1
    var s = index
    var e = index + 1
    while (s > 0 && text[s - 1].isLetterOrDigit()) s--
    while (e < text.length && text[e].isLetterOrDigit()) e++
    return s until e
}

/** Selected page text as one would paste it: hyphenated line breaks joined, other line breaks become spaces. */
fun cleanSelection(text: String): String =
    text.replace(Regex("(?<=\\p{L})[-\u00AD\u2010][ \\t]*\\n\\s*(?=\\p{L})"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
