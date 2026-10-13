package com.minesweeperkeyboard.ime

/**
 * ローマ字 → ひらがなの逐次変換（Android 非依存）。
 *
 * 1 文字ずつ [input] すると、確定したかなは [kana] に、まだ確定していない
 * アルファベット（例："k"、"ky"）は [pending] に残る。
 * - 子音の重ね（"tt" など）は「っ」
 * - "nn"、または n の後に子音が来たら「ん」
 */
class RomajiConverter {

    private val kanaBuf = StringBuilder()
    private val pendingBuf = StringBuilder()

    val kana: String get() = kanaBuf.toString()
    val pending: String get() = pendingBuf.toString()

    /** 画面に表示する変換中の文字列（確定かな＋未確定アルファベット） */
    val display: String get() = kana + pending

    fun isEmpty() = kanaBuf.isEmpty() && pendingBuf.isEmpty()

    fun clear() {
        kanaBuf.setLength(0)
        pendingBuf.setLength(0)
    }

    fun input(ch: Char) {
        pendingBuf.append(ch.lowercaseChar())
        resolve()
    }

    /** 1 文字削除（未確定アルファベットがあればそれを、なければかなを 1 文字） */
    fun backspace() {
        when {
            pendingBuf.isNotEmpty() -> pendingBuf.setLength(pendingBuf.length - 1)
            kanaBuf.isNotEmpty() -> {
                val end = kanaBuf.length
                val start = if (end >= 2 && Character.isSurrogatePair(kanaBuf[end - 2], kanaBuf[end - 1])) end - 2 else end - 1
                kanaBuf.setLength(start)
            }
        }
    }

    /** 未確定部分も含めて確定させた文字列（末尾の "n" は「ん」） */
    fun flush(): String {
        if (pendingBuf.toString() == "n") {
            kanaBuf.append('ん')
        } else {
            kanaBuf.append(pendingBuf)
        }
        pendingBuf.setLength(0)
        return kana
    }

    private fun resolve() {
        while (pendingBuf.isNotEmpty()) {
            val p = pendingBuf.toString()
            TABLE[p]?.let {
                kanaBuf.append(it)
                pendingBuf.setLength(0)
                return
            }
            if (p.length >= 2) {
                val a = p[0]
                val b = p[1]
                // 促音：同じ子音が続いたら「っ」
                if (a == b && a.isLetter() && a !in VOWELS && a != 'n') {
                    kanaBuf.append('っ')
                    pendingBuf.deleteCharAt(0)
                    continue
                }
                // n の後に母音・y・n 以外が来たら「ん」
                if (a == 'n' && b !in VOWELS && b != 'y' && b != 'n') {
                    kanaBuf.append('ん')
                    pendingBuf.deleteCharAt(0)
                    continue
                }
            }
            if (p in PREFIXES) return
            // どの綴りにもならない文字はそのまま確定
            kanaBuf.append(p[0])
            pendingBuf.deleteCharAt(0)
        }
    }

    companion object {
        // 変換表の行（あいうえお）と同じ順
        private const val VOWELS = "aiueo"

        val TABLE: Map<String, String> = buildMap {
            fun row(consonant: String, kana: String) {
                // kana は「あいうえお」の順の 5 文字（存在しないものは空白）
                for (i in 0 until 5) {
                    val k = kana[i]
                    if (k != ' ') put(consonant + VOWELS[i], k.toString())
                }
            }
            fun yoon(consonant: String, base: String) {
                put(consonant + "a", base + "ゃ")
                put(consonant + "i", base + "ぃ")
                put(consonant + "u", base + "ゅ")
                put(consonant + "e", base + "ぇ")
                put(consonant + "o", base + "ょ")
            }
            row("", "あいうえお")
            row("k", "かきくけこ"); row("g", "がぎぐげご")
            row("s", "さしすせそ"); row("z", "ざじずぜぞ")
            row("t", "たちつてと"); row("d", "だぢづでど")
            row("n", "なにぬねの")
            row("h", "はひふへほ"); row("b", "ばびぶべぼ"); row("p", "ぱぴぷぺぽ")
            row("m", "まみむめも")
            row("y", "や ゆ よ")
            row("r", "らりるれろ")
            row("w", "わ   を")
            put("wi", "うぃ"); put("we", "うぇ")
            row("l", "ぁぃぅぇぉ"); row("x", "ぁぃぅぇぉ")
            put("fa", "ふぁ"); put("fi", "ふぃ"); put("fu", "ふ"); put("fe", "ふぇ"); put("fo", "ふぉ")
            put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")
            put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("ji", "じ")
            put("ca", "か"); put("cu", "く"); put("co", "こ"); put("qa", "くぁ"); put("qi", "くぃ"); put("qu", "く"); put("qe", "くぇ"); put("qo", "くぉ")
            yoon("ky", "き"); yoon("gy", "ぎ"); yoon("sy", "し"); yoon("zy", "じ"); yoon("jy", "じ")
            yoon("ty", "ち"); yoon("cy", "ち"); yoon("dy", "ぢ"); yoon("ny", "に"); yoon("hy", "ひ")
            yoon("by", "び"); yoon("py", "ぴ"); yoon("my", "み"); yoon("ry", "り")
            put("sha", "しゃ"); put("shu", "しゅ"); put("she", "しぇ"); put("sho", "しょ")
            put("cha", "ちゃ"); put("chu", "ちゅ"); put("che", "ちぇ"); put("cho", "ちょ")
            put("ja", "じゃ"); put("ju", "じゅ"); put("je", "じぇ"); put("jo", "じょ")
            put("thi", "てぃ"); put("thu", "てゅ"); put("dhi", "でぃ"); put("dhu", "でゅ")
            put("twu", "とぅ"); put("dwu", "どぅ")
            put("ltu", "っ"); put("xtu", "っ"); put("ltsu", "っ")
            put("lya", "ゃ"); put("lyu", "ゅ"); put("lyo", "ょ"); put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ")
            put("lwa", "ゎ"); put("xwa", "ゎ")
            put("nn", "ん"); put("n'", "ん"); put("xn", "ん")
            put("-", "ー")
        }

        /** 変換表のキーの途中までの綴り（まだ確定できない入力） */
        private val PREFIXES: Set<String> = buildSet {
            for (key in TABLE.keys) for (i in 1 until key.length) add(key.substring(0, i))
        }
    }
}
