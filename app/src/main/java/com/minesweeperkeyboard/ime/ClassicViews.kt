package com.minesweeperkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 上部のステータスバー：残り地雷数 LED ／ ニコちゃん ／ タイマー LED。
 * 予測変換の候補があるときは LED の代わりにニコちゃんの左右へ候補ボタンを並べる。
 */
class StatusBarView(context: Context) : View(context) {

    var remainingMines = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var seconds = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var face = ClassicStyle.Face.SMILE
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 予測変換の候補（最大 4 つ。左に 1・2 番目、右に 3・4 番目。2 つ以下なら左右に 1 つずつ） */
    var candidates: List<String> = emptyList()
        set(v) { if (field != v) { field = v.take(4); pressedCandidate = -1; invalidate() } }

    var onFaceClick: (() -> Unit)? = null
    var onCandidateClick: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val desiredHeight = (46 * density).toInt()
    private val border = 3f * density
    private val faceRect = RectF()
    private var facePressed = false

    private val candidateRects = ArrayList<RectF>()
    private var pressedCandidate = -1

    private val bg = Paint().apply { color = ClassicStyle.FACE }
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ClassicStyle.DARK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        // 凹んだパネル
        ClassicStyle.drawBevel(canvas, border, border, w - border, h - border, border * 0.7f, raised = false)

        val inner = h - border * 4
        val margin = border * 3
        val faceSize = inner
        faceRect.set((w - faceSize) / 2f, (h - faceSize) / 2f, (w + faceSize) / 2f, (h + faceSize) / 2f)

        layoutCandidates(margin, inner)
        if (candidates.isEmpty()) {
            val ledH = inner * 0.85f
            val ledW = ledH * 1.75f
            val ledTop = (h - ledH) / 2f
            ClassicStyle.drawLedCounter(canvas, RectF(margin, ledTop, margin + ledW, ledTop + ledH), remainingMines)
            ClassicStyle.drawLedCounter(canvas, RectF(w - margin - ledW, ledTop, w - margin, ledTop + ledH), seconds)
        } else {
            for (i in candidateRects.indices) drawCandidate(canvas, candidateRects[i], candidates[i], i == pressedCandidate)
        }

        canvas.drawRect(faceRect, bg)
        ClassicStyle.drawBevel(
            canvas, faceRect.left, faceRect.top, faceRect.right, faceRect.bottom,
            border * 0.8f, raised = !facePressed,
        )
        val shift = if (facePressed) border * 0.4f else 0f
        ClassicStyle.drawFace(canvas, faceRect.centerX() + shift, faceRect.centerY() + shift, faceSize * 0.85f, face)
    }

    private fun layoutCandidates(margin: Float, inner: Float) {
        candidateRects.clear()
        if (candidates.isEmpty()) return
        val top = (height - inner) / 2f
        val bottom = top + inner
        val gap = border
        val left = RectF(margin, top, faceRect.left - gap * 2, bottom)
        val right = RectF(faceRect.right + gap * 2, top, width - margin, bottom)
        val (leftCount, rightCount) = if (candidates.size <= 2) 1 to 1 else 2 to 2
        fun split(area: RectF, n: Int) {
            val slotW = (area.width() - gap * (n - 1)) / n
            for (i in 0 until n) {
                val l = area.left + i * (slotW + gap)
                candidateRects += RectF(l, area.top, l + slotW, area.bottom)
            }
        }
        split(left, leftCount)
        split(right, rightCount)
        while (candidateRects.size > candidates.size) candidateRects.removeAt(candidateRects.size - 1)
    }

    private fun drawCandidate(canvas: Canvas, r: RectF, text: String, pressed: Boolean) {
        canvas.drawRect(r, bg)
        ClassicStyle.drawBevel(canvas, r.left, r.top, r.right, r.bottom, border * 0.7f, raised = !pressed)
        var size = r.height() * 0.45f
        candidatePaint.textSize = size
        val maxW = r.width() - border * 3
        val textW = candidatePaint.measureText(text)
        if (textW > maxW) {
            size *= maxW / textW
            candidatePaint.textSize = size
        }
        val shift = if (pressed) border * 0.4f else 0f
        val y = r.centerY() - (candidatePaint.descent() + candidatePaint.ascent()) / 2f
        canvas.drawText(text, r.centerX() + shift, y + shift, candidatePaint)
    }

    private fun candidateAt(x: Float, y: Float): Int = candidateRects.indexOfFirst { it.contains(x, y) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inFace = faceRect.contains(event.x, event.y)
        val cand = candidateAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when {
                    inFace -> facePressed = true
                    cand >= 0 -> pressedCandidate = cand
                    else -> return false
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // 押した場所から指が外れたら押下を解除する
                if (facePressed && !inFace) {
                    facePressed = false
                    invalidate()
                }
                if (pressedCandidate >= 0 && cand != pressedCandidate) {
                    pressedCandidate = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (facePressed && inFace) {
                    performClick()
                    onFaceClick?.invoke()
                } else if (pressedCandidate >= 0 && cand == pressedCandidate) {
                    performClick()
                    candidates.getOrNull(cand)?.let { onCandidateClick?.invoke(it) }
                }
                facePressed = false
                pressedCandidate = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                facePressed = false
                pressedCandidate = -1
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Windows 95 風の立体ボタン（押すと凹む） */
class ClassicButton(context: Context, label: String) : View(context) {

    var label: String = label
        set(v) { field = v; invalidate() }

    /** トグル状態（凹んだまま表示） */
    var isToggled = false
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val bevel = 2f * density
    private val bgPaint = Paint().apply { color = ClassicStyle.FACE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ClassicStyle.DARK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 15f * density
    }

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize((44 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val down = isPressed || isToggled
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // 外側の黒枠＋立体枠
        ClassicStyle.drawBevel(canvas, 0f, 0f, w, h, density, raised = !down)
        ClassicStyle.drawBevel(canvas, density, density, w - density, h - density, bevel, raised = !down)

        // ラベルが幅に収まるよう縮める
        var size = 15f * density
        textPaint.textSize = size
        while (size > 8f * density && textPaint.measureText(label) > w - bevel * 4) {
            size -= density
            textPaint.textSize = size
        }
        val shift = if (down) density else 0f
        val y = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, w / 2f + shift, y + shift, textPaint)
    }
}
