package com.quickpdf.reader.pdf

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTest {
    private fun hits(text: String, query: String): List<SearchHit> = runBlocking {
        Search.run(arrayOf(PageText(text, PageText.SOURCE_PDF)), Search.regexFor(query)!!)
    }

    private fun find(text: String, query: String): List<String> = hits(text, query).map { text.substring(it.start, it.end) }

    @Test
    fun blankQueryHasNoRegex() = assertNull(Search.regexFor("  \n "))

    @Test
    fun ignoresCaseAndAccents() {
        assertEquals(listOf("Café"), find("Le Café noir", "cafe"))
        assertEquals(listOf("CAFE"), find("CAFE", "café"))
        assertEquals(listOf("Ångström"), find("1 Ångström", "angstrom"))
    }

    @Test
    fun yoMatchesYe() {
        assertEquals(listOf("ёлка"), find("ёлка", "елка"))
        assertEquals(listOf("Елка"), find("Елка", "Ёлка"))
    }

    @Test
    fun shortIStaysDistinct() {
        assertEquals(emptyList<String>(), find("мои книги", "мой"))
        assertEquals(listOf("Мой"), find("Мой дом", "мой"))
    }

    @Test
    fun whitespaceMatchesLineBreaks() = assertEquals(listOf("New\nYork"), find("in New\nYork city", "new york"))

    @Test
    fun matchesAcrossHyphenatedLineBreak() {
        assertEquals(listOf("infor-\nmation"), find("the infor-\nmation age", "information"))
        assertEquals(listOf("инфор-\n  мация"), find("инфор-\n  мация", "информация"))
        assertEquals(emptyList<String>(), find("infor- mation", "information"))
    }

    @Test
    fun foldKeepsLength() {
        val s = "Ёжик É ß ﬁ İ 😀 x́"
        assertEquals(s.length, Search.fold(s).length)
    }

    @Test
    fun snippetMarksTheMatch() {
        val hit = hits("the infor-\nmation age", "information").single()
        assertEquals("infor- mation", hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
    }

    @Test
    fun specialCharactersAreLiteral() = assertEquals(listOf("a+b (c)"), find("x = a+b (c)", "A+B (c)"))
}
