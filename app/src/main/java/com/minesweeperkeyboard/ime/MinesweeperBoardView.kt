package com.minesweeperkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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

    var engine: MinesweeperEngine? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var listener: Listener? = null

    var maxHeightRatio: Float = 0.45f
        set(value) {
            field = value
            requestLayout()
        }

    var hapticsEnabled = true

    private val density = resources.displayMetrics.density
    private val outerBorder = 3f * density

    private var cellSize = 0f
    private val boardLeft: Float get() = (width - cellSize * (engine?.cols ?: 0)) / 2f
    private val boardTop: Float get() = outerBorder

    /** 押下中のマス（閉じたマスを凹ませて表示する） */
    private var pressedRow = -1
    private var pressedCol = -1

    private val bgPaint = Paint().apply { color = ClassicStyle.FACE }
    private val gridPaint = Paint().apply { color = ClassicStyle.SHADOW }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

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
        val maxHeight = resources.displayMetrics.heightPixels * maxHeightRatio
        val availW = width - outerBorder * 2
        val availH = maxHeight - outerBorder * 2
        cellSize = max(1f, min(availW / e.cols, availH / e.rows)).toInt().toFloat()
        val height = (cellSize * e.rows + outerBorder * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val e = engine ?: return
        val boardW = cellSize * e.cols
        val boardLeft = boardLeft
        val boardTop = boardTop

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // 盤面全体は凹んだ枠
        ClassicStyle.drawBevel(
            canvas, boardLeft - outerBorder, 0f, boardLeft + boardW + outerBorder, height.toFloat(),
            outerBorder, raised = false,
        )

        numberPaint.textSize = cellSize * 0.72f
        val bevel = max(1f, (cellSize * 0.12f).toInt().toFloat())
        val textOffset = (numberPaint.descent() + numberPaint.ascent()) / 2f

        for (r in 0 until e.rows) for (c in 0 until e.cols) {
            val cell = e.cell(r, c)
            val l = boardLeft + c * cellSize
            val t = boardTop + r * cellSize
            val rt = l + cellSize
            val b = t + cellSize
            val cx = l + cellSize / 2f
            val cy = t + cellSize / 2f
            val revealMine = cell.isMine && e.state == MinesweeperEngine.State.LOST && !cell.isFlagged
            val looksOpen = cell.isOpen || revealMine || cell.isWrongFlag ||
                (r == pressedRow && c == pressedCol && !cell.isFlagged && !e.isGameOver)

            if (looksOpen) {
                // 開いたマス：フラットなグレー＋左上の灰色の線
                bgPaint.color = if (cell.isExploded) ClassicStyle.EXPLODED_BG else ClassicStyle.FACE
                canvas.drawRect(l, t, rt, b, bgPaint)
                bgPaint.color = ClassicStyle.FACE
                canvas.drawRect(l, t, rt, t + 1f, gridPaint)
                canvas.drawRect(l, t, l + 1f, b, gridPaint)
                when {
                    cell.isWrongFlag -> {
                        ClassicStyle.drawMine(canvas, cx, cy, cellSize)
                        ClassicStyle.drawCross(canvas, cx, cy, cellSize)
                    }
                    revealMine || cell.isExploded -> ClassicStyle.drawMine(canvas, cx, cy, cellSize)
                    cell.isOpen && cell.adjacentMines > 0 -> {
                        numberPaint.color = ClassicStyle.NUMBER_COLORS[cell.adjacentMines]
                        canvas.drawText(cell.adjacentMines.toString(), cx, cy - textOffset, numberPaint)
                    }
                }
            } else {
                // 閉じたマス：凸型ボタン
                canvas.drawRect(l, t, rt, b, bgPaint)
                ClassicStyle.drawBevel(canvas, l, t, rt, b, bevel, raised = true)
                if (cell.isFlagged) ClassicStyle.drawFlag(canvas, cx, cy, cellSize)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        if (cellSize <= 0f) return null
        val c = ((x - boardLeft) / cellSize).toInt()
        val r = ((y - boardTop) / cellSize).toInt()
        if (x < boardLeft || y < boardTop) return null
        return if (e.inBounds(r, c)) r to c else null
    }
}
