package com.quickpdf.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    // A 200 x 100 crop box starting at (10, 20).
    private val crop = floatArrayOf(10f, 20f, 210f, 120f)

    private fun at(x: Float, y: Float, rotation: Int) = toPageRelative(x, y, crop, rotation)

    @Test
    fun unrotatedFlipsY() {
        assertEquals(0f to 0f, at(10f, 120f, 0)) // top-left
        assertEquals(1f to 1f, at(210f, 20f, 0)) // bottom-right
        assertEquals(0.25f to 0.5f, at(60f, 70f, 0))
    }

    @Test
    fun rotationTurnsClockwise() {
        // The unrotated top-left corner ends up top-right at 90°, bottom-right at 180° and bottom-left at 270°.
        assertEquals(1f to 0f, at(10f, 120f, 90))
        assertEquals(1f to 1f, at(10f, 120f, 180))
        assertEquals(0f to 1f, at(10f, 120f, 270))
        assertEquals(at(10f, 120f, 270), at(10f, 120f, -90))
    }
}
