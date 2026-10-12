package com.quickpdf.reader.pdf

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.text.Normalizer

class SearchHit(
    val page: Int,
    val start: Int,
    val end: Int,
    val snippet: String,
    val snippetMatchStart: Int,
    val snippetMatchEnd: Int,
)

object Search {
    const val MAX_HITS = 5000

    private val WHITESPACE = Regex("\\s+")

    /** Optional hyphen + line break between two letters, so "information" also finds "infor-\nmation". */
    private const val HYPHEN_BREAK = "(?:[-­‐][ \\t]*\\n\\s*)?"

    private val FOLD = CharArray(0x2000) { foldChar(it.toChar()) }

    /**
     * Case- and accent-insensitive; whitespace in the query matches any whitespace (including line breaks).
     * Match against [PageText.folded].
     */
    fun regexFor(query: String): Regex? {
        val tokens = fold(query).trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return Regex(tokens.joinToString("\\s+", transform = ::tokenPattern))
    }

    /**
     * Lowercases and strips diacritics (é→e, ё→е; й is a separate letter and stays) one char for one char,
     * so offsets in the folded text are offsets in the original.
     */
    fun fold(s: String): String {
        val out = CharArray(s.length) { i ->
            val c = s[i]
            if (c.code < FOLD.size) FOLD[c.code] else c.lowercaseChar()
        }
        return String(out)
    }

    suspend fun run(pages: Array<PageText?>, regex: Regex): List<SearchHit> {
        val hits = ArrayList<SearchHit>()
        for ((index, page) in pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            page ?: continue
            for (m in regex.findAll(page.folded)) {
                hits += hit(index, page.text, m.range.first, m.range.last + 1)
                if (hits.size >= MAX_HITS) return hits
            }
        }
        return hits
    }

    private fun foldChar(c: Char): Char {
        val lower = c.lowercaseChar()
        if (lower == 'й') return lower
        return Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)[0]
    }

    /** Escapes [token], allowing a hyphenated line break between any two adjacent letters or digits. */
    private fun tokenPattern(token: String): String {
        val sb = StringBuilder()
        var from = 0
        for (i in 1 until token.length) {
            if (token[i - 1].isLetterOrDigit() && token[i].isLetterOrDigit()) {
                sb.append(Regex.escape(token.substring(from, i))).append(HYPHEN_BREAK)
                from = i
            }
        }
        return sb.append(Regex.escape(token.substring(from))).toString()
    }

    private fun hit(page: Int, text: String, start: Int, end: Int): SearchHit {
        var from = (start - 40).coerceAtLeast(0)
        var to = (end + 70).coerceAtMost(text.length)
        // Avoid cutting words in half at the snippet edges.
        if (from > 0) text.indexOf(' ', from).let { if (it in from until start) from = it + 1 }
        if (to < text.length) text.lastIndexOf(' ', to).let { if (it > end) to = it }
        val prefix = if (from > 0) "…" else ""
        val raw = text.substring(from, to).replace('\n', ' ')
        val snippet = prefix + raw + if (to < text.length) "…" else ""
        val ms = prefix.length + (start - from)
        return SearchHit(page, start, end, snippet, ms, ms + (end - start))
    }
}
