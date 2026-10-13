package com.minesweeperkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwertyInputTest {

    private fun romaji(input: String): RomajiConverter =
        RomajiConverter().apply { input.forEach { input(it) } }

    @Test
    fun basicRomaji() {
        assertEquals("こんにちは", romaji("konnnichiha").kana)
        assertEquals("ありがとう", romaji("arigatou").kana)
        assertEquals("しんぶん", romaji("shinbunn").kana)
        assertEquals("きょう", romaji("kyou").kana)
        assertEquals("まいんすいーぱー", romaji("mainsui-pa-").kana)
    }

    @Test
    fun sokuonAndN() {
        assertEquals("きって", romaji("kitte").kana)
        assertEquals("まっちゃ", romaji("maccha").kana)
        assertEquals("かんじ", romaji("kanji").kana)
        assertEquals("こんや", romaji("kon'ya").kana)
        // n だけで止めると未確定、確定時に「ん」
        val conv = romaji("pan")
        assertEquals("ぱ", conv.kana)
        assertEquals("n", conv.pending)
        assertEquals("ぱん", conv.flush())
    }

    @Test
    fun pendingAndBackspace() {
        val conv = romaji("aky")
        assertEquals("あ", conv.kana)
        assertEquals("ky", conv.pending)
        assertEquals("あky", conv.display)
        conv.backspace()
        conv.backspace()
        assertEquals("あ", conv.display)
        conv.backspace()
        assertTrue(conv.isEmpty())
    }

    @Test
    fun invalidLettersPassThrough() {
        // "q" は "qa" などの途中なので未確定
        assertEquals("q", romaji("q").pending)
        // どの綴りにもならない "qk" の "q" はそのまま確定する
        assertEquals("qか", romaji("qka").kana)
    }

    @Test
    fun composerRomaji() {
        val c = Composer()
        c.language = InputLanguage.ROMAJI
        "mine".forEach { assertEquals("", c.type(it, shift = false)) }
        assertEquals("みね", c.composing)
        assertEquals("みね", c.reading)
        // 句読点で確定
        assertEquals("みね。", c.type('.', shift = false))
        assertTrue(c.isEmpty())
        "ka".forEach { c.type(it, false) }
        assertTrue(c.backspace())
        assertEquals("", c.composing)
        assertFalse(c.backspace())
    }

    @Test
    fun composerEnglish() {
        val c = Composer()
        c.language = InputLanguage.ENGLISH
        c.type('h', shift = true)
        c.type('i', shift = false)
        assertEquals("Hi", c.composing)
        assertEquals("Hi,", c.type(',', shift = false))
        c.type('g', false)
        assertEquals("g1", c.type('1', false))
        assertEquals("", c.finish())
    }

    @Test
    fun switchingLanguageClearsComposing() {
        val c = Composer()
        c.language = InputLanguage.ROMAJI
        c.type('a', false)
        c.language = InputLanguage.ENGLISH
        assertTrue(c.isEmpty())
    }

    @Test
    fun dictionaryLookup() {
        val dict = UserDictionary(
            """
            # comment
            おは,おはようございます
            おはな,お花
            まいん,マインスイーパー
            mine,minesweeper
            草
            """.trimIndent(),
        )
        assertEquals(5, dict.entries.size)
        assertEquals(listOf("おはようございます", "お花"), dict.lookup("おは"))
        // 完全一致が先
        assertEquals(listOf("お花"), dict.lookup("おはな"))
        assertEquals(listOf("minesweeper"), dict.lookup("MI"))
        assertEquals(listOf("草"), dict.lookup("草"))
        assertEquals(emptyList<String>(), dict.lookup(""))
        assertEquals(emptyList<String>(), dict.lookup("ぬ"))
        assertTrue(UserDictionary(UserDictionary.SAMPLE).entries.isNotEmpty())
    }

    @Test
    fun qwertyLayout() {
        assertEquals(4, QwertyLayout.rows)
        assertEquals(10, QwertyLayout.cols)
        assertTrue(QwertyLayout.ROWS.all { it.length == QwertyLayout.cols })
        assertEquals('q', QwertyLayout.keyAt(1, 0))
        assertEquals("Q", QwertyLayout.label(1, 0, InputLanguage.ENGLISH, shift = true))
        assertEquals("ー", QwertyLayout.label(2, 9, InputLanguage.ROMAJI, shift = false))
        assertEquals("、", QwertyLayout.label(3, 7, InputLanguage.ROMAJI, shift = false))
    }

    @Test
    fun qwertyBoardFitsEngine() {
        val e = MinesweeperEngine(QwertyLayout.rows, QwertyLayout.cols, 6)
        assertEquals(4, e.rows)
        assertEquals(10, e.cols)
        assertFalse(e.open(1, 4).exploded)
    }
}
