package com.minesweeperkeyboard.ime

enum class ChainMode(val key: String) {
    /** 開いたマスの数だけ文字を入力 */
    PER_CELL("per_cell"),
    /** 1回のタップにつき1回だけ入力 */
    ONCE("once");

    companion object {
        fun fromKey(key: String?): ChainMode = entries.firstOrNull { it.key == key } ?: PER_CELL
    }
}

/** 「マスを開いた数」→「入力する文字列」の変換（Android 非依存でテスト可能） */
object TextGimmick {

    /**
     * @param openedCells 1回の操作で開いた安全マスの数
     * @param maxRepeat 0 なら無制限
     */
    fun textForOpenedCells(openedCells: Int, customText: String, mode: ChainMode, maxRepeat: Int): String {
        if (openedCells <= 0 || customText.isEmpty()) return ""
        val times = when (mode) {
            ChainMode.ONCE -> 1
            ChainMode.PER_CELL -> if (maxRepeat > 0) minOf(openedCells, maxRepeat) else openedCells
        }
        return customText.repeat(times)
    }
}
