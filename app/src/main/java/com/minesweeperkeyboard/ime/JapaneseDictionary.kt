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

    fun lookup(reading: String, limit: Int = 60): List<String> {
        if (reading.isEmpty()) return emptyList()
        val result = LinkedHashSet<String>()

        val exact = main.find(reading)?.words.orEmpty()
        // 単語に区切って変換した文（例：わたしはがくせい → 私は学生）。完全一致する単語がなければ先頭に
        val sentence = convert(reading)
        if (exact.isEmpty() && sentence != null) result += sentence
        exact.forEachIndexed { i, (_, word) ->
            result += word
            if (i == 1 && sentence != null) result += sentence
        }
        if (sentence != null) result += sentence
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

    /**
     * よみを単語に区切り、Mozc のコストの合計が最小になる組み合わせで変換する（2 語以上になったときだけ返す）。
     * 辞書にない 1 文字はそのまま残す。
     */
    fun convert(reading: String): String? {
        val n = reading.length
        if (n < 2 || n > MAX_CONVERT_LENGTH) return null
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val from = IntArray(n + 1) { -1 }
        val word = arrayOfNulls<String>(n + 1)
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] == Int.MAX_VALUE) continue
            for (len in 1..minOf(MAX_SEGMENT, n - i)) {
                val sub = reading.substring(i, i + len)
                val (cost, w) = segment(sub) ?: if (len == 1) UNKNOWN_COST to sub else continue
                val total = best[i] + cost + SEGMENT_PENALTY
                if (total < best[i + len]) {
                    best[i + len] = total
                    from[i + len] = i
                    word[i + len] = w
                }
            }
        }
        if (best[n] == Int.MAX_VALUE) return null
        val parts = ArrayList<String>()
        var p = n
        while (p > 0) {
            parts += word[p]!!
            p = from[p]
        }
        if (parts.size < 2) return null
        return parts.asReversed().joinToString("")
    }

    /**
     * 文の一部（よみ）をどう書くかとそのコスト。
     * Mozc の辞書は文法（品詞のつながり）のコストなしではひらがなの断片が安くなりすぎるので、
     * 助詞・助動詞は辞書より優先してひらがなにし、それ以外は最低コストを設けて、漢字の表記を少し優先する。
     */
    private fun segment(sub: String): Pair<Int, String>? {
        if (sub in FUNCTION_WORDS) return FUNCTION_COST to sub
        val words = main.find(sub)?.words ?: return null
        val bestCost = words.first().first
        val chosen = words.firstOrNull { it.first <= bestCost + PREFER_MARGIN && it.second.any(::isKanji) }
            ?: words.firstOrNull { it.first <= bestCost + PREFER_MARGIN && it.second.none(::isKatakana) }
            ?: words.first()
        return maxOf(bestCost, MIN_CONTENT_COST) to chosen.second
    }

    companion object {
        private const val MAX_CONVERT_LENGTH = 24
        private const val MAX_SEGMENT = 10
        /** 1 語増えるごとに足すコスト（細かく区切りすぎないように） */
        private const val SEGMENT_PENALTY = 2000
        private const val UNKNOWN_COST = 20000
        private const val FUNCTION_COST = 300
        private const val MIN_CONTENT_COST = 3000
        /** これだけのコスト差なら漢字（またはカタカナ以外）の表記を優先する */
        private const val PREFER_MARGIN = 1500

        /** ひらがなのまま使う助詞・助動詞など */
        private val FUNCTION_WORDS = setOf(
            "は", "が", "を", "に", "へ", "で", "と", "も", "の", "や", "か", "ね", "よ", "な", "わ", "さ",
            "ので", "のに", "から", "まで", "より", "けど", "けれど", "ながら", "だけ", "しか", "ばかり", "など", "って",
            "だ", "です", "でした", "でしょう", "だった", "ます", "ました", "ません", "ませんでした", "ましょう",
            "たい", "ない", "なかった", "た", "て", "ている", "ています", "ていた", "ていました", "てる",
            "いる", "います", "いた", "いました", "ある", "あります", "あった", "ありました",
            "する", "します", "した", "しました", "して", "しない", "される", "された",
            "れる", "られる", "せる", "させる", "う", "よう", "ください", "ございます", "ございました",
        )

        private fun isKanji(ch: Char) = ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch == '々'
        private fun isKatakana(ch: Char) = ch in '\u30A1'..'\u30FA'

        /** ja_prefix.bin に収録しているよみの長さ（tools/build_ja_dictionary.py の PREFIX_MAX と同じ） */
        const val PREFIX_MAX = 3
        private const val SCAN_LIMIT = 2000

        fun toKatakana(hiragana: String): String = buildString(hiragana.length) {
            for (ch in hiragana) append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
        }
    }
}
