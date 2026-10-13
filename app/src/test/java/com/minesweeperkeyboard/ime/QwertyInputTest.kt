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
    fun keyGrid() {
        val g = KeyGrid()
        assertEquals(4, g.rowCount)
        assertEquals(10, g.colCount)
        assertTrue(g.rows.all { it.length == g.colCount })
        assertEquals('q', g.keyAt(1, 0))
        assertEquals("Q", g.label(1, 0, InputLanguage.ENGLISH, shift = true, symbols = false))
        assertEquals("ー", g.label(2, 9, InputLanguage.ROMAJI, shift = false, symbols = false))
        assertEquals("、", g.label(3, 7, InputLanguage.ROMAJI, shift = false, symbols = false))
        assertEquals("，", g.label(3, 7, InputLanguage.ROMAJI, false, false, PunctuationStyle.COMMA_PERIOD))
        assertEquals("!", g.label(1, 0, InputLanguage.ENGLISH, shift = false, symbols = true))
    }

    @Test
    fun keyLayoutsAndNumberRow() {
        for (layout in KeyLayout.entries) {
            for (numberRow in listOf(true, false)) {
                val g = KeyGrid(layout, numberRow)
                assertEquals(if (numberRow) 4 else 3, g.rowCount)
                assertTrue(g.rows.all { it.length == KeyGrid.COLS })
                // ローマ字の「ー」と句読点が打てる
                val keys = g.rows.joinToString("")
                assertTrue("$layout has -", '-' in keys)
                assertTrue(',' in keys && '.' in keys)
                // 26 文字すべてある
                assertEquals(26, keys.filter { it.isLetter() }.toSet().size)
                for (r in 0 until g.rowCount) for (c in 0 until g.colCount) g.symbolAt(r, c)
            }
        }
        // 数字の行がなくても記号モードで数字が打てる
        assertEquals('1', KeyGrid(numberRow = false).symbolAt(0, 0))
        assertEquals('z', KeyGrid(KeyLayout.QWERTZ).keyAt(1, 5))
        assertEquals('a', KeyGrid(KeyLayout.AZERTY).keyAt(1, 0))
    }

    @Test
    fun composerPunctuationStyle() {
        val c = Composer()
        c.language = InputLanguage.ROMAJI
        c.punctuation = PunctuationStyle.COMMA_PERIOD
        "a".forEach { c.type(it, false) }
        assertEquals("あ．", c.type('.', false))
    }

    @Test
    fun learning() {
        val m = LearningModel(maxEntries = 3)
        m.learn("かんじ", "漢字")
        m.learn("かんじ", "漢字")
        m.learn("かんじ", "感じ")
        m.learn("かんじょう", "感情")
        assertEquals(listOf("漢字", "感じ", "感情"), m.lookup("かんじ"))
        assertEquals(listOf("感情"), m.lookup("かんじょ"))
        // 上限を超えたら一番長く使っていないものから消える
        m.learn("き", "木")
        assertEquals(3, m.size())
        val copy = LearningModel().apply { load(m.serialize()) }
        assertEquals(m.lookup("か"), copy.lookup("か"))
        m.clear()
        assertTrue(m.lookup("かんじ").isEmpty())
    }

    @Test
    fun qwertyBoardFitsEngine() {
        val g = KeyGrid(numberRow = false)
        val e = MinesweeperEngine(g.rowCount, g.colCount, 6)
        assertEquals(3, e.rows)
        assertEquals(10, e.cols)
        assertFalse(e.open(1, 4).exploded)
    }

    @Test
    fun flagKeyLeftOfZ() {
        val g = KeyGrid(flagKey = true)
        assertEquals(3 to 0, g.flagPosition)
        assertTrue(g.isFlagKey(3, 0))
        assertEquals('z', g.keyAt(3, 1))
        assertEquals("🚩", g.label(3, 0, InputLanguage.ENGLISH, shift = false, symbols = false))
        assertEquals("🚩", g.label(3, 0, InputLanguage.ENGLISH, shift = false, symbols = true))
        assertTrue(g.rows.all { it.length == KeyGrid.COLS })
        assertEquals(null, KeyGrid().flagPosition)
    }

    @Test
    fun readingVariantsCompletePendingRomaji() {
        val c = Composer()
        c.language = InputLanguage.ROMAJI
        "kanj".forEach { c.type(it, false) }
        // n の後に子音が来たので「ん」まで確定、j は未確定
        assertEquals("かん", c.reading)
        val variants = c.readingVariants
        assertTrue("かんじ" in variants)
        assertTrue("かんじゃ" in variants)
        val plain = Composer().apply { language = InputLanguage.ROMAJI; "kanji".forEach { type(it, false) } }
        assertEquals(listOf("かんじ"), plain.readingVariants)
    }

    @Test
    fun learnNextWord() {
        val m = LearningModel()
        m.learnNext("電車", "に")
        m.learnNext("電車", "に")
        m.learnNext("電車", "で")
        assertEquals(listOf("に", "で"), m.lookupNext("電車"))
        // 次の単語の学習は普通の検索には出てこない
        assertTrue(m.lookup("電").isEmpty())
    }
}
