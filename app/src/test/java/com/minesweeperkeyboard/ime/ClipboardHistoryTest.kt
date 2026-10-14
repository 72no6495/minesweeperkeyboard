package com.minesweeperkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryTest {

    private fun ClipboardHistory.texts() = items().map { it.text }

    @Test
    fun newestFirstAndDeduplicated() {
        val h = ClipboardHistory()
        h.add("a")
        h.add("b")
        h.add("c")
        assertEquals(listOf("c", "b", "a"), h.texts())
        // もう一度コピーしたら先頭へ
        assertTrue(h.add("a"))
        assertEquals(listOf("a", "c", "b"), h.texts())
        assertFalse(h.add("a"))
        assertFalse(h.add("   "))
    }

    @Test
    fun pinnedStayOnTopAndSurviveTrimming() {
        val h = ClipboardHistory(maxUnpinned = 2)
        h.add("keep")
        h.togglePin("keep")
        h.add("x")
        h.add("y")
        h.add("z")
        assertEquals(listOf("keep", "z", "y"), h.texts())
        assertTrue(h.items().first().pinned)
        // ピン留めしたものをもう一度コピーしてもピンは外れない
        h.add("keep")
        assertTrue(h.items().first { it.text == "keep" }.pinned)
        h.clearUnpinned()
        assertEquals(listOf("keep"), h.texts())
        h.togglePin("keep")
        assertFalse(h.items().first().pinned)
        h.remove("keep")
        assertTrue(h.items().isEmpty())
    }

    @Test
    fun serializeRoundTripKeepsNewlinesAndTabs() {
        val h = ClipboardHistory()
        h.add("line1\nline2\ttab \\ backslash")
        h.add("📌 pinned")
        h.togglePin("📌 pinned")
        val copy = ClipboardHistory().apply { load(h.serialize()) }
        assertEquals(h.items(), copy.items())
        assertEquals("line1\nline2\ttab \\ backslash", copy.items().last().text)
    }
}
