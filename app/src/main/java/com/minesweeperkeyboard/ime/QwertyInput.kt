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

/** 普通のキーボードモードでキーを長押ししたときの動作 */
enum class LongPressAction(val key: String, val label: String) {
    SYMBOL("symbol", "記号・数字を入力（記号モードで同じ位置にある文字）"),
    FLAG("flag", "裏のマインスイーパーに旗を立てる"),
    NONE("none", "なし（長く押しても普通に入力）");

    companion object {
        fun fromKey(key: String?): LongPressAction = entries.firstOrNull { it.key == key } ?: SYMBOL
    }
}

/** キーの面：記号キーを押すたびに 文字 → 記号 → 絵文字 → 文字 の順に切り替わる */
enum class KeyLayer(val buttonLabel: String) {
    LETTERS("記号"),
    SYMBOLS("絵文字"),
    EMOJI("文字");

    fun next(): KeyLayer = entries[(ordinal + 1) % entries.size]
}

/** 英字の並び（どれも 10 列） */
enum class KeyLayout(val key: String, val label: String, val letterRows: List<String>) {
    QWERTY("qwerty", "QWERTY", listOf("qwertyuiop", "asdfghjkl-", "zxcvbnm,.?")),
    QWERTZ("qwertz", "QWERTZ（ドイツ語圏）", listOf("qwertzuiop", "asdfghjkl-", "yxcvbnm,.?")),
    AZERTY("azerty", "AZERTY（フランス語圏）", listOf("azertyuiop", "qsdfghjklm", "wxcvbn-,.?"));

    companion object {
        fun fromKey(key: String?): KeyLayout = entries.firstOrNull { it.key == key } ?: QWERTY
    }
}

/** 日本語入力の句読点 */
enum class PunctuationStyle(val key: String, val comma: String, val period: String) {
    TOUTEN_KUTEN("touten_kuten", "、", "。"),
    COMMA_PERIOD("comma_period", "，", "．"),
    COMMA_KUTEN("comma_kuten", "，", "。");

    val label: String get() = comma + period

    companion object {
        fun fromKey(key: String?): PunctuationStyle = entries.firstOrNull { it.key == key } ?: TOUTEN_KUTEN
    }
}

/**
 * QWERTY 系の盤面（10 列 × 3〜4 行）。数字の行は設定で出し入れできる。
 * 記号モードでは同じ位置に [SYMBOL_ROWS] の記号が並ぶ。
 * [flagKey] が true なら、いちばん下の段の左端（QWERTY なら Z の左）に🚩キーを置く（その段の右端の「?」は記号モードへ）。
 */
class KeyGrid(
    val layout: KeyLayout = KeyLayout.QWERTY,
    val numberRow: Boolean = true,
    val flagKey: Boolean = false,
) {

    val rows: List<String> = ((if (numberRow) listOf(NUMBER_ROW) else emptyList()) + layout.letterRows).let { rows ->
        if (flagKey) rows.dropLast(1) + (FLAG + rows.last().dropLast(1)) else rows
    }
    private val symbolRows: List<String> = SYMBOL_ROWS.take(rows.size).let { rows ->
        if (flagKey) rows.dropLast(1) + (FLAG + rows.last().drop(1)) else rows
    }
    private val emojiRows: List<List<String>> = EMOJI_ROWS.take(rows.size)

    /** 🚩キーの位置（なければ null） */
    val flagPosition: Pair<Int, Int>? = if (flagKey) (rows.size - 1) to 0 else null

    fun isFlagKey(r: Int, c: Int): Boolean = flagKey && r == rows.size - 1 && c == 0

    val rowCount: Int get() = rows.size
    val colCount: Int get() = COLS

    fun keyAt(r: Int, c: Int): Char = rows[r][c]

    fun symbolAt(r: Int, c: Int): Char = symbolRows[r][c]

    fun emojiAt(r: Int, c: Int): String = emojiRows[r][c]

    /** その面で入力される文字列（記号・絵文字の面）。文字の面は [keyAt] を使う */
    fun textAt(r: Int, c: Int, layer: KeyLayer): String = when (layer) {
        KeyLayer.LETTERS -> keyAt(r, c).toString()
        KeyLayer.SYMBOLS -> symbolAt(r, c).toString()
        KeyLayer.EMOJI -> emojiAt(r, c)
    }

    /** キーに表示する文字 */
    fun label(
        r: Int, c: Int, language: InputLanguage, shift: Boolean, layer: KeyLayer = KeyLayer.LETTERS,
        punctuation: PunctuationStyle = PunctuationStyle.TOUTEN_KUTEN,
    ): String {
        if (isFlagKey(r, c)) return "🚩"
        if (layer != KeyLayer.LETTERS) return textAt(r, c, layer)
        val k = keyAt(r, c)
        return when (language) {
            InputLanguage.ENGLISH -> if (shift) k.uppercaseChar().toString() else k.toString()
            InputLanguage.ROMAJI -> japanesePunctuation(k, punctuation) ?: k.toString()
        }
    }

    companion object {
        const val COLS = 10
        /** 🚩キーを表す文字 */
        const val FLAG = '\u2691'
        const val NUMBER_ROW = "1234567890"
        val SYMBOL_ROWS = listOf(
            "1234567890",
            "!@#$%&*()?",
            "-_=+;:'\"/,",
            "<>[]{}~^|\\",
        )

        /** 絵文字の面（10 個 × 4 行。数字の行がないときは上の 3 行） */
        val EMOJI_ROWS: List<List<String>> = listOf(
            listOf("😀", "😂", "🥹", "😍", "😘", "😎", "🤔", "😭", "😡", "😱"),
            listOf("👍", "👏", "🙏", "💪", "👀", "🙌", "👌", "✌️", "🤝", "👋"),
            listOf("❤️", "💔", "✨", "🔥", "💯", "🎉", "💣", "💥", "🚩", "⭐"),
            listOf("🐱", "🐶", "🍣", "🍺", "☕", "🍰", "🌸", "☀️", "🌙", "🎮"),
        )

        /** ローマ字入力時の記号の置き換え（"-" は変換表で「ー」になる） */
        fun japanesePunctuation(key: Char, style: PunctuationStyle): String? = when (key) {
            '-' -> "ー"
            ',' -> style.comma
            '.' -> style.period
            '?' -> "？"
            else -> null
        }
    }
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

    var punctuation: PunctuationStyle = PunctuationStyle.TOUTEN_KUTEN

    private val english = StringBuilder()
    private val romaji = RomajiConverter()

    /** 画面に表示する変換中の文字列 */
    val composing: String
        get() = if (language == InputLanguage.ENGLISH) english.toString() else romaji.display

    /** 予測変換の検索に使う読み（ローマ字の未確定部分は含めない） */
    val reading: String
        get() = if (language == InputLanguage.ENGLISH) english.toString() else romaji.kana

    /**
     * 予測変換で検索するよみ。ローマ字の途中（"かんj" など）なら続きのかなを補ったよみ（かんじ・かんじゃ…）、
     * そうでなければ [reading] だけ。
     */
    val readingVariants: List<String>
        get() {
            if (language == InputLanguage.ENGLISH) return listOf(reading).filter { it.isNotEmpty() }
            val kana = romaji.kana
            val completions = romaji.pendingCompletions().map { kana + it }
            return (if (completions.isEmpty()) listOf(kana) else completions).filter { it.isNotEmpty() }
        }

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
                finish() + (KeyGrid.japanesePunctuation(key, punctuation) ?: key.toString())
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
