package com.quickpdf.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updates.isNewer("1.1.0", "1.0.1"))
        assertTrue(Updates.isNewer("v1.10.0", "1.9.2"))
        assertTrue(Updates.isNewer("2", "1.9.9"))
        assertFalse(Updates.isNewer("1.0.1", "1.0.1"))
        assertFalse(Updates.isNewer("1.0", "1.0.0"))
        assertFalse(Updates.isNewer("1.0.0", "1.0.1"))
        assertFalse(Updates.isNewer("1.1.0-beta", "1.1.0"))
    }

    @Test
    fun releaseNotesBecomePlainText() {
        val md = "## What's new\n\n* **Highlights** and [notes](https://x.y/z)\n- Library `search`\n\n\n\n**Full Changelog**: https://x.y/compare"
        assertEquals("What's new\n\n• Highlights and notes\n• Library search", Updates.plainNotes(md))
    }
}
