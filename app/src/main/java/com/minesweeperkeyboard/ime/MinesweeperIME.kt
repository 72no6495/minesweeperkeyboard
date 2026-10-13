package com.minesweeperkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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
 * QWERTY 系では学習・ユーザー辞書・日本語辞書の単語を予測変換の候補としてニコちゃんの左右に表示する。
 * 文字の入力を最優先にし、候補の検索は別スレッドで行う。
 */
class MinesweeperIME : InputMethodService(), MinesweeperBoardView.Listener {

    private lateinit var prefs: ImePrefs
    private var engine: MinesweeperEngine? = null
    private var engineConfig: BoardConfig? = null
    private var engineMode: KeyboardMode? = null
    private var mode = KeyboardMode.CLASSIC
    private var textSettings = TextSettings()
    private var grid = KeyGrid()

    private val composer = Composer()
    @Volatile
    private var dictionary = UserDictionary("")
    private var dictionarySource: String? = null
    private val learning get() = Learning.model

    private var root: LinearLayout? = null
    private var boardView: MinesweeperBoardView? = null
    private var statusBar: StatusBarView? = null
    private var modeButton: ClassicButton? = null
    /** クラシック：難易度／QWERTY：入力言語 */
    private var secondButton: ClassicButton? = null
    /** クラシック：🚩モード／QWERTY：シフト */
    private var thirdButton: ClassicButton? = null
    private var symbolButton: ClassicButton? = null
    private var globeButton: ClassicButton? = null

    /** 🚩モード：タップで旗、長押しで開く（クラシックのみ） */
    private var flagMode = false
    /** 英語入力のシフト（1 文字だけ）とキャプスロック */
    private var shift = false
    private var capsLock = false
    /** 文の始めなどで自動的に大文字にする状態 */
    private var autoShift = false
    /** 記号モード */
    private var symbols = false
    /** 英語入力で「単語＋スペース」を入れた時刻（スペース 2 回でピリオド用） */
    private var lastWordSpaceAt = 0L

    /** 予測候補と、空白キーで選んでいる候補（-1 なら選択なし） */
    private var candidateList: List<String> = emptyList()
    private var highlight = -1
    /** 候補が「直前の単語の次に来そうな語」のときの、その直前の単語 */
    private var nextWordFor: String? = null
    /** 最後に確定した日本語の単語（次の単語の予測と学習に使う） */
    private var lastWord: String? = null

    /** 普通のキーボードの🚩キー：次に押したキーに旗を立てる／（長押しで）押している間ずっと旗モード */
    private var flagArmed = false
    private var flagLocked = false
    @Volatile
    private var candidateRequest = 0

    private val handler = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("candidates").apply { start() }
    private val workerHandler = Handler(worker.looper)

    private val timerTick = object : Runnable {
        override fun run() {
            updateStatus()
            if (engine?.state == MinesweeperEngine.State.PLAYING) handler.postDelayed(this, 250L)
        }
    }
    /** 裏のマインスイーパーが終わったら少し待って新しいゲームにする */
    private val backgroundRestart = Runnable {
        if (mode == KeyboardMode.QWERTY_BACKGROUND && engine?.isGameOver == true) newGame(carryFlags = true)
    }
    private val saveLearning = Runnable { Learning.save(filesDir) }

    override fun onCreate() {
        super.onCreate()
        prefs = ImePrefs(this)
        ClassicStyle.applyTheme(prefs.theme)
        workerHandler.post { Learning.ensureLoaded(filesDir) }
        // 日本語辞書は初回に展開が必要なので、先にバックグラウンドで読み込んでおく
        if (prefs.systemDictionaryEnabled) SystemDictionaryLoader.getOrLoadAsync(this)
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
        this.root = root

        statusBar = StatusBarView(this).also {
            it.onFaceClick = { onFaceTap() }
            it.onFaceLongClick = { onFaceLongPress() }
            it.onCandidateClick = { index -> pickCandidate(index) }
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

        fun add(label: String, weight: Float, sound: Int = AudioManager.FX_KEYPRESS_STANDARD, onClick: (ClassicButton) -> Unit): ClassicButton {
            val b = ClassicButton(this, label)
            b.setOnClickListener { v ->
                keyFeedback(v, sound)
                onClick(b)
            }
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight))
            return b
        }

        modeButton = add(mode.shortLabel, 1.1f) { switchMode() }
        secondButton = add("", 1.2f) {
            if (mode.isQwerty) switchLanguage() else {
                prefs.difficulty = prefs.difficulty.next()
                applySettings(forceNewGame = true)
            }
        }
        thirdButton = add("", 1f) {
            if (mode.isQwerty) {
                // シフト：押すたびに 1 文字だけ大文字 ⇔ 解除（キャプスロック中なら解除）
                if (capsLock) {
                    capsLock = false
                    shift = false
                } else {
                    shift = !shift
                }
                autoShift = false
                refreshKeyLabels()
            } else {
                flagMode = !flagMode
            }
            updateButtons()
        }
        // シフトの長押しでキャプスロック
        thirdButton?.setOnLongClickListener { v ->
            if (!mode.isQwerty) return@setOnLongClickListener false
            keyFeedback(v, AudioManager.FX_KEYPRESS_STANDARD)
            capsLock = !capsLock
            shift = capsLock
            autoShift = false
            refreshKeyLabels()
            updateButtons()
            true
        }
        symbolButton = add("記号", 1.1f) {
            symbols = !symbols
            if (symbols) finishComposing()
            refreshKeyLabels()
            updateButtons()
        }
        add("⚙", 0.9f) { openSettings() }
        globeButton = add("🌐", 0.9f) { switchKeyboard() }.also {
            it.setOnLongClickListener {
                showPicker()
                true
            }
        }
        add("空白", 1.4f, AudioManager.FX_KEYPRESS_SPACEBAR) { onSpace() }
        add("⌫", 1.1f) { }.setOnTouchListener(BackspaceRepeater())
        add("⏎", 1.1f, AudioManager.FX_KEYPRESS_RETURN) { onEnter() }
        return row
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            resetComposing()
            symbols = false
            if (!capsLock) shift = false
        }
        // 設定画面で変更された内容を反映
        applySettings(forceNewGame = false)
        updateAutoShift()
        refreshKeyLabels()
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
        if (!composer.isEmpty() && candidatesEnd >= 0 && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            resetComposing()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        workerHandler.removeCallbacksAndMessages(null)
        workerHandler.post(saveLearning)
        worker.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 盤面の操作

    override fun onCellTap(row: Int, col: Int) {
        when (mode) {
            KeyboardMode.CLASSIC -> classicTap(row, col)
            KeyboardMode.QWERTY_GAME -> qwertyGameTap(row, col)
            KeyboardMode.QWERTY_BACKGROUND -> {
                keyFeedback(boardView, AudioManager.FX_KEYPRESS_STANDARD)
                when {
                    grid.isFlagKey(row, col) -> {
                        // 🚩キー：次に押したキーに旗（もう一度押すと取り消し）
                        flagArmed = !flagArmed
                        if (!flagArmed) flagLocked = false
                        boardView?.invalidate()
                    }
                    flagArmed -> {
                        backgroundToggleFlag(row, col)
                        if (!flagLocked) flagArmed = false
                        boardView?.invalidate()
                    }
                    else -> {
                        // 入力を先に済ませ、裏のゲームはその後で進める
                        typeKey(row, col)
                        if (prefs.backgroundGameEnabled) handler.post { backgroundOpen(row, col) }
                    }
                }
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
            KeyboardMode.QWERTY_BACKGROUND -> if (grid.isFlagKey(row, col)) {
                // 🚩キーの長押し：解除するまで旗を立て続けるモード
                flagLocked = !flagLocked
                flagArmed = flagLocked
                boardView?.invalidate()
            } else if (flagArmed) {
                onCellTap(row, col)
            } else when (prefs.longPressAction) {
                LongPressAction.SYMBOL -> {
                    keyFeedback(boardView, AudioManager.FX_KEYPRESS_STANDARD)
                    typeSymbol(grid.symbolAt(row, col))
                }
                LongPressAction.FLAG ->
                    if (prefs.backgroundGameEnabled && !e.cell(row, col).isOpen) backgroundToggleFlag(row, col)
                    else onCellTap(row, col)
                // 長押しなしの設定では盤面側で長押しを無効にしているが、念のため普通に入力する
                LongPressAction.NONE -> onCellTap(row, col)
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
            cell.isOpen -> {
                keyFeedback(boardView, AudioManager.FX_KEYPRESS_STANDARD)
                typeKey(row, col)
            }
            else -> {
                val wasReady = e.state == MinesweeperEngine.State.READY
                val result = e.open(row, col)
                if (!result.exploded) {
                    keyFeedback(boardView, AudioManager.FX_KEYPRESS_STANDARD)
                    typeKey(row, col)
                }
                handleResult(e, result, commitResults = true)
                if (wasReady && e.state == MinesweeperEngine.State.PLAYING) startTimer()
            }
        }
    }

    /** 普通のキーボードモード：押したキーの下のマスを裏で開く */
    private fun backgroundOpen(row: Int, col: Int) {
        if (mode != KeyboardMode.QWERTY_BACKGROUND) return
        if (engine?.isGameOver == true) {
            handler.removeCallbacks(backgroundRestart)
            newGame(carryFlags = true)
        }
        val e = engine ?: return
        if (!e.inBounds(row, col)) return
        val cell = e.cell(row, col)
        if (cell.isOpen || cell.isFlagged) return
        val wasReady = e.state == MinesweeperEngine.State.READY
        val result = e.open(row, col)
        handleResult(e, result, commitResults = prefs.backgroundResults)
        if (wasReady && e.state == MinesweeperEngine.State.PLAYING) startTimer()
        if (e.isGameOver) handler.postDelayed(backgroundRestart, 1500L)
    }

    /** 普通のキーボードの裏のゲームに旗を立てる／外す（終わったゲームなら旗を引き継いで新しいゲームに） */
    private fun backgroundToggleFlag(row: Int, col: Int) {
        if (!prefs.backgroundGameEnabled) return
        if (engine?.isGameOver == true) {
            handler.removeCallbacks(backgroundRestart)
            newGame(carryFlags = true)
        }
        toggleFlag(row, col)
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

    /**
     * 新しいゲーム。普通のキーボードでは🚩キーのマスをゲームから外し、
     * [carryFlags] なら前のゲームで最後に立てた旗を最大 3 つ残す（その位置には地雷を置かない）。
     */
    private fun newGame(carryFlags: Boolean = false) {
        val config = prefs.boardConfigFor(mode)
        val previous = engine
        val carried = if (carryFlags && mode == KeyboardMode.QWERTY_BACKGROUND && previous != null &&
            engineConfig == config && engineMode == mode
        ) previous.recentFlags(CARRIED_FLAGS) else emptyList()
        engine = MinesweeperEngine(config.rows, config.cols, config.mines).also { e ->
            if (mode == KeyboardMode.QWERTY_BACKGROUND) e.prepare(blocked = listOfNotNull(grid.flagPosition), flags = carried)
        }
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
        grid = prefs.keyGrid(flagKey = mode == KeyboardMode.QWERTY_BACKGROUND && prefs.backgroundGameEnabled)
        if (mode != KeyboardMode.QWERTY_BACKGROUND) {
            flagArmed = false
            flagLocked = false
        }
        val languages = prefs.enabledLanguages
        composer.language = prefs.inputLanguage.takeIf { it in languages } ?: languages.first()
        composer.punctuation = prefs.punctuationStyle
        prefs.dictionaryText.let {
            if (it != dictionarySource) {
                dictionary = UserDictionary(it)
                dictionarySource = it
            }
        }

        ClassicStyle.applyTheme(prefs.theme)
        root?.setBackgroundColor(ClassicStyle.FACE)

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
            keyHeightDp = prefs.keyHeightDp
            labelScale = prefs.keyTextPercent / 100f
            keyPreview = prefs.keyPreview
            longPressMs = prefs.longPressMs.toLong()
            // 長押しで記号を入力するときは、拡大表示にその記号を出す
            val g = grid
            longPressLabel =
                if (mode == KeyboardMode.QWERTY_BACKGROUND && prefs.longPressAction == LongPressAction.SYMBOL) {
                    { r, c -> if (g.isFlagKey(r, c)) null else g.symbolAt(r, c).toString() }
                } else {
                    null
                }
            keyToggled = { r, c -> flagArmed && g.isFlagKey(r, c) }
            invalidate()
        }
        statusBar?.hapticsEnabled = prefs.hapticsEnabled
        statusBar?.invalidate()
        globeButton?.visibility = if (prefs.showGlobeKey) View.VISIBLE else View.GONE
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
        symbols = false
        prefs.keyboardMode = next
        applySettings(forceNewGame = true)
        updateAutoShift()
        refreshKeyLabels()
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
        capsLock = false
        symbols = false
        updateAutoShift()
        refreshKeyLabels()
        updateButtons()
    }

    private fun updateButtons() {
        modeButton?.label = mode.shortLabel
        if (mode.isQwerty) {
            secondButton?.label = composer.language.label
            thirdButton?.label = if (capsLock) "⇪" else "⇧"
            thirdButton?.isToggled = isEnglish() && (shift || capsLock || autoShift)
            symbolButton?.visibility = View.VISIBLE
            symbolButton?.label = if (symbols) composer.language.label else "記号"
            symbolButton?.isToggled = symbols
        } else {
            secondButton?.label = getString(prefs.difficulty.labelRes)
            thirdButton?.label = "🚩"
            thirdButton?.isToggled = flagMode
            symbolButton?.visibility = View.GONE
        }
    }

    private fun isEnglish() = composer.language == InputLanguage.ENGLISH

    /** 次に打つ英字を大文字にするか */
    private fun upperCaseNext() = isEnglish() && (shift || capsLock || autoShift)

    private fun refreshKeyLabels() {
        if (!mode.isQwerty) {
            boardView?.keyLabel = null
            return
        }
        val g = grid
        val language = composer.language
        val upper = upperCaseNext()
        val sym = symbols
        val punctuation = composer.punctuation
        boardView?.keyLabel = { r, c -> g.label(r, c, language, upper, sym, punctuation) }
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
        if (row !in 0 until grid.rowCount || col !in 0 until grid.colCount || grid.isFlagKey(row, col)) return
        // 空白キーで候補を選んでいる途中なら、その候補を確定してから次の文字へ
        if (highlight >= 0) pickCandidate(highlight)
        if (symbols) {
            typeSymbol(grid.symbolAt(row, col))
            return
        }
        val key = grid.keyAt(row, col)
        val wasUpper = upperCaseNext()
        val out = composer.type(key, wasUpper)
        if (key.isLetter() && !capsLock) {
            shift = false
            autoShift = false
        }
        if (out.isNotEmpty()) {
            commit(out)
            lastWordSpaceAt = 0
            lastWord = null
            updateAutoShift()
        } else {
            updateComposing()
        }
        if (wasUpper != upperCaseNext()) {
            refreshKeyLabels()
            updateButtons()
        }
        updateCandidates()
    }

    /** 記号はそのまま確定して入力する */
    private fun typeSymbol(ch: Char) {
        if (highlight >= 0) pickCandidate(highlight)
        finishComposing()
        commit(ch.toString())
        lastWordSpaceAt = 0
        lastWord = null
        updateAutoShift()
        refreshKeyLabels()
        updateCandidates()
    }

    private fun updateComposing() {
        currentInputConnection?.setComposingText(composer.composing, 1)
    }

    /** 変換中の文字列をそのまま確定 */
    private fun finishComposing() {
        if (composer.isEmpty()) return
        val text = composer.finish()
        highlight = -1
        commit(text)
        lastWord = text.takeIf { composer.language == InputLanguage.ROMAJI }
        updateCandidates()
    }

    private fun resetComposing() {
        composer.clear()
        highlight = -1
        lastWord = null
        updateCandidates()
    }

    /** 候補を確定する（変換中なら変換の結果として、入力していなければ「次の単語」として） */
    private fun pickCandidate(index: Int) {
        val word = candidateList.getOrNull(index) ?: return
        keyFeedback(statusBar, AudioManager.FX_KEYPRESS_STANDARD)
        val reading = composer.reading
        val previous = if (composer.isEmpty()) nextWordFor else lastWord
        if (prefs.learningEnabled) {
            workerHandler.post {
                if (reading.isNotEmpty()) learning.learn(reading, word)
                if (previous != null) learning.learnNext(previous, word)
            }
            workerHandler.removeCallbacks(saveLearning)
            workerHandler.postDelayed(saveLearning, 2000L)
        }
        composer.clear()
        highlight = -1
        if (isEnglish() && prefs.spaceAfterCandidate) {
            commit("$word ")
            lastWordSpaceAt = SystemClock.uptimeMillis()
        } else {
            commit(word)
        }
        lastWord = word.takeIf { !isEnglish() }
        updateAutoShift()
        refreshKeyLabels()
        updateCandidates()
    }

    /**
     * 予測候補を別スレッドで検索する（文字入力を待たせないため）。
     * - 入力中：学習した単語 → ユーザー辞書 → 日本語辞書（文節に区切った変換・完全一致・続き）
     *   ローマ字の途中（"かんj"）なら続きのかなを補ったよみ（かんじ・かんじゃ…）でも探す
     * - 日本語の単語を確定した直後：その次によく使った単語と、助詞などのつなぎの言葉
     */
    private fun updateCandidates() {
        val request = ++candidateRequest
        highlight = -1
        if (!mode.isQwerty || !prefs.predictionEnabled) {
            showCandidates(emptyList(), null)
            return
        }
        val reading = composer.reading
        val variants = composer.readingVariants
        val previous = lastWord
        if (variants.isEmpty()) {
            // 何も入力していない：直前の単語の続きを出す
            if (previous == null || composer.language != InputLanguage.ROMAJI) {
                showCandidates(emptyList(), null)
                return
            }
            val useLearning = prefs.learningEnabled
            workerHandler.post {
                if (request != candidateRequest) return@post
                val learned = if (useLearning) learning.lookupNext(previous) else emptyList()
                val list = (learned + NEXT_WORDS).distinct().take(MAX_CANDIDATES)
                handler.post { if (request == candidateRequest) showCandidates(list, previous) }
            }
            return
        }
        val useLearning = prefs.learningEnabled
        val japanese = composer.language == InputLanguage.ROMAJI && prefs.systemDictionaryEnabled
        val userDictionary = dictionary
        val systemDictionary = if (japanese) {
            SystemDictionaryLoader.getOrLoadAsync(this) { handler.post { updateCandidates() } }
        } else {
            null
        }
        workerHandler.post {
            // 次の入力が来ていたら、この検索は捨てる
            if (request != candidateRequest) return@post
            val result = LinkedHashSet<String>()
            if (useLearning) variants.forEach { result += learning.lookup(it) }
            variants.forEach { result += userDictionary.lookup(it, limit = MAX_USER_CANDIDATES) }
            if (systemDictionary != null) {
                // よみの候補が複数あるときは、それぞれの上位を交互に並べる
                val lists = variants.map { systemDictionary.lookup(it, limit = MAX_CANDIDATES) }
                var i = 0
                while (result.size < MAX_CANDIDATES && lists.any { i < it.size }) {
                    for (l in lists) if (i < l.size) result += l[i]
                    i++
                }
            }
            if (reading.isNotEmpty() && variants.size > 1) result += reading
            val list = result.take(MAX_CANDIDATES)
            handler.post { if (request == candidateRequest) showCandidates(list, null) }
        }
    }

    private fun showCandidates(list: List<String>, nextFor: String?) {
        candidateList = list
        nextWordFor = nextFor
        highlight = -1
        statusBar?.candidates = list
        statusBar?.highlighted = -1
    }

    /** 空白キーで次の候補を選ぶ（変換中の文字列もその候補に置き換えて表示） */
    private fun selectNextCandidate() {
        highlight = (highlight + 1) % candidateList.size
        statusBar?.highlighted = highlight
        currentInputConnection?.setComposingText(candidateList[highlight], 1)
    }

    private fun onSpace() {
        if (mode.isQwerty && isEnglish()) {
            englishSpace()
            return
        }
        when {
            composer.isEmpty() -> {
                lastWord = null
                commit(" ")
                updateCandidates()
            }
            candidateList.isNotEmpty() -> selectNextCandidate()
            else -> finishComposing()
        }
    }

    /** 英語入力のスペース：単語を確定してスペース。続けてもう 1 回でピリオド */
    private fun englishSpace() {
        val now = SystemClock.uptimeMillis()
        when {
            !composer.isEmpty() -> {
                commit(composer.finish() + " ")
                lastWordSpaceAt = now
            }
            prefs.doubleSpacePeriod && lastWordSpaceAt > 0 && now - lastWordSpaceAt < DOUBLE_SPACE_MS -> {
                currentInputConnection?.deleteSurroundingText(1, 0)
                commit(". ")
                lastWordSpaceAt = 0
            }
            else -> {
                commit(" ")
                lastWordSpaceAt = 0
            }
        }
        updateCandidates()
        updateAutoShift()
        refreshKeyLabels()
        updateButtons()
    }

    private fun onEnter() {
        lastWordSpaceAt = 0
        when {
            highlight >= 0 -> pickCandidate(highlight)
            !composer.isEmpty() -> finishComposing()
            else -> {
                lastWord = null
                sendEnter()
                updateCandidates()
            }
        }
        updateAutoShift()
        refreshKeyLabels()
    }

    private fun onBackspace() {
        lastWordSpaceAt = 0
        when {
            // 候補を選んでいる途中なら、選ぶ前（よみ）に戻す
            highlight >= 0 -> {
                highlight = -1
                statusBar?.highlighted = -1
                updateComposing()
            }
            composer.backspace() -> {
                updateComposing()
                updateCandidates()
            }
            else -> {
                lastWord = null
                deleteOne()
                if (nextWordFor != null) updateCandidates()
                if (mode.isQwerty && isEnglish()) {
                    updateAutoShift()
                    refreshKeyLabels()
                }
            }
        }
    }

    /** ニコちゃんのタップ：1 文字削除して、盤面もリセット */
    private fun onFaceTap() {
        keyFeedback(statusBar, AudioManager.FX_KEYPRESS_DELETE)
        onBackspace()
        resetBoard()
    }

    /** ニコちゃんの長押し：入力欄の文字をすべて消して、盤面もリセット */
    private fun onFaceLongPress() {
        resetBoard()
        clearAllText()
    }

    /** 盤面を新しくする（普通のキーボードでは最近の旗を 3 つまで残す） */
    private fun resetBoard() {
        handler.removeCallbacks(backgroundRestart)
        newGame(carryFlags = mode == KeyboardMode.QWERTY_BACKGROUND)
    }

    private fun clearAllText() {
        composer.clear()
        highlight = -1
        lastWord = null
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.setComposingText("", 1)
        ic.finishComposingText()
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        val before = ic.getTextBeforeCursor(CLEAR_ALL_LIMIT, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(CLEAR_ALL_LIMIT, 0)?.length ?: 0
        if (before > 0 || after > 0) ic.deleteSurroundingText(before, after)
        ic.endBatchEdit()
        updateCandidates()
        updateAutoShift()
        refreshKeyLabels()
    }

    /**
     * 英語入力で文の始めなら次の 1 文字を大文字にする（入力欄が大文字化を求めている場合だけ）。
     * 入力欄への問い合わせは単語の区切りでだけ行う。
     */
    private fun updateAutoShift() {
        val before = autoShift
        autoShift = mode.isQwerty && isEnglish() && prefs.autoCapitalize && !capsLock && composer.isEmpty() &&
            (currentInputEditorInfo?.inputType ?: 0).let { type ->
                type != 0 && (currentInputConnection?.getCursorCapsMode(type) ?: 0) != 0
            }
        if (before != autoShift) updateButtons()
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
                    keyFeedback(v, AudioManager.FX_KEYPRESS_DELETE)
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

    /** キーを押したときの振動と音 */
    private fun keyFeedback(view: View?, sound: Int) {
        if (prefs.hapticsEnabled) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (prefs.keySound) (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.playSoundEffect(sound, -1f)
    }

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

    companion object {
        private const val MAX_CANDIDATES = 60
        /** 普通のキーボードでゲームが終わっても残す旗の数 */
        private const val CARRIED_FLAGS = 3
        private const val CLEAR_ALL_LIMIT = 100_000
        /** 日本語の単語を確定した直後に出す、つなぎの言葉 */
        private val NEXT_WORDS = listOf(
            "を", "に", "は", "が", "の", "で", "と", "も", "へ", "から", "まで", "より",
            "です", "ます", "でした", "ました", "ですね", "ですか", "します", "しました", "する", "した", "して",
            "ね", "よ", "な", "って", "けど", "だけ", "など", "、", "。", "！", "？",
        )
        private const val MAX_USER_CANDIDATES = 8
        private const val DOUBLE_SPACE_MS = 1000L
    }
}
