package com.minesweeperkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * クリップボードのパネル（盤面の代わりに表示する）。
 * 上の段：コピー・切り取り・すべて選択・貼り付け・履歴の消去
 * その下：履歴の一覧（タップで貼り付け、📌でピン留め、🗑で削除）
 */
class ClipboardPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onClipPaste(text: String)
        fun onClipTogglePin(text: String)
        fun onClipDelete(text: String)
        /** android.R.id.copy / cut / selectAll / paste */
        fun onClipEditorAction(id: Int)
        fun onClipClearHistory()
    }

    private val density = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val empty = TextView(context).apply {
        text = "履歴はまだありません。コピーした文字がここに残ります"
        gravity = Gravity.CENTER
        val p = (16 * density).toInt()
        setPadding(p, p, p, p)
    }

    init {
        orientation = VERTICAL
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun action(label: String, onClick: () -> Unit) {
            actions.addView(ClassicButton(context, label).apply { setOnClickListener { onClick() } },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        action("コピー") { listener.onClipEditorAction(android.R.id.copy) }
        action("切り取り") { listener.onClipEditorAction(android.R.id.cut) }
        action("全選択") { listener.onClipEditorAction(android.R.id.selectAll) }
        action("貼り付け") { listener.onClipEditorAction(android.R.id.paste) }
        action("履歴消去") { listener.onClipClearHistory() }
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setItems(items: List<ClipboardHistory.Entry>) {
        list.removeAllViews()
        empty.setTextColor(ClassicStyle.DARK)
        if (items.isEmpty()) {
            list.addView(empty)
            return
        }
        for (entry in items) {
            list.addView(ClipRowView(context, entry), LayoutParams(LayoutParams.MATCH_PARENT, (40 * density).toInt()))
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawColor(ClassicStyle.FACE)
        super.dispatchDraw(canvas)
    }

    /** 履歴の 1 行：本文（1 行に省略）＋ 📌 ＋ 🗑 */
    private inner class ClipRowView(context: Context, private val entry: ClipboardHistory.Entry) : View(context) {
        private val bevel = 2f * density
        private val bg = Paint()
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * density }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 17f * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        private var pressedArea = -1

        init {
            isClickable = true
        }

        private fun pinRect() = RectF(width - height * 2f, 0f, width - height.toFloat(), height.toFloat())
        private fun deleteRect() = RectF(width - height.toFloat(), 0f, width.toFloat(), height.toFloat())
        private fun bodyRect() = RectF(0f, 0f, width - height * 2f, height.toFloat())

        override fun onDraw(canvas: Canvas) {
            bg.color = ClassicStyle.FACE
            textPaint.color = ClassicStyle.DARK
            drawButton(canvas, bodyRect(), pressedArea == AREA_BODY)
            drawButton(canvas, pinRect(), entry.pinned || pressedArea == AREA_PIN)
            drawButton(canvas, deleteRect(), pressedArea == AREA_DELETE)

            val body = bodyRect()
            val oneLine = entry.text.replace('\n', ' ')
            val text = TextUtils.ellipsize(oneLine, textPaint, body.width() - bevel * 6, TextUtils.TruncateAt.END)
            val y = body.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, 0, text.length, body.left + bevel * 3, y, textPaint)

            val iy = height / 2f - (iconPaint.descent() + iconPaint.ascent()) / 2f
            canvas.drawText(if (entry.pinned) "📌" else "📍", pinRect().centerX(), iy, iconPaint)
            canvas.drawText("🗑", deleteRect().centerX(), iy, iconPaint)
        }

        private fun drawButton(canvas: Canvas, r: RectF, down: Boolean) {
            canvas.drawRect(r, bg)
            ClassicStyle.drawBevel(canvas, r.left, r.top, r.right, r.bottom, bevel, raised = !down)
        }

        private fun areaAt(x: Float, y: Float) = when {
            pinRect().contains(x, y) -> AREA_PIN
            deleteRect().contains(x, y) -> AREA_DELETE
            bodyRect().contains(x, y) -> AREA_BODY
            else -> -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedArea = areaAt(event.x, event.y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (areaAt(event.x, event.y) != pressedArea && pressedArea != -1) {
                    pressedArea = -1
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val area = pressedArea
                    pressedArea = -1
                    invalidate()
                    if (area != -1 && area == areaAt(event.x, event.y)) {
                        performClick()
                        when (area) {
                            AREA_BODY -> listener.onClipPaste(entry.text)
                            AREA_PIN -> listener.onClipTogglePin(entry.text)
                            AREA_DELETE -> listener.onClipDelete(entry.text)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedArea = -1
                    invalidate()
                }
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }

    private companion object {
        const val AREA_BODY = 0
        const val AREA_PIN = 1
        const val AREA_DELETE = 2
    }
}
