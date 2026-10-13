package com.minesweeperkeyboard.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** 入力文字・盤面サイズなどの設定画面（レイアウトはコードで構築） */
class SettingsActivity : Activity() {

    private lateinit var prefs: ImePrefs

    private lateinit var themeGroup: RadioGroup
    private lateinit var layoutGroup: RadioGroup
    private lateinit var numberRowBox: CheckBox
    private lateinit var keyHeight: () -> Int
    private lateinit var keyText: () -> Int
    private lateinit var keyPreviewBox: CheckBox
    private lateinit var keySoundBox: CheckBox
    private lateinit var globeBox: CheckBox
    private lateinit var longPress: () -> Int
    private lateinit var longPressGroup: RadioGroup
    private lateinit var autoCapBox: CheckBox
    private lateinit var doubleSpaceBox: CheckBox
    private lateinit var spaceAfterCandidateBox: CheckBox
    private lateinit var punctuationGroup: RadioGroup
    private lateinit var learningBox: CheckBox
    private lateinit var keyboardModeGroup: RadioGroup
    private val modeBoxes = LinkedHashMap<KeyboardMode, CheckBox>()
    private var shownMode = KeyboardMode.CLASSIC
    private var shownLanguage = InputLanguage.ROMAJI
    private lateinit var languageGroup: RadioGroup
    private val languageBoxes = LinkedHashMap<InputLanguage, CheckBox>()
    private lateinit var qwertyMinesEdit: EditText
    private lateinit var backgroundGameBox: CheckBox
    private lateinit var backgroundMinesEdit: EditText
    private lateinit var backgroundResultsBox: CheckBox
    private lateinit var predictionBox: CheckBox
    private lateinit var systemDictionaryBox: CheckBox
    private lateinit var dictionaryEdit: EditText
    private lateinit var textModeGroup: RadioGroup
    private lateinit var customText: EditText
    private lateinit var charTableEdit: EditText
    private lateinit var randomPoolEdit: EditText
    private lateinit var hintsBox: CheckBox
    private lateinit var fixedSection: View
    private lateinit var tableSection: View
    private lateinit var randomSection: View
    private lateinit var hintsSection: View
    private lateinit var chainGroup: RadioGroup
    private lateinit var maxChain: EditText
    private lateinit var chordBox: CheckBox
    private lateinit var hapticsBox: CheckBox
    private lateinit var difficultyGroup: RadioGroup
    private lateinit var rowsEdit: EditText
    private lateinit var colsEdit: EditText
    private lateinit var minesEdit: EditText
    private lateinit var heightSeek: SeekBar
    private lateinit var heightLabel: TextView
    private lateinit var statusText: TextView

    private val difficultyIds = HashMap<Int, Difficulty>()

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ImePrefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }

        // ---- セットアップ
        root.addView(header("① キーボードのセットアップ"))
        statusText = TextView(this)
        root.addView(statusText)
        root.addView(button("キーボードを有効にする（システム設定）") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(button("入力方法をマインスイーパーに切り替える") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        // ---- キーボードのモード
        root.addView(header("② キーボードのモード"))
        keyboardModeGroup = RadioGroup(this)
        shownMode = prefs.keyboardMode
        val keyboardModeLabels = linkedMapOf(
            KeyboardMode.CLASSIC to "💣 クラシック盤面（マスを開くと文字入力）",
            KeyboardMode.QWERTY_GAME to "⌨💣 QWERTY 盤面でマインスイーパー（開いたキーの文字を入力）",
            KeyboardMode.QWERTY_BACKGROUND to "⌨ 普通のキーボード（裏でマインスイーパーがうっすら動く）",
        )
        for ((m, text) in keyboardModeLabels) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); this.text = text; tag = m }
            keyboardModeGroup.addView(rb)
            if (m == prefs.keyboardMode) keyboardModeGroup.check(rb.id)
        }
        root.addView(keyboardModeGroup)
        root.addView(label("キーボード左下のモード切替キーで切り替えるモード"))
        val enabledModes = prefs.enabledModes
        for ((m, text) in keyboardModeLabels) {
            val box = CheckBox(this).apply { this.text = text.substringBefore("（"); isChecked = m in enabledModes }
            modeBoxes[m] = box
            root.addView(box)
        }

        // ---- QWERTY 入力と予測変換
        root.addView(header("③ QWERTY の入力と予測変換"))
        root.addView(label("使う入力（キーボードの「あ／A」キーで切り替え）"))
        languageGroup = RadioGroup(this)
        shownLanguage = prefs.inputLanguage
        val languageLabels = linkedMapOf(
            InputLanguage.ROMAJI to "ローマ字入力（ひらがな）",
            InputLanguage.ENGLISH to "英語入力",
        )
        val enabledLanguages = prefs.enabledLanguages
        for ((lang, text) in languageLabels) {
            val box = CheckBox(this).apply { this.text = text; isChecked = lang in enabledLanguages }
            languageBoxes[lang] = box
            root.addView(box)
        }
        root.addView(label("最初に使う入力"))
        for ((lang, text) in languageLabels) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); this.text = text; tag = lang }
            languageGroup.addView(rb)
            if (lang == prefs.inputLanguage) languageGroup.check(rb.id)
        }
        root.addView(languageGroup)

        root.addView(label("QWERTY 盤面でプレイするときの地雷の数（1〜${ImePrefs.QWERTY_MAX_MINES}）"))
        qwertyMinesEdit = numberEdit(prefs.qwertyMines)
        root.addView(qwertyMinesEdit)

        backgroundGameBox = CheckBox(this).apply {
            text = "普通のキーボードモードで、裏のマインスイーパーを動かす"
            isChecked = prefs.backgroundGameEnabled
        }
        root.addView(backgroundGameBox)
        root.addView(label("裏のマインスイーパーの地雷の数（1〜${ImePrefs.QWERTY_MAX_MINES}）"))
        backgroundMinesEdit = numberEdit(prefs.backgroundMines)
        root.addView(backgroundMinesEdit)
        backgroundResultsBox = CheckBox(this).apply {
            text = "裏のマインスイーパーの結果（💥／🎉）も入力する"
            isChecked = prefs.backgroundResults
        }
        root.addView(backgroundResultsBox)

        predictionBox = CheckBox(this).apply {
            text = "予測変換を使う（候補をニコちゃんの左右に表示。候補が多いときは空白キーで次のページ）"
            isChecked = prefs.predictionEnabled
        }
        root.addView(predictionBox)

        val bundled = SystemDictionaryLoader.isBundled(this)
        systemDictionaryBox = CheckBox(this).apply {
            text = "日本語辞書（Mozc）の単語も候補に出す（ローマ字入力のとき）"
            isChecked = prefs.systemDictionaryEnabled
            isEnabled = bundled
        }
        root.addView(systemDictionaryBox)
        root.addView(label(
            if (bundled) "約 75 万語のよみを収録しています。初めて使うときに辞書を展開するため、候補が出るまで数秒かかります"
            else "このビルドには日本語辞書が入っていません（tools/build_ja_dictionary.py で作成できます）",
        ))
        if (bundled) {
            root.addView(button("日本語辞書のライセンスを表示") {
                val text = SystemDictionaryLoader.licenseText(this).orEmpty()
                AlertDialog.Builder(this)
                    .setTitle("日本語辞書のライセンス")
                    .setView(ScrollView(this).apply {
                        val p = (16 * dp).toInt()
                        addView(TextView(this@SettingsActivity).apply {
                            this.text = text
                            setPadding(p, p, p, p)
                            setTextIsSelectable(true)
                        })
                    })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            })
        }

        root.addView(label("ユーザー辞書（候補の先頭に出ます）：1 行に 1 つ「よみ,単語」の形式で登録します。" +
            "よみを省略すると単語そのものがよみになります。# で始まる行は無視します"))
        dictionaryEdit = EditText(this).apply {
            setText(prefs.dictionaryText)
            minLines = 5
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(dictionaryEdit)
        root.addView(button("辞書をサンプルに戻す") { dictionaryEdit.setText(UserDictionary.SAMPLE) })

        // ---- QWERTY・普通キーボードのカスタマイズ
        root.addView(header("④ キーボードの見た目と操作"))
        root.addView(label("テーマ（すべてのモード）"))
        themeGroup = radioGroup(Theme.entries.map { it to it.label }, prefs.theme)
        root.addView(themeGroup)

        root.addView(label("キー配列（QWERTY 盤面・普通のキーボード）"))
        layoutGroup = radioGroup(KeyLayout.entries.map { it to it.label }, prefs.keyLayout)
        root.addView(layoutGroup)
        numberRowBox = checkBox("数字の行を表示する（オフにすると数字は「記号」キーから入力）", prefs.numberRow)
        root.addView(numberRowBox)

        keyHeight = slider(root, ImePrefs.MIN_KEY_HEIGHT, ImePrefs.MAX_KEY_HEIGHT, 2, prefs.keyHeightDp) { "キーの高さ：${it}dp" }
        keyText = slider(root, ImePrefs.MIN_KEY_TEXT, ImePrefs.MAX_KEY_TEXT, 5, prefs.keyTextPercent) { "キーの文字の大きさ：${it}%" }
        keyPreviewBox = checkBox("押したキーを上に大きく表示する", prefs.keyPreview)
        root.addView(keyPreviewBox)
        keySoundBox = checkBox("キーを押したときに音を鳴らす", prefs.keySound)
        root.addView(keySoundBox)
        globeBox = checkBox("🌐（キーボード切り替え）キーを表示する", prefs.showGlobeKey)
        root.addView(globeBox)

        longPress = slider(root, ImePrefs.MIN_LONG_PRESS, ImePrefs.MAX_LONG_PRESS, 50, prefs.longPressMs) { "長押しと判定するまでの時間：${it}ms" }
        root.addView(label("普通のキーボードでキーを長押ししたとき"))
        longPressGroup = radioGroup(LongPressAction.entries.map { it to it.label }, prefs.longPressAction)
        root.addView(longPressGroup)

        root.addView(label("英語入力"))
        autoCapBox = checkBox("文の始めを自動で大文字にする（入力欄が対応している場合）", prefs.autoCapitalize)
        root.addView(autoCapBox)
        doubleSpaceBox = checkBox("スペースを 2 回押すとピリオドを入力する", prefs.doubleSpacePeriod)
        root.addView(doubleSpaceBox)
        spaceAfterCandidateBox = checkBox("候補を選んだ後にスペースを入れる", prefs.spaceAfterCandidate)
        root.addView(spaceAfterCandidateBox)

        root.addView(label("日本語入力の句読点"))
        punctuationGroup = radioGroup(PunctuationStyle.entries.map { it to it.label }, prefs.punctuationStyle)
        root.addView(punctuationGroup)

        learningBox = checkBox("選んだ候補を学習して、次から先に出す", prefs.learningEnabled)
        root.addView(learningBox)
        root.addView(button("学習した内容を消去") {
            AlertDialog.Builder(this)
                .setMessage("学習した ${Learning.model.size()} 件の候補をすべて消去しますか？")
                .setPositiveButton("消去") { _, _ ->
                    Learning.reset(filesDir)
                    Toast.makeText(this, "学習した内容を消去しました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })

        // ---- 文字入力ギミック
        root.addView(header("⑤ クラシック盤面：安全なマスを開いたときに入力する文字"))
        textModeGroup = RadioGroup(this)
        val modeLabels = linkedMapOf(
            TextMode.FIXED to "決まった文字",
            TextMode.COORDINATE_TABLE to "座標に応じた文字（マスごとに文字表を割り当て）",
            TextMode.COORDINATE_LABEL to "座標そのもの（例：B3）",
            TextMode.RANDOM to "ランダムな文字",
        )
        for ((mode, text) in modeLabels) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); this.text = text; tag = mode }
            textModeGroup.addView(rb)
            if (mode == prefs.textMode) textModeGroup.check(rb.id)
        }
        root.addView(textModeGroup)

        // 決まった文字
        customText = EditText(this).apply {
            setText(prefs.customText)
            hint = "例：ｗ / 草 / あ"
            setSingleLine()
        }
        fixedSection = section(
            label("入力する文字"),
            customText,
            presetRow(customText, listOf("ｗ", "草", "あ", "💣", "ﾋﾟｴﾝ")),
        )
        root.addView(fixedSection)

        // 座標に応じた文字
        charTableEdit = EditText(this).apply {
            setText(prefs.charTable)
            hint = "例：あいうえお…"
        }
        hintsBox = CheckBox(this).apply {
            text = "閉じたマスに、開くと入力される文字を表示する"
            isChecked = prefs.showCellHints
        }
        tableSection = section(
            label("文字表：盤面の左上から右へ、1 マスずつ順番に割り当てます（足りなければ最初に戻ります）。" +
                "カンマ区切りにすると「おはよう,こんにちは」のように複数文字も割り当てられます"),
            charTableEdit,
            presetRow(charTableEdit, linkedMapOf(
                "五十音" to TextGimmick.DEFAULT_CHAR_TABLE,
                "ABC" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                "数字" to "0123456789",
                "絵文字" to "😀😂😍🤔😎😭😡👍🙏🎉🔥💯✨💣🚩",
            )),
        )
        root.addView(tableSection)

        // ランダムな文字
        randomPoolEdit = EditText(this).apply {
            setText(prefs.randomPool)
            hint = "例：あいうえお…"
        }
        randomSection = section(
            label("候補：開いたマスごとに、この中からランダムに 1 つ入力します（カンマ区切りで複数文字の候補も可）"),
            randomPoolEdit,
            presetRow(randomPoolEdit, linkedMapOf(
                "ひらがな" to TextGimmick.DEFAULT_RANDOM_POOL,
                "カタカナ" to "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン",
                "英数字" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
                "絵文字" to "😀😂😍🤔😎😭😡👍🙏🎉🔥💯✨💣🚩",
            )),
        )
        root.addView(randomSection)

        // 座標モード共通のヒント表示
        hintsSection = section(hintsBox)
        root.addView(hintsSection)

        textModeGroup.setOnCheckedChangeListener { _, _ -> updateTextModeSections() }
        updateTextModeSections()

        root.addView(label("連鎖オープン時の入力"))
        chainGroup = RadioGroup(this)
        val perCell = RadioButton(this).apply { id = View.generateViewId(); text = "開いたマスの数だけ連続入力" }
        val once = RadioButton(this).apply { id = View.generateViewId(); text = "1回のタップにつき1回だけ" }
        chainGroup.addView(perCell)
        chainGroup.addView(once)
        chainGroup.check(if (prefs.chainMode == ChainMode.ONCE) once.id else perCell.id)
        perCell.tag = ChainMode.PER_CELL
        once.tag = ChainMode.ONCE
        root.addView(chainGroup)

        root.addView(label("1回の連鎖で入力する最大文字数（0 = 無制限）"))
        maxChain = numberEdit(prefs.maxChainRepeat)
        root.addView(maxChain)

        chordBox = CheckBox(this).apply {
            text = "数字マスをタップしたとき、旗の数が合っていれば周囲を一括オープン"
            isChecked = prefs.chordEnabled
        }
        root.addView(chordBox)
        hapticsBox = CheckBox(this).apply {
            text = "振動フィードバック"
            isChecked = prefs.hapticsEnabled
        }
        root.addView(hapticsBox)

        // ---- 盤面
        root.addView(header("⑥ クラシック盤面のサイズ"))
        difficultyGroup = RadioGroup(this)
        for (d in Difficulty.entries) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = if (d == Difficulty.CUSTOM) getString(d.labelRes)
                else "${getString(d.labelRes)}（${d.cols}×${d.rows} / 地雷${d.mines}）"
            }
            difficultyIds[rb.id] = d
            difficultyGroup.addView(rb)
            if (d == prefs.difficulty) difficultyGroup.check(rb.id)
        }
        root.addView(difficultyGroup)

        val custom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rowsEdit = numberEdit(prefs.customRows)
        colsEdit = numberEdit(prefs.customCols)
        minesEdit = numberEdit(prefs.customMines)
        custom.addView(label("縦"))
        custom.addView(rowsEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        custom.addView(label("横"))
        custom.addView(colsEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        custom.addView(label("地雷"))
        custom.addView(minesEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(label("カスタムのサイズ（${MinesweeperEngine.MIN_SIZE}〜${MinesweeperEngine.MAX_SIZE}マス）"))
        root.addView(custom)

        heightLabel = label("")
        root.addView(heightLabel)
        heightSeek = SeekBar(this).apply {
            max = ImePrefs.MAX_HEIGHT_PERCENT - ImePrefs.MIN_HEIGHT_PERCENT
            progress = prefs.boardHeightPercent - ImePrefs.MIN_HEIGHT_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateHeightLabel()
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        updateHeightLabel()
        root.addView(heightSeek)

        root.addView(button("保存") {
            save()
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        })

        // ---- 試し打ち
        root.addView(header("⑦ 試し打ち"))
        root.addView(EditText(this).apply {
            hint = "ここをタップしてキーボードを試す"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        prefs.theme = selected<Theme>(themeGroup) ?: Theme.CLASSIC
        prefs.keyLayout = selected<KeyLayout>(layoutGroup) ?: KeyLayout.QWERTY
        prefs.numberRow = numberRowBox.isChecked
        prefs.keyHeightDp = keyHeight()
        prefs.keyTextPercent = keyText()
        prefs.keyPreview = keyPreviewBox.isChecked
        prefs.keySound = keySoundBox.isChecked
        prefs.showGlobeKey = globeBox.isChecked
        prefs.longPressMs = longPress()
        prefs.longPressAction = selected<LongPressAction>(longPressGroup) ?: LongPressAction.SYMBOL
        prefs.autoCapitalize = autoCapBox.isChecked
        prefs.doubleSpacePeriod = doubleSpaceBox.isChecked
        prefs.spaceAfterCandidate = spaceAfterCandidateBox.isChecked
        prefs.punctuationStyle = selected<PunctuationStyle>(punctuationGroup) ?: PunctuationStyle.TOUTEN_KUTEN
        prefs.learningEnabled = learningBox.isChecked
        // ラジオボタンを変えたときだけ書き込む（試し打ち欄でキーボードから切り替えた分を上書きしない）
        val selectedMode =
            keyboardModeGroup.findViewById<RadioButton>(keyboardModeGroup.checkedRadioButtonId)?.tag as? KeyboardMode
                ?: KeyboardMode.CLASSIC
        if (selectedMode != shownMode) {
            prefs.keyboardMode = selectedMode
            shownMode = selectedMode
        }
        // 1 つも選ばれていなければ現在のモード／入力だけを有効にする
        prefs.enabledModes = modeBoxes.filterValues { it.isChecked }.keys.ifEmpty { setOf(prefs.keyboardMode) }
        val language =
            languageGroup.findViewById<RadioButton>(languageGroup.checkedRadioButtonId)?.tag as? InputLanguage
                ?: InputLanguage.ROMAJI
        val languages = languageBoxes.filterValues { it.isChecked }.keys.ifEmpty { setOf(language) }
        prefs.enabledLanguages = languages
        if (language != shownLanguage || prefs.inputLanguage !in languages) {
            prefs.inputLanguage = if (language in languages) language else languages.first()
            shownLanguage = language
        }
        prefs.qwertyMines = qwertyMinesEdit.text.toString().toIntOrNull() ?: prefs.qwertyMines
        qwertyMinesEdit.setText(prefs.qwertyMines.toString())
        prefs.backgroundGameEnabled = backgroundGameBox.isChecked
        prefs.backgroundMines = backgroundMinesEdit.text.toString().toIntOrNull() ?: prefs.backgroundMines
        backgroundMinesEdit.setText(prefs.backgroundMines.toString())
        prefs.backgroundResults = backgroundResultsBox.isChecked
        prefs.predictionEnabled = predictionBox.isChecked
        prefs.systemDictionaryEnabled = systemDictionaryBox.isChecked
        prefs.dictionaryText = dictionaryEdit.text.toString()

        prefs.customText = customText.text.toString()
        prefs.textMode = selectedTextMode()
        prefs.charTable = charTableEdit.text.toString()
        prefs.randomPool = randomPoolEdit.text.toString()
        prefs.showCellHints = hintsBox.isChecked
        prefs.chainMode = chainGroup.findViewById<RadioButton>(chainGroup.checkedRadioButtonId)?.tag as? ChainMode
            ?: ChainMode.PER_CELL
        prefs.maxChainRepeat = maxChain.text.toString().toIntOrNull() ?: 0
        prefs.chordEnabled = chordBox.isChecked
        prefs.hapticsEnabled = hapticsBox.isChecked
        prefs.difficulty = difficultyIds[difficultyGroup.checkedRadioButtonId] ?: Difficulty.BEGINNER

        val rows = (rowsEdit.text.toString().toIntOrNull() ?: prefs.customRows)
            .coerceIn(MinesweeperEngine.MIN_SIZE, MinesweeperEngine.MAX_SIZE)
        val cols = (colsEdit.text.toString().toIntOrNull() ?: prefs.customCols)
            .coerceIn(MinesweeperEngine.MIN_SIZE, MinesweeperEngine.MAX_SIZE)
        val mines = (minesEdit.text.toString().toIntOrNull() ?: prefs.customMines).coerceIn(1, rows * cols - 1)
        prefs.customRows = rows
        prefs.customCols = cols
        prefs.customMines = mines
        rowsEdit.setText(rows.toString())
        colsEdit.setText(cols.toString())
        minesEdit.setText(mines.toString())

        prefs.boardHeightPercent = heightSeek.progress + ImePrefs.MIN_HEIGHT_PERCENT
    }

    private fun selectedTextMode(): TextMode =
        textModeGroup.findViewById<RadioButton>(textModeGroup.checkedRadioButtonId)?.tag as? TextMode ?: TextMode.FIXED

    /** 選択中のモードに関係する設定だけを表示する */
    private fun updateTextModeSections() {
        val mode = selectedTextMode()
        fixedSection.visibility = if (mode == TextMode.FIXED) View.VISIBLE else View.GONE
        tableSection.visibility = if (mode == TextMode.COORDINATE_TABLE) View.VISIBLE else View.GONE
        randomSection.visibility = if (mode == TextMode.RANDOM) View.VISIBLE else View.GONE
        hintsSection.visibility =
            if (mode == TextMode.COORDINATE_TABLE || mode == TextMode.COORDINATE_LABEL) View.VISIBLE else View.GONE
    }

    private fun section(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

    private fun presetRow(target: EditText, presets: List<String>) =
        presetRow(target, presets.associateWithTo(linkedMapOf<String, String>()) { it })

    /** ボタンを押すと [target] に値を入れるプリセット行（キー＝ボタン表示、値＝入力内容） */
    private fun presetRow(target: EditText, presets: Map<String, String>) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((label, value) in presets) {
                addView(Button(this@SettingsActivity).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { target.setText(value) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun updateHeightLabel() {
        heightLabel.text = "盤面の最大高さ：画面の ${heightSeek.progress + ImePrefs.MIN_HEIGHT_PERCENT}%"
    }

    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.startsWith("$packageName/") == true
        statusText.text = buildString {
            append(if (enabled) "✅ 有効化済み" else "⬜ まだ有効化されていません")
            append("\n")
            append(if (selected) "✅ 現在の入力方法です" else "⬜ 現在の入力方法ではありません")
        }
    }

    /** タグに選択肢の値を持たせたラジオボタンのグループ */
    private fun <T> radioGroup(options: List<Pair<T, String>>, selected: T) = RadioGroup(this).apply {
        for ((value, text) in options) {
            val rb = RadioButton(this@SettingsActivity).apply { id = View.generateViewId(); this.text = text; tag = value }
            addView(rb)
            if (value == selected) check(rb.id)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> selected(group: RadioGroup): T? =
        group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? T

    private fun checkBox(text: String, checked: Boolean) = CheckBox(this).apply {
        this.text = text
        isChecked = checked
    }

    /** 見出し付きのスライダーを追加し、現在の値を返す関数を返す */
    private fun slider(parent: LinearLayout, min: Int, max: Int, step: Int, value: Int, title: (Int) -> String): () -> Int {
        val caption = label(title(value))
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = ((value - min) / step).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    caption.text = title(min + p * step)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        parent.addView(caption)
        parent.addView(bar)
        return { min + bar.progress * step }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun numberEdit(value: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        setSingleLine()
    }
}
