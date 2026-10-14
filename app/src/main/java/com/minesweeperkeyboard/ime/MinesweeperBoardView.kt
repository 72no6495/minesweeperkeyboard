package com.minesweeperkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas でクラシックな盤面を描画する View。
 * キーボード領域に収まるよう、最大高さ（画面高さ×[maxHeightRatio]）と幅からマスの大きさを決めて縮小描画する。
 */
class MinesweeperBoardView(context: Context) : View(context) {

    interface Listener {
        fun onCellTap(row: Int, col: Int)
        fun onCellLongPress(row: Int, col: Int)
        /** 押している間の顔（😮）表示用 */
        fun onBoardPressChanged(pressed: Boolean)
    }

    enum class Style {
        /** 正方形のマス（クラシック） */
        CLASSIC,
        /** 横長のキー型のマス。開いたマスにも [keyLabel] を表示し、数字は隅に小さく */
        KEYS,
        /** 普通のキーボードとして描き、ゲームの状態は上にうっすら重ねる */
        GHOST,
    }

    var engine: MinesweeperEngine? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var listener: Listener? = null

    var style: Style = Style.CLASSIC
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var maxHeightRatio: Float = 0.45f
        set(value) {
            field = value
            requestLayout()
        }

    var hapticsEnabled = true

    /** 盤面の下に必ず残す高さ（ボタンの段など、px） */
    var reservedBelowPx = 0
        set(value) {
            field = value
            requestLayout()
        }

    /** 閉じたマスに薄く表示する文字（座標モードで「開くと何が入力されるか」を示す） */
    var cellHint: ((row: Int, col: Int) -> String?)? = null
        set(value) {
            field = value
            invalidate()
        }

    /** キー型・ゴースト表示でマスに表示するキーの文字 */
    var keyLabel: ((row: Int, col: Int) -> String)? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 長押ししたときに入力される文字（長押し中の拡大表示に使う） */
    var longPressLabel: ((row: Int, col: Int) -> String?)? = null

    /** 押されたままの表示にするキー（🚩キーの「次のキーに旗」待ちなど） */
    var keyToggled: ((row: Int, col: Int) -> Boolean)? = null
        set(value) {
            field = value
            invalidate()
        }

    /** ゴースト表示でゲームを描くか（false なら普通のキーボードだけ） */
    var ghostGameVisible = true
        set(value) {
            field = value
            invalidate()
        }

    /** キー型・ゴースト表示のキーの高さ（dp） */
    var keyHeightDp: Int = 50
        set(value) {
            field = value
            requestLayout()
        }

    /** キーの文字の大きさ（1.0 = 標準） */
    var labelScale: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    /** 押したキーを上に大きく表示する */
    var keyPreview = true

    /** キー型・ゴースト表示で長押しとみなすまでの時間（ms）。0 なら長押しなし */
    var longPressMs: Long = 400L

    private val density = resources.displayMetrics.density
    private val outerBorder = 3f * density
    private val keyHeight: Float get() = keyHeightDp * density

    private var cellW = 0f
    private var cellH = 0f
    private val boardLeft: Float get() = (width - cellW * (engine?.cols ?: 0)) / 2f
    private val boardTop: Float get() = outerBorder

    /** 押下中のマス（閉じたマスを凹ませて表示する）。クラシック盤面用 */
    private var pressedRow = -1
    private var pressedCol = -1

    /** キー型・ゴースト表示で押されている指（ポインター ID → キー） */
    private class KeyPointer(val row: Int, val col: Int, val longPress: Runnable) {
        /** 入力済み（ロールオーバーまたは長押しで処理済み） */
        var handled = false
    }
    private val keyPointers = LinkedHashMap<Int, KeyPointer>()
    private var previewRow = -1
    private var previewCol = -1
    /** 拡大表示しているキーが長押しされた（長押しの文字を表示する） */
    private var previewLongPress = false

    private fun isPressed(r: Int, c: Int): Boolean =
        if (style == Style.CLASSIC) r == pressedRow && c == pressedCol
        else keyPointers.values.any { it.row == r && it.col == c }

    private val bgPaint = Paint().apply { color = ClassicStyle.FACE }
    private val gridPaint = Paint().apply { color = ClassicStyle.SHADOW }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ClassicStyle.SHADOW
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val ghostOpenPaint = Paint().apply { color = Color.argb(70, 0x40, 0x40, 0x40) }
    private val ghostExplodedPaint = Paint().apply { color = Color.argb(90, 0xFF, 0x00, 0x00) }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            cellAt(e.x, e.y)?.let { (r, c) -> listener?.onCellTap(r, c) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            cellAt(e.x, e.y)?.let { (r, c) ->
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                clearPressed()
                listener?.onCellLongPress(r, c)
            }
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val e = engine
        if (e == null) {
            setMeasuredDimension(width, 0)
            return
        }
        var maxHeight = resources.displayMetrics.heightPixels * maxHeightRatio
        // 親から高さの上限が来ているときは、下に並ぶボタンの段の分を残して盤面のほうを縮める
        // （そうしないと最後に測られるボタンの段が潰れる）
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            maxHeight = min(maxHeight, (MeasureSpec.getSize(heightMeasureSpec) - reservedBelowPx).toFloat())
        }
        val availW = width - outerBorder * 2
        val availH = max(1f, maxHeight - outerBorder * 2)
        if (style == Style.CLASSIC) {
            cellW = max(1f, min(availW / e.cols, availH / e.rows)).toInt().toFloat()
            cellH = cellW
        } else {
            cellW = max(1f, availW / e.cols).toInt().toFloat()
            cellH = max(1f, min(keyHeight, availH / e.rows)).toInt().toFloat()
        }
        val height = (cellH * e.rows + outerBorder * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val e = engine ?: return
        val boardW = cellW * e.cols
        val boardLeft = boardLeft
        val boardTop = boardTop

        bgPaint.color = ClassicStyle.FACE
        gridPaint.color = ClassicStyle.SHADOW
        hintPaint.color = ClassicStyle.SHADOW
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // 盤面全体は凹んだ枠
        ClassicStyle.drawBevel(
            canvas, boardLeft - outerBorder, 0f, boardLeft + boardW + outerBorder, height.toFloat(),
            outerBorder, raised = false,
        )

        val unit = min(cellW, cellH)
        val bevel = max(1f, (unit * 0.12f).toInt().toFloat())

        for (r in 0 until e.rows) for (c in 0 until e.cols) {
            val l = boardLeft + c * cellW
            val t = boardTop + r * cellH
            when (style) {
                Style.CLASSIC, Style.KEYS -> drawGameCell(canvas, e, r, c, l, t, bevel)
                Style.GHOST -> drawGhostCell(canvas, e, r, c, l, t, bevel)
            }
        }
        if (keyPreview && style != Style.CLASSIC && previewRow >= 0) drawPreview(canvas, e, previewRow, previewCol)
    }

    /** 押したキーの文字を上に大きく表示する（盤面の中に収まるよう位置を調整） */
    private fun drawPreview(canvas: Canvas, e: MinesweeperEngine, r: Int, c: Int) {
        if (!e.inBounds(r, c)) return
        val text = (if (previewLongPress) longPressLabel?.invoke(r, c) else null) ?: keyLabel?.invoke(r, c) ?: return
        val w = cellW * 1.4f
        val h = cellH * 1.25f
        val cx = boardLeft + c * cellW + cellW / 2f
        val left = (cx - w / 2f).coerceIn(0f, width - w)
        val top = (boardTop + r * cellH - h * 0.9f).coerceAtLeast(0f)
        val rect = RectF(left, top, left + w, top + h)
        ClassicStyle.drawRaisedPanel(canvas, rect, max(1f, density * 2f))
        labelPaint.color = ClassicStyle.DARK
        labelPaint.textSize = h * 0.55f * labelScale
        val tw = labelPaint.measureText(text)
        if (tw > w * 0.85f) labelPaint.textSize *= w * 0.85f / tw
        canvas.drawText(text, rect.centerX(), rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
    }

    private fun drawGameCell(canvas: Canvas, e: MinesweeperEngine, r: Int, c: Int, l: Float, t: Float, bevel: Float) {
        val cell = e.cell(r, c)
        val rt = l + cellW
        val b = t + cellH
        val cx = l + cellW / 2f
        val cy = t + cellH / 2f
        val unit = min(cellW, cellH)
        val keys = style == Style.KEYS
        val label = if (keys) keyLabel?.invoke(r, c) else null
        val revealMine = cell.isMine && e.state == MinesweeperEngine.State.LOST && !cell.isFlagged
        val looksOpen = cell.isOpen || revealMine || cell.isWrongFlag ||
            (isPressed(r, c) && !cell.isFlagged && !e.isGameOver)

        if (looksOpen) {
            // 開いたマス：フラットなグレー＋左上の灰色の線
            bgPaint.color = if (cell.isExploded) ClassicStyle.EXPLODED_BG else ClassicStyle.FACE
            canvas.drawRect(l, t, rt, b, bgPaint)
            bgPaint.color = ClassicStyle.FACE
            canvas.drawRect(l, t, rt, t + 1f, gridPaint)
            canvas.drawRect(l, t, l + 1f, b, gridPaint)
            when {
                cell.isWrongFlag -> {
                    ClassicStyle.drawMine(canvas, cx, cy, unit)
                    ClassicStyle.drawCross(canvas, cx, cy, unit)
                }
                revealMine || cell.isExploded -> ClassicStyle.drawMine(canvas, cx, cy, unit)
                else -> {
                    if (cell.isOpen && cell.adjacentMines > 0) {
                        if (keys) drawCornerNumber(canvas, cell.adjacentMines, rt, t, 255)
                        else drawCenterNumber(canvas, cell.adjacentMines, cx, cy)
                    }
                    label?.let { drawLabel(canvas, it, cx, cy, ClassicStyle.SHADOW) }
                }
            }
        } else {
            // 閉じたマス：凸型ボタン
            canvas.drawRect(l, t, rt, b, bgPaint)
            ClassicStyle.drawBevel(canvas, l, t, rt, b, bevel, raised = true)
            when {
                cell.isFlagged -> ClassicStyle.drawFlag(canvas, cx, cy, unit)
                label != null -> drawLabel(canvas, label, cx, cy, ClassicStyle.DARK)
                else -> cellHint?.invoke(r, c)?.let { drawHint(canvas, it, cx, cy, cellW - bevel * 2) }
            }
        }
    }

    /** 普通のキー＋うっすらゲームの状態 */
    private fun drawGhostCell(canvas: Canvas, e: MinesweeperEngine, r: Int, c: Int, l: Float, t: Float, bevel: Float) {
        val cell = e.cell(r, c)
        val rt = l + cellW
        val b = t + cellH
        val cx = l + cellW / 2f
        val cy = t + cellH / 2f
        val unit = min(cellW, cellH)
        val pressed = isPressed(r, c) || keyToggled?.invoke(r, c) == true

        canvas.drawRect(l, t, rt, b, bgPaint)
        ClassicStyle.drawBevel(canvas, l, t, rt, b, bevel, raised = !pressed)

        if (ghostGameVisible) {
            val revealMine = cell.isMine && e.state == MinesweeperEngine.State.LOST && !cell.isFlagged
            when {
                cell.isExploded -> canvas.drawRect(l, t, rt, b, ghostExplodedPaint)
                cell.isOpen || revealMine -> canvas.drawRect(l + bevel, t + bevel, rt - bevel, b - bevel, ghostOpenPaint)
            }
            // レイヤーを使わず、描画色の不透明度だけで薄く重ねる（キー入力ごとの描画を軽くするため）
            val alpha = GHOST_ALPHA
            when {
                cell.isFlagged -> ClassicStyle.drawFlag(canvas, cx, cy, unit, alpha)
                revealMine || cell.isExploded -> ClassicStyle.drawMine(canvas, cx, cy, unit, alpha)
                cell.isOpen && cell.adjacentMines > 0 -> drawCornerNumber(canvas, cell.adjacentMines, rt, t, alpha)
            }
        }
        keyLabel?.invoke(r, c)?.let { drawLabel(canvas, it, cx, cy, ClassicStyle.DARK) }
    }

    private fun drawCenterNumber(canvas: Canvas, n: Int, cx: Float, cy: Float) {
        numberPaint.textSize = cellW * 0.72f
        numberPaint.color = ClassicStyle.NUMBER_COLORS[n]
        numberPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(n.toString(), cx, cy - (numberPaint.descent() + numberPaint.ascent()) / 2f, numberPaint)
    }

    /** キー型のマスでは数字を右上に小さく描く */
    private fun drawCornerNumber(canvas: Canvas, n: Int, right: Float, top: Float, alpha: Int) {
        numberPaint.textSize = cellH * 0.34f
        numberPaint.color = ClassicStyle.NUMBER_COLORS[n]
        numberPaint.alpha = alpha
        numberPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(n.toString(), right - cellW * 0.08f, top - numberPaint.ascent() + cellH * 0.04f, numberPaint)
        numberPaint.alpha = 255
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float, color: Int) {
        labelPaint.color = color
        labelPaint.textSize = min(cellH * 0.45f, cellW * 0.6f) * labelScale
        val w = labelPaint.measureText(text)
        val maxW = cellW * 0.8f
        if (w > maxW) labelPaint.textSize *= maxW / w
        canvas.drawText(text, cx, cy - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
    }

    private fun drawHint(canvas: Canvas, text: String, cx: Float, cy: Float, maxWidth: Float) {
        if (text.isEmpty() || maxWidth <= 0f) return
        hintPaint.textSize = cellH * 0.5f
        val w = hintPaint.measureText(text)
        if (w > maxWidth) hintPaint.textSize *= maxWidth / w
        val y = cy - (hintPaint.descent() + hintPaint.ascent()) / 2f
        canvas.drawText(text, cx, y, hintPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (style != Style.CLASSIC) return onKeyTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cell = cellAt(event.x, event.y)
                pressedRow = cell?.first ?: -1
                pressedCol = cell?.second ?: -1
                if (engine?.isGameOver == false) listener?.onBoardPressChanged(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val cell = cellAt(event.x, event.y)
                if (cell?.first != pressedRow || cell.second != pressedCol) {
                    pressedRow = cell?.first ?: -1
                    pressedCol = cell?.second ?: -1
                    invalidate()
                }
            }
        }
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            clearPressed()
        }
        return handled || super.onTouchEvent(event)
    }

    /**
     * キー型・ゴースト表示のタッチ処理（マルチタッチ対応）。
     * - キーは指を置いた位置で決まり、指を離したときに入力する
     * - 前の指を離す前に次の指が触れたら、前のキーをその場で入力する（ロールオーバー）。速く打っても取りこぼさない
     * - [longPressMs] 押し続けたら長押し
     */
    private fun onKeyTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // 最初の指：前のジェスチャーの取り残し（UP を受け取れなかった指）は入力せずに捨てる
                    keyPointers.values.forEach { removeCallbacks(it.longPress) }
                    keyPointers.clear()
                }
                // 押しっぱなしの前のキーは、この時点で入力を確定させる
                for (p in keyPointers.values) {
                    if (!p.handled) {
                        p.handled = true
                        removeCallbacks(p.longPress)
                        listener?.onCellTap(p.row, p.col)
                    }
                }
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val cell = cellAt(event.getX(index), event.getY(index))
                if (cell != null) {
                    val (r, c) = cell
                    lateinit var pointer: KeyPointer
                    pointer = KeyPointer(r, c, Runnable {
                        if (!pointer.handled && keyPointers[id] === pointer) {
                            pointer.handled = true
                            if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            if (previewRow == r && previewCol == c) {
                                previewLongPress = true
                                invalidate()
                            }
                            listener?.onCellLongPress(r, c)
                        }
                    })
                    keyPointers[id] = pointer
                    if (longPressMs > 0) postDelayed(pointer.longPress, longPressMs)
                    previewRow = r
                    previewCol = c
                    previewLongPress = false
                    if (keyPointers.size == 1 && engine?.isGameOver == false) listener?.onBoardPressChanged(true)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                keyPointers.remove(id)?.let { p ->
                    removeCallbacks(p.longPress)
                    if (!p.handled) listener?.onCellTap(p.row, p.col)
                }
                if (keyPointers.isEmpty()) {
                    previewRow = -1
                    previewCol = -1
                    previewLongPress = false
                    listener?.onBoardPressChanged(false)
                } else {
                    keyPointers.values.last().let { previewRow = it.row; previewCol = it.col }
                    previewLongPress = false
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                // システムにタッチを取られた場合も、押していたキーは入力しておく
                for (p in keyPointers.values) {
                    removeCallbacks(p.longPress)
                    if (!p.handled) listener?.onCellTap(p.row, p.col)
                }
                keyPointers.clear()
                previewRow = -1
                previewCol = -1
                listener?.onBoardPressChanged(false)
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        keyPointers.values.forEach { removeCallbacks(it.longPress) }
        keyPointers.clear()
    }

    private fun clearPressed() {
        if (pressedRow != -1) {
            pressedRow = -1
            pressedCol = -1
            invalidate()
        }
        listener?.onBoardPressChanged(false)
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val e = engine ?: return null
        if (cellW <= 0f || cellH <= 0f) return null
        if (x < boardLeft || y < boardTop) return null
        val c = ((x - boardLeft) / cellW).toInt()
        val r = ((y - boardTop) / cellH).toInt()
        return if (e.inBounds(r, c)) r to c else null
    }

    companion object {
        /** ゴースト表示でゲームを重ねる不透明度（0〜255） */
        private const val GHOST_ALPHA = 90
    }
}
