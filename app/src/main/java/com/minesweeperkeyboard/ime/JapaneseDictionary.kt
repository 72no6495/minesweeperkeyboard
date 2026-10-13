package com.minesweeperkeyboard.ime

import java.nio.ByteBuffer

/**
 * tools/build_ja_dictionary.py が作る辞書ファイル（よみの UTF-8 順に並んだレコード）を
 * メモリに読み込まずに二分探索するリーダー（Android 非依存）。
 *
 * 形式："MSD1" / 件数 int32 / 各レコードの位置 int32[件数] / レコード（よみ \t コスト \t 単語 … \n）
 */
class SortedRecordFile(private val buf: ByteBuffer) {

    val count: Int
    private val dataStart: Int

    init {
        require(buf.limit() >= 8 && buf.get(0) == 'M'.code.toByte() && buf.get(1) == 'S'.code.toByte() &&
            buf.get(2) == 'D'.code.toByte() && buf.get(3) == '1'.code.toByte()) { "not a dictionary file" }
        count = buf.getInt(4)
        dataStart = 8 + 4 * count
    }

    private fun start(i: Int): Int = dataStart + buf.getInt(8 + 4 * i)

    /** レコード i のよみと key を UTF-8 のバイト順で比べる */
    private fun compare(i: Int, key: ByteArray): Int {
        var p = start(i)
        var k = 0
        while (true) {
            val b = buf.get(p).toInt() and 0xFF
            val readingEnded = b == TAB || b == NEWLINE
            if (k == key.size) return if (readingEnded) 0 else 1
            if (readingEnded) return -1
            val kb = key[k].toInt() and 0xFF
            if (b != kb) return b - kb
            p++
            k++
        }
    }

    private fun startsWith(i: Int, key: ByteArray): Boolean {
        val p = start(i)
        for (k in key.indices) {
            val b = buf.get(p + k).toInt() and 0xFF
            if (b == TAB || b == NEWLINE || b != (key[k].toInt() and 0xFF)) return false
        }
        return true
    }

    /** よみが key 以上になる最初のレコード */
    private fun lowerBound(key: ByteArray): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    data class Record(val reading: String, val words: List<Pair<Int, String>>)

    fun record(i: Int): Record {
        val s = start(i)
        var e = s
        while (buf.get(e).toInt() != NEWLINE) e++
        val bytes = ByteArray(e - s)
        for (j in bytes.indices) bytes[j] = buf.get(s + j)
        val parts = String(bytes, Charsets.UTF_8).split('\t')
        val words = ArrayList<Pair<Int, String>>((parts.size - 1) / 2)
        var j = 1
        while (j + 1 < parts.size) {
            words += (parts[j].toIntOrNull() ?: Int.MAX_VALUE) to parts[j + 1]
            j += 2
        }
        return Record(parts[0], words)
    }

    /** よみが完全一致するレコード */
    fun find(reading: String): Record? {
        val key = reading.toByteArray(Charsets.UTF_8)
        val i = lowerBound(key)
        return if (i < count && compare(i, key) == 0) record(i) else null
    }

    /** よみが prefix で始まるレコードを先頭から最大 limit 件 */
    fun withPrefix(prefix: String, limit: Int): List<Record> {
        val key = prefix.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Record>()
        var i = lowerBound(key)
        while (i < count && out.size < limit && startsWith(i, key)) {
            out += record(i)
            i++
        }
        return out
    }

    companion object {
        private const val TAB = '\t'.code
        private const val NEWLINE = '\n'.code
    }
}

/**
 * 日本語の予測変換（Mozc 辞書）。
 * よみが完全一致する単語（＝変換候補）を先に、続きの単語（＝予測候補）を後に返す。
 */
class JapaneseDictionary(private val main: SortedRecordFile, private val prefix: SortedRecordFile?) {

    fun lookup(reading: String, limit: Int = 24): List<String> {
        if (reading.isEmpty()) return emptyList()
        val result = LinkedHashSet<String>()

        main.find(reading)?.words?.forEach { result += it.second }
        // カタカナも候補に（辞書になくても出す）
        toKatakana(reading).takeIf { it != reading }?.let { result += it }

        val completions = if (reading.codePointCount(0, reading.length) <= PREFIX_MAX) {
            prefix?.find(reading)?.words.orEmpty()
        } else {
            // 長いよみは続きのレコードを走査してコストの小さい順に
            main.withPrefix(reading, SCAN_LIMIT)
                .filter { it.reading != reading }
                .flatMap { it.words }
                .sortedBy { it.first }
        }
        for ((_, word) in completions) {
            if (result.size >= limit) break
            result += word
        }
        return result.take(limit)
    }

    companion object {
        /** ja_prefix.bin に収録しているよみの長さ（tools/build_ja_dictionary.py の PREFIX_MAX と同じ） */
        const val PREFIX_MAX = 3
        private const val SCAN_LIMIT = 2000

        fun toKatakana(hiragana: String): String = buildString(hiragana.length) {
            for (ch in hiragana) append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
        }
    }
}
