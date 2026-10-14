package com.minesweeperkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * 上部のステータスバー：残り地雷数 LED ／ ニコちゃん ／ タイマー LED。
 * 予測変換の候補があるときは LED の代わりに候補を並べる。候補はニコちゃんの左から右へ 1 本の帯としてつながっていて、
 * 左右にスワイプしてスクロールできる。
 */
class StatusBarView(context: Context) : View(context) {

    var remainingMines = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var seconds = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var face = ClassicStyle.Face.SMILE
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 予測変換の候補 */
    var candidates: List<String> = emptyList()
        set(v) {
            if (field == v) return
            field = v
            highlighted = -1
            pressedCandidate = -1
            scroller.forceFinished(true)
            scrollPos = 0f
            itemsDirty = true
            invalidate()
        }

    /** 選択中（空白キーで選んでいる）の候補。-1 なら選択なし */
    var highlighted = -1
        set(v) {
            field = v
            if (v >= 0) scrollIntoView(v)
            invalidate()
        }

    var hapticsEnabled = true

    var onFaceClick: (() -> Unit)? = null
    var onFaceLongClick: (() -> Unit)? = null
    var onCandidateClick: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val desiredHeight = (46 * density).toInt()
    private val border = 3f * density
    private val faceRect = RectF()
    private var facePressed = false
    private var faceLongPressed = false
    private val faceLongPress = Runnable {
        if (facePressed) {
            faceLongPressed = true
            facePressed = false
            if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onFaceLongClick?.invoke()
            invalidate()
        }
    }

    // 候補の帯：ニコちゃんの左の領域と右の領域を 1 本につないで表示する
    private val leftArea = RectF()
    private val rightArea = RectF()
    private var itemStart = FloatArray(0)
    private var itemWidth = FloatArray(0)
    private var contentWidth = 0f
    private var itemsDirty = true
    private var scrollPos = 0f
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var trackingCandidates = false
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        itemsDirty = true
    }

    private val visibleWidth: Float get() = leftArea.width() + rightArea.width()
    private val maxScroll: Float get() = (contentWidth - visibleWidth).coerceAtLeast(0f)

    private fun layoutAreas() {
        val h = height.toFloat()
        val inner = h - border * 4
        faceRect.set((width - inner) / 2f, (h - inner) / 2f, (width + inner) / 2f, (h + inner) / 2f)
        val margin = border * 3
        val top = (h - inner) / 2f
        leftArea.set(margin, top, faceRect.left - border * 2, top + inner)
        rightArea.set(faceRect.right + border * 2, top, width - margin, top + inner)
    }

    private fun layoutItems() {
        layoutAreas()
        candidatePaint.textSize = leftArea.height() * 0.45f
        val minW = 44f * density
        val pad = 10f * density
        val gap = border
        itemStart = FloatArray(candidates.size)
        itemWidth = FloatArray(candidates.size)
        var x = 0f
        for (i in candidates.indices) {
            val w = maxOf(minW, minOf(candidatePaint.measureText(candidates[i]) + pad * 2, visibleWidth * 0.9f))
            itemStart[i] = x
            itemWidth[i] = w
            x += w + gap
        }
        contentWidth = (x - gap).coerceAtLeast(0f)
        scrollPos = scrollPos.coerceIn(0f, maxScroll)
        itemsDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        if (itemsDirty) layoutItems() else layoutAreas()
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = ClassicStyle.FACE
        candidatePaint.color = ClassicStyle.DARK
        canvas.drawRect(0f, 0f, w, h, bg)
        // 凹んだパネル
        ClassicStyle.drawBevel(canvas, border, border, w - border, h - border, border * 0.7f, raised = false)

        if (candidates.isEmpty()) {
            val inner = faceRect.height()
            val margin = border * 3
            val ledH = inner * 0.85f
            val ledW = ledH * 1.75f
            val ledTop = (h - ledH) / 2f
            ClassicStyle.drawLedCounter(canvas, RectF(margin, ledTop, margin + ledW, ledTop + ledH), remainingMines)
            ClassicStyle.drawLedCounter(canvas, RectF(w - margin - ledW, ledTop, w - margin, ledTop + ledH), seconds)
        } else {
            drawStrip(canvas, leftArea, 0f)
            drawStrip(canvas, rightArea, leftArea.width())
            drawScrollHints(canvas)
        }

        canvas.drawRect(faceRect, bg)
        ClassicStyle.drawBevel(
            canvas, faceRect.left, faceRect.top, faceRect.right, faceRect.bottom,
            border * 0.8f, raised = !facePressed,
        )
        val shift = if (facePressed) border * 0.4f else 0f
        ClassicStyle.drawFace(canvas, faceRect.centerX() + shift, faceRect.centerY() + shift, faceRect.height() * 0.85f, face)
    }

    /** 帯のうち [area] に見えている部分を描く（[areaOffset] = 帯の中でこの領域が始まる位置） */
    private fun drawStrip(canvas: Canvas, area: RectF, areaOffset: Float) {
        if (area.width() <= 0f) return
        canvas.save()
        canvas.clipRect(area)
        val origin = area.left - scrollPos - areaOffset
        for (i in candidates.indices) {
            val l = origin + itemStart[i]
            val r = l + itemWidth[i]
            if (r < area.left || l > area.right) continue
            drawCandidate(canvas, RectF(l, area.top, r, area.bottom), candidates[i], i == pressedCandidate, i == highlighted)
        }
        canvas.restore()
    }

    /** まだ続きがある側に小さな三角を出す */
    private fun drawScrollHints(canvas: Canvas) {
        if (maxScroll <= 0f) return
        candidatePaint.textSize = leftArea.height() * 0.35f
        val y = leftArea.centerY() - (candidatePaint.descent() + candidatePaint.ascent()) / 2f
        if (scrollPos > 1f) canvas.drawText("◀", border * 2.2f, y, candidatePaint)
        if (scrollPos < maxScroll - 1f) canvas.drawText("▶", width - border * 2.2f, y, candidatePaint)
        candidatePaint.textSize = leftArea.height() * 0.45f
    }

    private fun drawCandidate(canvas: Canvas, r: RectF, text: String, pressed: Boolean, selected: Boolean) {
        canvas.drawRect(r, bg)
        ClassicStyle.drawBevel(canvas, r.left, r.top, r.right, r.bottom, border * 0.7f, raised = !(pressed || selected))
        var size = r.height() * 0.45f
        candidatePaint.textSize = size
        val maxW = r.width() - border * 3
        val textW = candidatePaint.measureText(text)
        if (textW > maxW) {
            size *= maxW / textW
            candidatePaint.textSize = size
        }
        val shift = if (pressed || selected) border * 0.4f else 0f
        val y = r.centerY() - (candidatePaint.descent() + candidatePaint.ascent()) / 2f
        canvas.drawText(text, r.centerX() + shift, y + shift, candidatePaint)
        if (selected) {
            // 選択中の候補は下に線を引く
            candidatePaint.strokeWidth = border * 0.6f
            canvas.drawLine(r.left + border * 2, r.bottom - border * 1.6f, r.right - border * 2, r.bottom - border * 1.6f, candidatePaint)
        }
    }

    /** 画面上の x を帯の中の位置に変換（候補の領域外なら null） */
    private fun stripPosition(x: Float, y: Float): Float? = when {
        leftArea.contains(x, y) -> scrollPos + (x - leftArea.left)
        rightArea.contains(x, y) -> scrollPos + leftArea.width() + (x - rightArea.left)
        else -> null
    }

    private fun candidateAt(x: Float, y: Float): Int {
        val pos = stripPosition(x, y) ?: return -1
        for (i in candidates.indices) if (pos >= itemStart[i] && pos <= itemStart[i] + itemWidth[i]) return i
        return -1
    }

    private fun scrollIntoView(i: Int) {
        if (itemsDirty) layoutItems()
        if (i !in itemStart.indices || visibleWidth <= 0f) return
        val start = itemStart[i]
        val end = start + itemWidth[i]
        val target = when {
            start < scrollPos -> start
            end > scrollPos + visibleWidth -> end - visibleWidth
            else -> return
        }
        scroller.forceFinished(true)
        scrollPos = target.coerceIn(0f, maxScroll)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currX.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                faceLongPressed = false
                trackingCandidates = false
                dragging = false
                when {
                    faceRect.contains(event.x, event.y) -> {
                        facePressed = true
                        postDelayed(faceLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    candidates.isNotEmpty() && stripPosition(event.x, event.y) != null -> {
                        scroller.forceFinished(true)
                        trackingCandidates = true
                        downX = event.x
                        lastX = event.x
                        pressedCandidate = candidateAt(event.x, event.y)
                        velocityTracker?.recycle()
                        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    }
                    else -> return false
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (facePressed && !faceRect.contains(event.x, event.y)) {
                    facePressed = false
                    removeCallbacks(faceLongPress)
                    invalidate()
                }
                if (trackingCandidates) {
                    velocityTracker?.addMovement(event)
                    if (!dragging && abs(event.x - downX) > touchSlop) {
                        dragging = true
                        pressedCandidate = -1
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        scrollPos = (scrollPos - (event.x - lastX)).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                    lastX = event.x
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(faceLongPress)
                if (facePressed && !faceLongPressed && faceRect.contains(event.x, event.y)) {
                    performClick()
                    onFaceClick?.invoke()
                } else if (trackingCandidates) {
                    if (dragging) {
                        velocityTracker?.apply {
                            addMovement(event)
                            computeCurrentVelocity(1000)
                            scroller.fling(scrollPos.toInt(), 0, -xVelocity.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
                            postInvalidateOnAnimation()
                        }
                    } else if (pressedCandidate >= 0 && candidateAt(event.x, event.y) == pressedCandidate) {
                        performClick()
                        onCandidateClick?.invoke(pressedCandidate)
                    }
                }
                resetTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(faceLongPress)
                resetTouch()
            }
        }
        return true
    }

    private fun resetTouch() {
        facePressed = false
        pressedCandidate = -1
        trackingCandidates = false
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Windows 95 風の立体ボタン（押すと凹む） */
class ClassicButton(context: Context, label: String) : View(context) {

    companion object {
        const val BUTTON_HEIGHT_DP = 44
    }

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
        // 親の高さが足りなくても潰さない（盤面のほうを縮めて場所を空ける）
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (BUTTON_HEIGHT_DP * density).toInt())
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val down = isPressed || isToggled
        bgPaint.color = ClassicStyle.FACE
        textPaint.color = ClassicStyle.DARK
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
