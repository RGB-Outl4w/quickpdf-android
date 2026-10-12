package com.quickpdf.reader.pdf

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

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

    /** Case-insensitive; whitespace in the query matches any whitespace (including line breaks). */
    fun regexFor(query: String): Regex? {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return Regex(tokens.joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    }

    suspend fun run(pages: Array<PageText?>, regex: Regex): List<SearchHit> {
        val hits = ArrayList<SearchHit>()
        for ((index, page) in pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            val text = page?.text ?: continue
            for (m in regex.findAll(text)) {
                hits += hit(index, text, m.range.first, m.range.last + 1)
                if (hits.size >= MAX_HITS) return hits
            }
        }
        return hits
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
