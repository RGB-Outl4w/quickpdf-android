package com.quickpdf.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionTest {
    // Two lines: "ab cd" on top (y 0.1..0.2), "ef" below (y 0.3..0.4); the space has no box.
    private val text = "ab cd\nef"
    private val nan = Float.NaN
    private val boxes = floatArrayOf(
        0.1f, 0.1f, 0.2f, 0.2f, // a
        0.2f, 0.1f, 0.3f, 0.2f, // b
        nan, nan, nan, nan, //    space
        0.4f, 0.1f, 0.5f, 0.2f, // c
        0.5f, 0.1f, 0.6f, 0.2f, // d
        nan, nan, nan, nan, //    line break
        0.1f, 0.3f, 0.2f, 0.4f, // e
        0.2f, 0.3f, 0.3f, 0.4f, // f
    )

    @Test
    fun charUnderPoint() {
        assertEquals(3, charIndexAt(boxes, 0.45f, 0.15f))
        assertEquals(7, charIndexAt(boxes, 0.25f, 0.35f))
    }

    @Test
    fun nearestCharPrefersSameLine() {
        // Far right of the second line is still on that line, not on the closer-looking first line.
        assertEquals(7, charIndexAt(boxes, 0.9f, 0.35f))
        assertEquals(-1, charIndexAt(FloatArray(0), 0.5f, 0.5f))
    }

    @Test
    fun wordAroundIndex() {
        assertEquals(3 until 5, wordRangeAt(text, 4))
        assertEquals(2 until 3, wordRangeAt(text, 2))
        assertEquals(IntRange.EMPTY, wordRangeAt(text, 99))
    }

    @Test
    fun cleanJoinsHyphenatedBreaks() {
        assertEquals("information age is here", cleanSelection("infor-\nmation age\nis  here "))
        assertEquals("well- known", cleanSelection("well- known"))
        assertEquals("1 - 2", cleanSelection("1 -\n2"))
    }
}
