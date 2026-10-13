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
 * マインスイーパー IME 本体。
 *
 * - 安全なマスを開く → 設定した文字を（開いたマスの数だけ）commitText
 * - 開いている数字マスをタップ → その数字を commitText
 * - 地雷を踏む／クリア → リザルト文を commitText
 */
class MinesweeperIME : InputMethodService(), MinesweeperBoardView.Listener {

    private lateinit var prefs: ImePrefs
    private var engine: MinesweeperEngine? = null
    private var engineConfig: BoardConfig? = null

    private var boardView: MinesweeperBoardView? = null
    private var statusBar: StatusBarView? = null
    private var difficultyButton: ClassicButton? = null
    private var flagButton: ClassicButton? = null

    /** 🚩モード：タップで旗、長押しで開く */
    private var flagMode = false

    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            updateStatus()
            if (engine?.state == MinesweeperEngine.State.PLAYING) handler.postDelayed(this, 250L)
        }
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

        difficultyButton = add(getString(prefs.difficulty.labelRes), 1.4f) {
            prefs.difficulty = prefs.difficulty.next()
            applySettings(forceNewGame = true)
        }
        flagButton = add("🚩", 1f) {
            flagMode = !flagMode
            it.isToggled = flagMode
        }
        add("⚙", 1f) { openSettings() }
        add("🌐", 1f) { switchKeyboard() }.setOnLongClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            true
        }
        add("空白", 1.4f) { commit(" ") }
        add("⌫", 1.2f) { }.setOnTouchListener(BackspaceRepeater())
        add("⏎", 1.2f) { sendEnter() }
        return row
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 設定画面で変更された内容を反映
        applySettings(forceNewGame = false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        handler.removeCallbacks(timerTick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- ゲーム操作

    override fun onCellTap(row: Int, col: Int) {
        val e = engine ?: return
        if (e.isGameOver) {
            // 終了後に盤面をタップしたら新しいゲーム
            newGame()
            return
        }
        if (flagMode) {
            toggleFlag(row, col)
        } else {
            reveal(row, col)
        }
    }

    override fun onCellLongPress(row: Int, col: Int) {
        val e = engine ?: return
        if (e.isGameOver) return
        if (flagMode) reveal(row, col) else toggleFlag(row, col)
    }

    override fun onBoardPressChanged(pressed: Boolean) {
        val e = engine ?: return
        if (!e.isGameOver) statusBar?.face = if (pressed) ClassicStyle.Face.SURPRISED else ClassicStyle.Face.SMILE
    }

    private fun toggleFlag(row: Int, col: Int) {
        if (engine?.toggleFlag(row, col) == true) {
            boardView?.invalidate()
            updateStatus()
        }
    }

    private fun reveal(row: Int, col: Int) {
        val e = engine ?: return
        val cell = e.cell(row, col)
        val wasReady = e.state == MinesweeperEngine.State.READY

        val result = if (cell.isOpen) {
            // 数字マスをタップ → 数字を入力（＋旗の数が合っていれば周囲を一括オープン）
            if (cell.adjacentMines > 0) commit(cell.adjacentMines.toString())
            if (prefs.chordEnabled) e.chord(row, col) else MinesweeperEngine.MoveResult(0)
        } else {
            e.open(row, col)
        }

        // 開いたマスの数だけカスタム文字を入力
        commit(TextGimmick.textForOpenedCells(result.openedCells, prefs.customText, prefs.chainMode, prefs.maxChainRepeat))

        when {
            result.exploded -> {
                vibrate(strong = true)
                commit(getString(R.string.result_game_over, e.openedCount))
            }
            result.won -> commit(getString(R.string.result_clear, e.elapsedSeconds()))
        }

        if (wasReady && e.state == MinesweeperEngine.State.PLAYING) startTimer()
        if (!result.isNoop) boardView?.invalidate()
        updateStatus()
    }

    private fun newGame() {
        val config = prefs.boardConfig()
        engine = MinesweeperEngine(config.rows, config.cols, config.mines)
        engineConfig = config
        handler.removeCallbacks(timerTick)
        boardView?.engine = engine
        updateStatus()
    }

    private fun applySettings(forceNewGame: Boolean) {
        val config = prefs.boardConfig()
        if (forceNewGame || engine == null || config != engineConfig) newGame()
        boardView?.apply {
            maxHeightRatio = prefs.boardHeightPercent / 100f
            hapticsEnabled = prefs.hapticsEnabled
        }
        difficultyButton?.label = getString(prefs.difficulty.labelRes)
        if (engine?.state == MinesweeperEngine.State.PLAYING) startTimer()
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
                deleteOne()
                handler.postDelayed(this, 60L)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (prefs.hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    deleteOne()
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
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun vibrate(strong: Boolean) {
        if (!prefs.hapticsEnabled) return
        boardView?.performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP,
        )
    }
}
