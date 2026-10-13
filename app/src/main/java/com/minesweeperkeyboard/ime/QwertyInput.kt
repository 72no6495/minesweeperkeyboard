package com.minesweeperkeyboard.ime

/** キーボード全体の動作モード */
enum class KeyboardMode(val key: String, val shortLabel: String) {
    /** クラシックな盤面（開いたマスで文字入力） */
    CLASSIC("classic", "💣"),
    /** QWERTY 配列の盤面でマインスイーパーをプレイ（開いたキーの文字を入力） */
    QWERTY_GAME("qwerty_game", "⌨💣"),
    /** 普通の QWERTY キーボード。裏でマインスイーパーがうっすら動く */
    QWERTY_BACKGROUND("qwerty_background", "⌨");

    val isQwerty: Boolean get() = this != CLASSIC

    companion object {
        fun fromKey(key: String?): KeyboardMode = entries.firstOrNull { it.key == key } ?: CLASSIC
    }
}

/** QWERTY モードの入力言語 */
enum class InputLanguage(val key: String, val label: String) {
    ENGLISH("english", "A"),
    ROMAJI("romaji", "あ");

    companion object {
        fun fromKey(key: String?): InputLanguage = entries.firstOrNull { it.key == key } ?: ROMAJI
    }
}

/** 4 行 × 10 列の QWERTY 盤面 */
object QwertyLayout {
    val ROWS = listOf(
        "1234567890",
        "qwertyuiop",
        "asdfghjkl-",
        "zxcvbnm,.?",
    )
    val rows: Int get() = ROWS.size
    val cols: Int get() = ROWS[0].length

    fun keyAt(r: Int, c: Int): Char = ROWS[r][c]

    /** キーに表示する文字 */
    fun label(r: Int, c: Int, language: InputLanguage, shift: Boolean): String {
        val k = keyAt(r, c)
        return when (language) {
            InputLanguage.ENGLISH -> if (shift) k.uppercaseChar().toString() else k.toString()
            InputLanguage.ROMAJI -> JA_PUNCTUATION[k] ?: k.toString()
        }
    }

    /** ローマ字入力時の記号の置き換え（"-" は変換表で「ー」になる） */
    val JA_PUNCTUATION = mapOf('-' to "ー", ',' to "、", '.' to "。", '?' to "？")
}

/**
 * QWERTY キーからの入力を組み立てる（変換中の文字列＝ composing を管理する）。
 * Android 非依存。IME はこの結果を setComposingText / commitText に渡す。
 *
 * - 英語：英字は単語として変換中に溜め、それ以外のキーで確定
 * - ローマ字：英字と "-" をかなに変換して変換中に溜め、句読点・数字で確定
 */
class Composer {
    var language: InputLanguage = InputLanguage.ROMAJI
        set(value) {
            if (field != value) clear()
            field = value
        }

    private val english = StringBuilder()
    private val romaji = RomajiConverter()

    /** 画面に表示する変換中の文字列 */
    val composing: String
        get() = if (language == InputLanguage.ENGLISH) english.toString() else romaji.display

    /** 予測変換の検索に使う読み（ローマ字の未確定部分は含めない） */
    val reading: String
        get() = if (language == InputLanguage.ENGLISH) english.toString() else romaji.kana

    fun isEmpty() = composing.isEmpty()

    fun clear() {
        english.setLength(0)
        romaji.clear()
    }

    /**
     * キーを 1 つ入力する。
     * @return 確定して入力すべき文字列（なければ空）。変換中の文字列は [composing] で取得する
     */
    fun type(key: Char, shift: Boolean): String = when (language) {
        InputLanguage.ENGLISH ->
            if (key.isLetter()) {
                english.append(if (shift) key.uppercaseChar() else key)
                ""
            } else {
                finish() + key
            }
        InputLanguage.ROMAJI ->
            if (key.isLetter() || key == '-') {
                romaji.input(key)
                ""
            } else {
                finish() + (QwertyLayout.JA_PUNCTUATION[key] ?: key.toString())
            }
    }

    /** 変換中の文字列を 1 文字削除。削除できたら true */
    fun backspace(): Boolean {
        if (isEmpty()) return false
        if (language == InputLanguage.ENGLISH) english.setLength(english.length - 1) else romaji.backspace()
        return true
    }

    /** 変換中の文字列をそのまま確定して返す */
    fun finish(): String {
        val text = if (language == InputLanguage.ENGLISH) english.toString() else romaji.flush()
        clear()
        return text
    }
}

/**
 * 辞書登録した単語だけを候補にする予測変換。
 * 1 行 1 語で「よみ,単語」（タブ区切りも可）。よみを省略した行は単語自体をよみにする。
 */
class UserDictionary(text: String) {

    data class Entry(val reading: String, val word: String)

    val entries: List<Entry> = text.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val parts = trimmed.split(',', '\t', limit = 2).map { it.trim() }
        if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            Entry(parts[0], parts[1])
        } else {
            val word = parts.firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(word, word)
        }
    }

    /** よみ（または単語）が [prefix] で始まる候補。完全一致を先に、あとは登録順 */
    fun lookup(prefix: String, limit: Int = 4): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val hits = entries.filter {
            it.reading.startsWith(prefix, ignoreCase = true) || it.word.startsWith(prefix, ignoreCase = true)
        }
        val (exact, rest) = hits.partition { it.reading.equals(prefix, ignoreCase = true) }
        return (exact + rest).map { it.word }.distinct().take(limit)
    }

    companion object {
        const val SAMPLE = """# よみ,単語 の形式で 1 行に 1 つ登録します
おは,おはようございます
あり,ありがとうございます
おつ,お疲れさまです
よろ,よろしくお願いします
くさ,草生える
まいん,マインスイーパー
じらい,💣
ばくはつ,💥
mine,minesweeper
gg,good game"""
    }
}
