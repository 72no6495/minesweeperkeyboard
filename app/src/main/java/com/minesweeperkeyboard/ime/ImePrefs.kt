package com.minesweeperkeyboard.ime

import android.content.Context
import android.content.SharedPreferences

/** 盤面プリセット */
enum class Difficulty(val key: String, val labelRes: Int, val rows: Int, val cols: Int, val mines: Int) {
    BEGINNER("beginner", R.string.difficulty_beginner, 9, 9, 10),
    INTERMEDIATE("intermediate", R.string.difficulty_intermediate, 16, 16, 40),
    // 縦長画面向けにクラシックの 16x30 を縦向きにしたもの
    EXPERT("expert", R.string.difficulty_expert, 30, 16, 99),
    CUSTOM("custom", R.string.difficulty_custom, 0, 0, 0);

    fun next(): Difficulty = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): Difficulty = entries.firstOrNull { it.key == key } ?: BEGINNER
    }
}

data class BoardConfig(val rows: Int, val cols: Int, val mines: Int)

/** SharedPreferences のラッパー。IME と設定画面で共有する。 */
class ImePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("minesweeper_ime", Context.MODE_PRIVATE)

    var customText: String
        get() = sp.getString(KEY_CUSTOM_TEXT, DEFAULT_TEXT)!!.ifEmpty { DEFAULT_TEXT }
        set(v) = sp.edit().putString(KEY_CUSTOM_TEXT, v).apply()

    var textMode: TextMode
        get() = TextMode.fromKey(sp.getString(KEY_TEXT_MODE, null))
        set(v) = sp.edit().putString(KEY_TEXT_MODE, v.key).apply()

    /** 座標モードでマスに割り当てる文字表 */
    var charTable: String
        get() = sp.getString(KEY_CHAR_TABLE, null)?.takeIf { it.isNotBlank() } ?: TextGimmick.DEFAULT_CHAR_TABLE
        set(v) = sp.edit().putString(KEY_CHAR_TABLE, v).apply()

    /** ランダムモードの候補 */
    var randomPool: String
        get() = sp.getString(KEY_RANDOM_POOL, null)?.takeIf { it.isNotBlank() } ?: TextGimmick.DEFAULT_RANDOM_POOL
        set(v) = sp.edit().putString(KEY_RANDOM_POOL, v).apply()

    /** 座標（文字表）モードで、閉じたマスに入力される文字を表示する */
    var showCellHints: Boolean
        get() = sp.getBoolean(KEY_CELL_HINTS, true)
        set(v) = sp.edit().putBoolean(KEY_CELL_HINTS, v).apply()

    var chainMode: ChainMode
        get() = ChainMode.fromKey(sp.getString(KEY_CHAIN_MODE, null))
        set(v) = sp.edit().putString(KEY_CHAIN_MODE, v.key).apply()

    /** 1回の連鎖で入力する最大文字数（0 = 無制限） */
    var maxChainRepeat: Int
        get() = sp.getInt(KEY_MAX_CHAIN, 0)
        set(v) = sp.edit().putInt(KEY_MAX_CHAIN, v.coerceAtLeast(0)).apply()

    var chordEnabled: Boolean
        get() = sp.getBoolean(KEY_CHORD, true)
        set(v) = sp.edit().putBoolean(KEY_CHORD, v).apply()

    var hapticsEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(v) = sp.edit().putBoolean(KEY_HAPTICS, v).apply()

    var difficulty: Difficulty
        get() = Difficulty.fromKey(sp.getString(KEY_DIFFICULTY, null))
        set(v) = sp.edit().putString(KEY_DIFFICULTY, v.key).apply()

    var customRows: Int
        get() = sp.getInt(KEY_ROWS, 12)
        set(v) = sp.edit().putInt(KEY_ROWS, v).apply()

    var customCols: Int
        get() = sp.getInt(KEY_COLS, 10)
        set(v) = sp.edit().putInt(KEY_COLS, v).apply()

    var customMines: Int
        get() = sp.getInt(KEY_MINES, 20)
        set(v) = sp.edit().putInt(KEY_MINES, v).apply()

    /** 盤面の最大高さ（画面高さに対する %） */
    var boardHeightPercent: Int
        get() = sp.getInt(KEY_HEIGHT, 45)
        set(v) = sp.edit().putInt(KEY_HEIGHT, v.coerceIn(MIN_HEIGHT_PERCENT, MAX_HEIGHT_PERCENT)).apply()

    fun textSettings() = TextSettings(
        mode = textMode,
        customText = customText,
        charTable = TextGimmick.splitUnits(charTable),
        randomPool = TextGimmick.splitUnits(randomPool),
        chainMode = chainMode,
        maxRepeat = maxChainRepeat,
    )

    fun boardConfig(): BoardConfig {
        val d = difficulty
        return if (d == Difficulty.CUSTOM) {
            val rows = customRows.coerceIn(MinesweeperEngine.MIN_SIZE, MinesweeperEngine.MAX_SIZE)
            val cols = customCols.coerceIn(MinesweeperEngine.MIN_SIZE, MinesweeperEngine.MAX_SIZE)
            BoardConfig(rows, cols, customMines.coerceIn(1, rows * cols - 1))
        } else {
            BoardConfig(d.rows, d.cols, d.mines)
        }
    }

    companion object {
        const val DEFAULT_TEXT = TextGimmick.DEFAULT_FIXED_TEXT
        const val MIN_HEIGHT_PERCENT = 25
        const val MAX_HEIGHT_PERCENT = 70

        private const val KEY_CUSTOM_TEXT = "custom_text"
        private const val KEY_CHAIN_MODE = "chain_mode"
        private const val KEY_TEXT_MODE = "text_mode"
        private const val KEY_CHAR_TABLE = "char_table"
        private const val KEY_RANDOM_POOL = "random_pool"
        private const val KEY_CELL_HINTS = "show_cell_hints"
        private const val KEY_MAX_CHAIN = "max_chain_repeat"
        private const val KEY_CHORD = "chord_enabled"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_ROWS = "custom_rows"
        private const val KEY_COLS = "custom_cols"
        private const val KEY_MINES = "custom_mines"
        private const val KEY_HEIGHT = "board_height_percent"
    }
}
