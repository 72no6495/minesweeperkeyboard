package com.minesweeperkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class JapaneseDictionaryTest {

    /** tools/build_ja_dictionary.py と同じ形式のファイルを作る */
    private fun file(vararg records: Pair<String, List<Pair<Int, String>>>): SortedRecordFile {
        val lines = records
            .map { (reading, words) ->
                reading.toByteArray() to (reading + words.joinToString("") { "\t${it.first}\t${it.second}" } + "\n").toByteArray()
            }
            .sortedWith { a, b -> compareUnsigned(a.first, b.first) }
            .map { it.second }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeBytes("MSD1")
            d.writeInt(lines.size)
            var pos = 0
            for (l in lines) {
                d.writeInt(pos)
                pos += l.size
            }
            lines.forEach { d.write(it) }
        }
        return SortedRecordFile(ByteBuffer.wrap(out.toByteArray()))
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private val main = file(
        "かんじ" to listOf(100 to "感じ", 200 to "漢字"),
        "かんじる" to listOf(150 to "感じる"),
        "かんじょう" to listOf(120 to "感情", 300 to "勘定"),
        "かん" to listOf(50 to "感", 60 to "缶"),
        "き" to listOf(10 to "木"),
        "にほんご" to listOf(80 to "日本語"),
        "にほんごきょういく" to listOf(500 to "日本語教育"),
        "にほんごがくしゅう" to listOf(400 to "日本語学習"),
    )
    private val prefix = file(
        "かん" to listOf(100 to "感じ", 120 to "感情", 150 to "感じる"),
        "かんじ" to listOf(120 to "感情", 150 to "感じる"),
    )

    @Test
    fun findExact() {
        assertEquals(listOf(100 to "感じ", 200 to "漢字"), main.find("かんじ")?.words)
        assertEquals("き", main.find("き")?.reading)
        assertNull(main.find("かんぜん"))
        assertNull(main.find(""))
        assertEquals(8, main.count)
    }

    @Test
    fun withPrefix() {
        val readings = main.withPrefix("かんじ", 10).map { it.reading }
        assertEquals(listOf("かんじ", "かんじょう", "かんじる"), readings)
        assertEquals(1, main.withPrefix("かん", 1).size)
        assertTrue(main.withPrefix("ぬ", 10).isEmpty())
    }

    @Test
    fun lookupShortReadingUsesPrefixTable() {
        val dict = JapaneseDictionary(main, prefix)
        // 完全一致（変換）→ カタカナ → 続き（予測）
        assertEquals(listOf("感じ", "漢字", "カンジ", "感情", "感じる"), dict.lookup("かんじ"))
        assertEquals(listOf("感", "缶", "カン", "感じ", "感情", "感じる"), dict.lookup("かん"))
    }

    @Test
    fun lookupLongReadingScansMainFile() {
        val dict = JapaneseDictionary(main, prefix)
        assertEquals(listOf("日本語", "ニホンゴ", "日本語学習", "日本語教育"), dict.lookup("にほんご"))
        assertEquals(listOf("日本語", "ニホンゴ"), dict.lookup("にほんご", limit = 2))
        assertTrue(dict.lookup("").isEmpty())
    }

    @Test
    fun katakana() {
        assertEquals("マインスイーパー", JapaneseDictionary.toKatakana("まいんすいーぱー"))
        assertEquals("ヴ", JapaneseDictionary.toKatakana("ゔ"))
    }
}
