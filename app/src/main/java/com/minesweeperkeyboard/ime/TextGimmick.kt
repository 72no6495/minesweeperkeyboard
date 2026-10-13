package com.minesweeperkeyboard.ime

import kotlin.random.Random

enum class ChainMode(val key: String) {
    /** 開いたマスの数だけ文字を入力 */
    PER_CELL("per_cell"),
    /** 1回のタップにつき1回だけ入力 */
    ONCE("once");

    companion object {
        fun fromKey(key: String?): ChainMode = entries.firstOrNull { it.key == key } ?: PER_CELL
    }
}

/** 安全なマスを開いたときに入力する文字の決め方 */
enum class TextMode(val key: String) {
    /** 設定した文字 */
    FIXED("fixed"),
    /** 文字表をマスに順番に割り当て、そのマスの文字を入力 */
    COORDINATE_TABLE("coordinate_table"),
    /** マスの座標そのもの（例：B3） */
    COORDINATE_LABEL("coordinate_label"),
    /** 候補からランダムに1文字 */
    RANDOM("random");

    companion object {
        fun fromKey(key: String?): TextMode = entries.firstOrNull { it.key == key } ?: FIXED
    }
}

data class TextSettings(
    val mode: TextMode = TextMode.FIXED,
    val customText: String = TextGimmick.DEFAULT_FIXED_TEXT,
    /** 座標モードでマスに割り当てる文字（[TextGimmick.splitUnits] 済み） */
    val charTable: List<String> = TextGimmick.splitUnits(TextGimmick.DEFAULT_CHAR_TABLE),
    /** ランダムモードの候補（[TextGimmick.splitUnits] 済み） */
    val randomPool: List<String> = TextGimmick.splitUnits(TextGimmick.DEFAULT_RANDOM_POOL),
    val chainMode: ChainMode = ChainMode.PER_CELL,
    /** 1回の連鎖で入力する最大数（0 なら無制限） */
    val maxRepeat: Int = 0,
)

/** 「開いたマス」→「入力する文字列」の変換（Android 非依存でテスト可能） */
object TextGimmick {

    const val DEFAULT_FIXED_TEXT = "ｗ"
    const val DEFAULT_CHAR_TABLE =
        "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
    const val DEFAULT_RANDOM_POOL =
        "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"

    /**
     * @param opened この操作で開いたマス（開いた順）。ONCE のときは先頭（タップしたマス）だけ使う
     * @param cols 盤面の列数（座標→文字表のインデックス計算用）
     */
    fun textForOpened(
        opened: List<Pair<Int, Int>>,
        cols: Int,
        settings: TextSettings,
        random: Random = Random.Default,
    ): String {
        if (opened.isEmpty()) return ""
        val count = when (settings.chainMode) {
            ChainMode.ONCE -> 1
            ChainMode.PER_CELL -> if (settings.maxRepeat > 0) minOf(opened.size, settings.maxRepeat) else opened.size
        }
        val sb = StringBuilder()
        for (i in 0 until count) {
            val (r, c) = opened[i]
            sb.append(unitFor(r, c, cols, settings, random))
        }
        return sb.toString()
    }

    /** 1マス分の文字 */
    fun unitFor(r: Int, c: Int, cols: Int, settings: TextSettings, random: Random = Random.Default): String =
        when (settings.mode) {
            TextMode.FIXED -> settings.customText
            TextMode.COORDINATE_TABLE -> tableCharAt(r, c, cols, settings.charTable)
            TextMode.COORDINATE_LABEL -> coordinateLabel(r, c)
            TextMode.RANDOM -> if (settings.randomPool.isEmpty()) "" else settings.randomPool[random.nextInt(settings.randomPool.size)]
        }

    /** 左上から右へ順番に文字表を割り当てる（足りなければ先頭に戻る） */
    fun tableCharAt(r: Int, c: Int, cols: Int, table: List<String>): String =
        if (table.isEmpty()) "" else table[(r * cols + c) % table.size]

    /** 列をアルファベット（A, B, …, Z, AA…）、行を 1 始まりの数字で表した座標。例：(2, 1) → "B3" */
    fun coordinateLabel(r: Int, c: Int): String {
        var n = c + 1
        val letters = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            letters.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return "$letters${r + 1}"
    }

    /**
     * 設定文字列を入力単位に分割する。
     * カンマ（, または 、）を含む場合はカンマ区切り（「おはよう,こんにちは」のように複数文字の単位も可）、
     * 含まない場合は 1 文字（コードポイント）ずつ。空白と空要素は除く。
     */
    fun splitUnits(text: String): List<String> {
        if (text.contains(',') || text.contains('、')) {
            return text.split(',', '、').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val units = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (!Character.isWhitespace(cp)) units += text.substring(i, i + len)
            i += len
        }
        return units
    }
}
