package com.quickpdf.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsTest {
    @Test
    fun singlePages() {
        assertEquals(listOf(0..0, 1..1, 2..2), spreadsFor(3, twoUp = false))
        assertEquals(2, spreadIndexOf(2, twoUp = false))
    }

    @Test
    fun coverAloneThenPairs() {
        assertEquals(listOf(0..0, 1..2, 3..4, 5..5), spreadsFor(6, twoUp = true))
        assertEquals(listOf(0..0, 1..2), spreadsFor(3, twoUp = true))
        assertEquals(emptyList<IntRange>(), spreadsFor(0, twoUp = true))
    }

    @Test
    fun everyPageMapsToItsSpread() {
        for (n in 1..9) {
            val spreads = spreadsFor(n, twoUp = true)
            for (p in 0 until n) assertEquals("page $p of $n", true, p in spreads[spreadIndexOf(p, twoUp = true)])
        }
    }
}
