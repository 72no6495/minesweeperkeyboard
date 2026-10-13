package com.minesweeperkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout

/**
 * マインスイーパー IME 本体。3 つのモードがある。
 *
 * - クラシック：安全なマスを開く → 設定した文字を入力、数字マス → 数字、地雷／クリア → リザルト
 * - QWERTY でプレイ：QWERTY 配列の盤面。安全なキーを開くとそのキーの文字（英語／ローマ字）を入力
 * - 普通のキーボード：普通に文字を打てる。押したキーの下で裏のマインスイーパーがうっすら進む
 *
 * QWERTY 系では辞書登録した単語だけを予測変換の候補としてニコちゃんの左右に表示する。
 */
class MinesweeperIME : InputMethodService(), MinesweeperBoardView.Listener {

    private lateinit var prefs: ImePrefs
    private var engine: MinesweeperEngine? = null
    private var engineConfig: BoardConfig? = null
    private var engineMode: KeyboardMode? = null
    private var mode = KeyboardMode.CLASSIC
    private var textSettings = TextSettings()

    private val composer = Composer()
    private var dictionary = UserDictionary("")
    private var dictionarySource: String? = null

    private var boardView: MinesweeperBoardView? = null
    private var statusBar: StatusBarView? = null
    private var modeButton: ClassicButton? = null
    /** クラシック：難易度／QWERTY：入力言語 */
    private var secondButton: ClassicButton? = null
    /** クラシック：🚩モード／QWERTY：シフト */
    private var thirdButton: ClassicButton? = null

    /** 🚩モード：タップで旗、長押しで開く（クラシックのみ） */
    private var flagMode = false
    /** 英語入力の 1 回だけのシフト */
    private var shift = false

    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            updateStatus()
            if (engine?.state == MinesweeperEngine.State.PLAYING) handler.postDelayed(this, 250L)
        }
    }
    /** 裏のマインスイーパーが終わったら少し待って新しいゲームにする */
    private val backgroundRestart = Runnable {
        if (mode == KeyboardMode.QWERTY_BACKGROUND && engine?.isGameOver == true) newGame()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = ImePrefs(this)
    }

    // 横画面でも全画面の抽出 UI にせず、盤面をそのまま表示する
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val pad = (4 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ClassicStyle.FACE)
            setPadding(pad, pad, pad, pad)
            // Android 15 以降は IME もナビゲーションバーの下まで描画されるため、
            // ホームボタン／ジェスチャー領域の分だけ下に余白を取る
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(pad, pad, pad, pad + bottomSystemInset(insets))
                insets
            }
        }

        statusBar = StatusBarView(this).also {
            it.onFaceClick = { newGame() }
            it.onCandidateClick = { word -> pickCandidate(word) }
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        boardView = MinesweeperBoardView(this).also {
            it.listener = this
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad
                bottomMargin = pad
            })
        }

        root.addView(createKeyRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        applySettings(forceNewGame = engine == null)
        boardView?.engine = engine
        updateStatus()
        return root
    }

    private fun bottomSystemInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            maxOf(
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom,
            )
        } else {
            // Android 10 以前は IME ウィンドウがナビゲーションバーの上に配置される
            0
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun createKeyRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun add(label: String, weight: Float, onClick: (ClassicButton) -> Unit): ClassicButton {
            val b = ClassicButton(this, label)
            b.setOnClickListener { v ->
                if (prefs.hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick(b)
            }
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight))
            return b
        }

        modeButton = add(mode.shortLabel, 1.1f) { switchMode() }
        secondButton = add("", 1.3f) {
            if (mode.isQwerty) switchLanguage() else {
                prefs.difficulty = prefs.difficulty.next()
                applySettings(forceNewGame = true)
            }
        }
        thirdButton = add("", 1f) {
            if (mode.isQwerty) {
                shift = !shift
                refreshKeyLabels()
            } else {
                flagMode = !flagMode
            }
            updateButtons()
        }
        add("⚙", 0.9f) { openSettings() }
        add("🌐", 0.9f) { switchKeyboard() }.setOnLongClickListener {
            showPicker()
            true
        }
        add("空白", 1.4f) { onSpace() }
        add("⌫", 1.1f) { }.setOnTouchListener(BackspaceRepeater())
        add("⏎", 1.1f) { onEnter() }
        return row
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) resetComposing()
        // 設定画面で変更された内容を反映
        applySettings(forceNewGame = false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        handler.removeCallbacks(timerTick)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposing()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // 変換中にカーソルが別の場所へ動かされたら、変換中の文字列はそのまま確定扱いにする
        if (!composer.isEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            resetComposing()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 盤面の操作

    override fun onCellTap(row: Int, col: Int) {
        when (mode) {
            KeyboardMode.CLASSIC -> classicTap(row, col)
            KeyboardMode.QWERTY_GAME -> qwertyGameTap(row, col)
            KeyboardMode.QWERTY_BACKGROUND -> {
                typeKey(row, col)
                if (prefs.backgroundGameEnabled) backgroundOpen(row, col)
            }
        }
    }

    override fun onCellLongPress(row: Int, col: Int) {
        val e = engine ?: return
        when (mode) {
            KeyboardMode.CLASSIC -> {
                if (e.isGameOver) return
                if (flagMode) reveal(row, col) else toggleFlag(row, col)
            }
            KeyboardMode.QWERTY_GAME -> {
                if (e.isGameOver) return
                // 開いた数字キーの長押しで周囲を一括オープン、閉じたキーは旗
                if (e.cell(row, col).isOpen) {
                    if (prefs.chordEnabled) handleResult(e, e.chord(row, col), commitResults = true)
                } else {
                    toggleFlag(row, col)
                }
            }
            KeyboardMode.QWERTY_BACKGROUND -> {
                // 裏のゲームが動いていれば旗、そうでなければ普通に入力
                if (prefs.backgroundGameEnabled && !e.isGameOver && !e.cell(row, col).isOpen) {
                    toggleFlag(row, col)
                } else {
                    typeKey(row, col)
                }
            }
        }
    }

    override fun onBoardPressChanged(pressed: Boolean) {
        val e = engine ?: return
        if (!e.isGameOver) statusBar?.face = if (pressed) ClassicStyle.Face.SURPRISED else ClassicStyle.Face.SMILE
    }

    private fun classicTap(row: Int, col: Int) {
        val e = engine ?: return
        if (e.isGameOver) {
            // 終了後に盤面をタップしたら新しいゲーム
            newGame()
            return
        }
        if (flagMode) toggleFlag(row, col) else reveal(row, col)
    }

    private fun qwertyGameTap(row: Int, col: Int) {
        val e = engine ?: return
        if (e.isGameOver) {
            newGame()
            return
        }
        val cell = e.cell(row, col)
        when {
            cell.isFlagged -> return
            // 開いたキーは普通のキーとして何度でも打てる
            cell.isOpen -> typeKey(row, col)
            else -> {
                val wasReady = e.state == MinesweeperEngine.State.READY
                val result = e.open(row, col)
                if (!result.exploded) typeKey(row, col)
                handleResult(e, result, commitResults = true)
                if (wasReady && e.state == MinesweeperEngine.State.PLAYING) startTimer()
            }
        }
    }

    /** 普通のキーボードモード：押したキーの下のマスを裏で開く */
    private fun backgroundOpen(row: Int, col: Int) {
        if (engine?.isGameOver == true) {
            handler.removeCallbacks(backgroundRestart)
            newGame()
        }
        val e = engine ?: return
        val cell = e.cell(row, col)
        if (cell.isOpen || cell.isFlagged) return
        val wasReady = e.state == MinesweeperEngine.State.READY
        val result = e.open(row, col)
        handleResult(e, result, commitResults = prefs.backgroundResults)
        if (wasReady && e.state == MinesweeperEngine.State.PLAYING) startTimer()
        if (e.isGameOver) handler.postDelayed(backgroundRestart, 1500L)
    }

    private fun toggleFlag(row: Int, col: Int) {
        if (engine?.toggleFlag(row, col) == true) {
            boardView?.invalidate()
            updateStatus()
        }
    }

    /** クラシック盤面でマスを開く／数字マスをタップ */
    private fun reveal(row: Int, col: Int) {
        val e = engine ?: return
        val cell = e.cell(row, col)
        val wasReady = e.state == MinesweeperEngine.State.READY

        val result = if (cell.isOpen) {
            // 数字マスをタップ → 数字を入力（＋旗の数が合っていれば周囲を一括オープン）
            if (cell.adjacentMines > 0) commit(cell.adjacentMines.toString())
            if (prefs.chordEnabled) e.chord(row, col) else MinesweeperEngine.MoveResult()
        } else {
            e.open(row, col)
        }

        // 開いたマスに応じた文字を入力（固定文字／座標の文字／ランダム）
        commit(TextGimmick.textForOpened(result.openedPositions, e.cols, textSettings))
        handleResult(e, result, commitResults = true)
        if (wasReady && e.state == MinesweeperEngine.State.PLAYING) startTimer()
    }

    /** 爆発・クリア時のリザルト入力と再描画 */
    private fun handleResult(e: MinesweeperEngine, result: MinesweeperEngine.MoveResult, commitResults: Boolean) {
        when {
            result.exploded -> {
                vibrate(strong = true)
                if (commitResults) {
                    finishComposing()
                    commit(getString(R.string.result_game_over, e.openedCount))
                }
            }
            result.won -> if (commitResults) {
                finishComposing()
                commit(getString(R.string.result_clear, e.elapsedSeconds()))
            }
        }
        if (!result.isNoop) boardView?.invalidate()
        updateStatus()
    }

    private fun newGame() {
        val config = prefs.boardConfigFor(mode)
        engine = MinesweeperEngine(config.rows, config.cols, config.mines)
        engineConfig = config
        engineMode = mode
        handler.removeCallbacks(timerTick)
        handler.removeCallbacks(backgroundRestart)
        boardView?.engine = engine
        updateCellHints()
        updateStatus()
    }

    // ---------------------------------------------------------------- 設定・モード

    private fun applySettings(forceNewGame: Boolean) {
        mode = prefs.keyboardMode
        val languages = prefs.enabledLanguages
        composer.language = prefs.inputLanguage.takeIf { it in languages } ?: languages.first()
        prefs.dictionaryText.let {
            if (it != dictionarySource) {
                dictionary = UserDictionary(it)
                dictionarySource = it
            }
        }

        val config = prefs.boardConfigFor(mode)
        if (forceNewGame || engine == null || config != engineConfig || mode != engineMode) newGame()
        boardView?.apply {
            style = when (mode) {
                KeyboardMode.CLASSIC -> MinesweeperBoardView.Style.CLASSIC
                KeyboardMode.QWERTY_GAME -> MinesweeperBoardView.Style.KEYS
                KeyboardMode.QWERTY_BACKGROUND -> MinesweeperBoardView.Style.GHOST
            }
            ghostGameVisible = prefs.backgroundGameEnabled
            maxHeightRatio = prefs.boardHeightPercent / 100f
            hapticsEnabled = prefs.hapticsEnabled
        }
        textSettings = prefs.textSettings()
        updateCellHints()
        refreshKeyLabels()
        updateButtons()
        updateCandidates()
        if (engine?.state == MinesweeperEngine.State.PLAYING) startTimer()
    }

    /** モード切替キー：設定で有効にしたモードを順番に切り替える */
    private fun switchMode() {
        val enabled = KeyboardMode.entries.filter { it in prefs.enabledModes }
        val next = enabled.getOrNull((enabled.indexOf(mode) + 1) % enabled.size) ?: return
        if (next == mode) return
        finishComposing()
        prefs.keyboardMode = next
        applySettings(forceNewGame = true)
    }

    /** 言語切替キー：設定で有効にした入力言語を順番に切り替える */
    private fun switchLanguage() {
        val enabled = InputLanguage.entries.filter { it in prefs.enabledLanguages }
        val next = enabled.getOrNull((enabled.indexOf(composer.language) + 1) % enabled.size) ?: return
        if (next == composer.language) return
        finishComposing()
        prefs.inputLanguage = next
        composer.language = next
        shift = false
        refreshKeyLabels()
        updateButtons()
    }

    private fun updateButtons() {
        modeButton?.label = mode.shortLabel
        if (mode.isQwerty) {
            secondButton?.label = composer.language.label
            thirdButton?.label = "⇧"
            thirdButton?.isToggled = shift && composer.language == InputLanguage.ENGLISH
        } else {
            secondButton?.label = getString(prefs.difficulty.labelRes)
            thirdButton?.label = "🚩"
            thirdButton?.isToggled = flagMode
        }
    }

    private fun refreshKeyLabels() {
        if (!mode.isQwerty) {
            boardView?.keyLabel = null
            return
        }
        val language = composer.language
        val upper = shift && language == InputLanguage.ENGLISH
        boardView?.keyLabel = { r, c -> QwertyLayout.label(r, c, language, upper) }
    }

    /** 座標モードでは閉じたマスに、開くと入力される文字を表示する（クラシックのみ） */
    private fun updateCellHints() {
        val settings = textSettings
        val cols = engine?.cols ?: return
        boardView?.cellHint = when {
            mode != KeyboardMode.CLASSIC || !prefs.showCellHints -> null
            settings.mode == TextMode.COORDINATE_TABLE -> { r, c -> TextGimmick.tableCharAt(r, c, cols, settings.charTable) }
            settings.mode == TextMode.COORDINATE_LABEL -> { r, c -> TextGimmick.coordinateLabel(r, c) }
            else -> null
        }
    }

    private fun startTimer() {
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    private fun updateStatus() {
        val e = engine ?: return
        statusBar?.apply {
            remainingMines = e.remainingMines
            seconds = e.elapsedSeconds()
            face = when (e.state) {
                MinesweeperEngine.State.WON -> ClassicStyle.Face.COOL
                MinesweeperEngine.State.LOST -> ClassicStyle.Face.DEAD
                else -> if (face == ClassicStyle.Face.SURPRISED) face else ClassicStyle.Face.SMILE
            }
        }
    }

    // ---------------------------------------------------------------- 文字入力

    /** QWERTY のキーを 1 つ打つ */
    private fun typeKey(row: Int, col: Int) {
        val key = QwertyLayout.keyAt(row, col)
        val out = composer.type(key, shift && composer.language == InputLanguage.ENGLISH)
        if (shift && key.isLetter()) {
            shift = false
            refreshKeyLabels()
            updateButtons()
        }
        if (out.isNotEmpty()) commit(out) else updateComposing()
        updateCandidates()
    }

    private fun updateComposing() {
        currentInputConnection?.setComposingText(composer.composing, 1)
    }

    /** 変換中の文字列をそのまま確定 */
    private fun finishComposing() {
        if (composer.isEmpty()) return
        commit(composer.finish())
        updateCandidates()
    }

    private fun resetComposing() {
        composer.clear()
        updateCandidates()
    }

    private fun pickCandidate(word: String) {
        if (prefs.hapticsEnabled) statusBar?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        composer.clear()
        commit(word)
        updateCandidates()
    }

    private fun updateCandidates() {
        statusBar?.candidates = if (mode.isQwerty && prefs.predictionEnabled) {
            dictionary.lookup(composer.reading)
        } else {
            emptyList()
        }
    }

    private fun onSpace() {
        if (!composer.isEmpty()) finishComposing() else commit(" ")
    }

    private fun onEnter() {
        if (!composer.isEmpty()) finishComposing() else sendEnter()
    }

    private fun onBackspace() {
        if (composer.backspace()) {
            updateComposing()
            updateCandidates()
        } else {
            deleteOne()
        }
    }

    /** commitText は変換中の文字列があればそれを置き換えて確定する */
    private fun commit(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            // 絵文字（サロゲートペア）も 1 文字として消えるようキーイベントで送る
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    /** ⌫ 長押しで連続削除 */
    private inner class BackspaceRepeater : View.OnTouchListener {
        private val repeat = object : Runnable {
            override fun run() {
                onBackspace()
                handler.postDelayed(this, 60L)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (prefs.hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onBackspace()
                    handler.postDelayed(repeat, 400L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- その他

    private fun switchKeyboard() {
        finishComposing()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToNextInputMethod(false)) showPicker()
        } else {
            @Suppress("DEPRECATION")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = window?.window?.attributes?.token
            @Suppress("DEPRECATION")
            if (token == null || !imm.switchToNextInputMethod(token, false)) showPicker()
        }
    }

    private fun showPicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun openSettings() {
        finishComposing()
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun vibrate(strong: Boolean) {
        if (!prefs.hapticsEnabled) return
        boardView?.performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP,
        )
    }
}
