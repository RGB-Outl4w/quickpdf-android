package com.quickpdf.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsTest {
    private fun doc(uri: String, at: Long, pinned: Boolean = false) =
        RecentDoc(uri, "$uri.pdf", 10, 0, at, emptySet(), 100, pinned)

    @Test
    fun pinnedKeepTheirOrderAboveTheRest() {
        val list = listOf(doc("a", 1), doc("p2", 5, pinned = true), doc("b", 9), doc("p1", 2, pinned = true))
        assertEquals(listOf("p2", "p1", "b", "a"), arrangeRecents(list, 50).map { it.uri })
    }

    @Test
    fun onlyUnpinnedAreCapped() {
        val list = List(5) { doc("d$it", it.toLong()) } + doc("p", 0, pinned = true)
        assertEquals(listOf("p", "d4", "d3"), arrangeRecents(list, 2).map { it.uri })
    }

    @Test
    fun sameFileUnderAnotherUriHasTheSameKey() {
        assertEquals(docKey("content://picker/1", "Book.pdf", 42), docKey("content://files/x", "Book.pdf", 42))
        assertEquals("content://a", docKey("content://a", "Book.pdf", -1))
    }
}
