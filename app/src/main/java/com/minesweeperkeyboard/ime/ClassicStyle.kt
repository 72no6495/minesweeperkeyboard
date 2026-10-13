package com.minesweeperkeyboard.ime

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Windows 95/98 風の配色と立体枠の描画ヘルパー */
object ClassicStyle {
    val FACE = Color.rgb(0xC0, 0xC0, 0xC0)
    val HIGHLIGHT = Color.WHITE
    val SHADOW = Color.rgb(0x80, 0x80, 0x80)
    val DARK = Color.BLACK
    val EXPLODED_BG = Color.RED

    /** 数字の色（インデックス = 数字） */
    val NUMBER_COLORS = intArrayOf(
        Color.TRANSPARENT,
        Color.rgb(0x00, 0x00, 0xFF), // 1 青
        Color.rgb(0x00, 0x80, 0x00), // 2 緑
        Color.rgb(0xFF, 0x00, 0x00), // 3 赤
        Color.rgb(0x00, 0x00, 0x80), // 4 紺
        Color.rgb(0x80, 0x00, 0x00), // 5 えんじ
        Color.rgb(0x00, 0x80, 0x80), // 6 青緑
        Color.rgb(0x00, 0x00, 0x00), // 7 黒
        Color.rgb(0x80, 0x80, 0x80), // 8 灰
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 立体枠を描く。raised = true で凸（左上が白・右下が灰）、false で凹。
     * 斜めの角はクラシック同様に三角形で分割する。
     */
    fun drawBevel(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, w: Float, raised: Boolean) {
        val topLeft = if (raised) HIGHLIGHT else SHADOW
        val bottomRight = if (raised) SHADOW else HIGHLIGHT
        fill.style = Paint.Style.FILL
        fill.isAntiAlias = false

        val path = Path()
        // 上辺+左辺
        path.moveTo(l, t); path.lineTo(r, t); path.lineTo(r - w, t + w)
        path.lineTo(l + w, t + w); path.lineTo(l + w, b - w); path.lineTo(l, b); path.close()
        fill.color = topLeft
        canvas.drawPath(path, fill)

        path.reset()
        // 下辺+右辺
        path.moveTo(r, b); path.lineTo(l, b); path.lineTo(l + w, b - w)
        path.lineTo(r - w, b - w); path.lineTo(r - w, t + w); path.lineTo(r, t); path.close()
        fill.color = bottomRight
        canvas.drawPath(path, fill)
    }

    fun drawRaisedPanel(canvas: Canvas, rect: RectF, w: Float) {
        fill.color = FACE
        fill.style = Paint.Style.FILL
        canvas.drawRect(rect, fill)
        drawBevel(canvas, rect.left, rect.top, rect.right, rect.bottom, w, raised = true)
    }

    /** 地雷（黒丸＋トゲ＋白いハイライト） */
    fun drawMine(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val radius = size * 0.28f
        fill.isAntiAlias = true
        fill.style = Paint.Style.FILL
        fill.color = DARK
        fill.strokeWidth = size * 0.08f
        val spike = size * 0.40f
        val d = spike * 0.72f
        canvas.drawLine(cx - spike, cy, cx + spike, cy, fill)
        canvas.drawLine(cx, cy - spike, cx, cy + spike, fill)
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, fill)
        canvas.drawLine(cx - d, cy + d, cx + d, cy - d, fill)
        canvas.drawCircle(cx, cy, radius, fill)
        fill.color = HIGHLIGHT
        canvas.drawRect(cx - radius * 0.55f, cy - radius * 0.55f, cx - radius * 0.15f, cy - radius * 0.15f, fill)
    }

    /** 旗（赤い三角＋黒いポールと台座） */
    fun drawFlag(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        fill.isAntiAlias = true
        fill.style = Paint.Style.FILL
        val poleX = cx + size * 0.04f
        // 台座
        fill.color = DARK
        canvas.drawRect(cx - size * 0.26f, cy + size * 0.22f, cx + size * 0.26f, cy + size * 0.30f, fill)
        canvas.drawRect(cx - size * 0.14f, cy + size * 0.16f, cx + size * 0.18f, cy + size * 0.22f, fill)
        // ポール
        canvas.drawRect(poleX - size * 0.03f, cy - size * 0.30f, poleX + size * 0.03f, cy + size * 0.18f, fill)
        // 旗
        val path = Path()
        path.moveTo(poleX + size * 0.03f, cy - size * 0.32f)
        path.lineTo(cx - size * 0.28f, cy - size * 0.16f)
        path.lineTo(poleX + size * 0.03f, cy - size * 0.02f)
        path.close()
        fill.color = Color.RED
        canvas.drawPath(path, fill)
    }

    /** 誤った旗の上に描く赤い × */
    fun drawCross(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        fill.isAntiAlias = true
        fill.color = Color.RED
        fill.strokeWidth = size * 0.08f
        val d = size * 0.32f
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, fill)
        canvas.drawLine(cx - d, cy + d, cx + d, cy - d, fill)
    }

    enum class Face { SMILE, SURPRISED, DEAD, COOL }

    /** ニコちゃんマーク */
    fun drawFace(canvas: Canvas, cx: Float, cy: Float, size: Float, face: Face) {
        val r = size * 0.40f
        fill.isAntiAlias = true
        fill.style = Paint.Style.FILL
        fill.color = Color.YELLOW
        canvas.drawCircle(cx, cy, r, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = size * 0.04f
        fill.color = DARK
        canvas.drawCircle(cx, cy, r, fill)

        val eyeY = cy - r * 0.28f
        val eyeDx = r * 0.36f
        fill.style = Paint.Style.FILL
        when (face) {
            Face.SMILE, Face.SURPRISED -> {
                canvas.drawCircle(cx - eyeDx, eyeY, r * 0.11f, fill)
                canvas.drawCircle(cx + eyeDx, eyeY, r * 0.11f, fill)
            }
            Face.DEAD -> {
                fill.strokeWidth = size * 0.035f
                val e = r * 0.13f
                for (x in floatArrayOf(cx - eyeDx, cx + eyeDx)) {
                    canvas.drawLine(x - e, eyeY - e, x + e, eyeY + e, fill)
                    canvas.drawLine(x - e, eyeY + e, x + e, eyeY - e, fill)
                }
            }
            Face.COOL -> {
                // サングラス
                canvas.drawRect(cx - r * 0.72f, eyeY - r * 0.05f, cx + r * 0.72f, eyeY + r * 0.02f, fill)
                canvas.drawOval(RectF(cx - r * 0.62f, eyeY - r * 0.12f, cx - r * 0.08f, eyeY + r * 0.22f), fill)
                canvas.drawOval(RectF(cx + r * 0.08f, eyeY - r * 0.12f, cx + r * 0.62f, eyeY + r * 0.22f), fill)
            }
        }

        fill.style = Paint.Style.STROKE
        fill.strokeWidth = size * 0.04f
        val mouth = RectF(cx - r * 0.5f, cy - r * 0.2f, cx + r * 0.5f, cy + r * 0.55f)
        when (face) {
            Face.SMILE, Face.COOL -> canvas.drawArc(mouth, 20f, 140f, false, fill)
            Face.DEAD -> canvas.drawArc(
                RectF(cx - r * 0.45f, cy + r * 0.25f, cx + r * 0.45f, cy + r * 0.9f), 200f, 140f, false, fill,
            )
            Face.SURPRISED -> {
                fill.style = Paint.Style.FILL
                canvas.drawOval(RectF(cx - r * 0.17f, cy + r * 0.2f, cx + r * 0.17f, cy + r * 0.6f), fill)
            }
        }
        fill.style = Paint.Style.FILL
    }

    // 7セグメント: a b c d e f g
    private val DIGIT_SEGMENTS = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, false),     // 0
        booleanArrayOf(false, true, true, false, false, false, false), // 1
        booleanArrayOf(true, true, false, true, true, false, true),    // 2
        booleanArrayOf(true, true, true, true, false, false, true),    // 3
        booleanArrayOf(false, true, true, false, false, true, true),   // 4
        booleanArrayOf(true, false, true, true, false, true, true),    // 5
        booleanArrayOf(true, false, true, true, true, true, true),     // 6
        booleanArrayOf(true, true, true, false, false, false, false),  // 7
        booleanArrayOf(true, true, true, true, true, true, true),      // 8
        booleanArrayOf(true, true, true, true, false, true, true),     // 9
    )
    private val MINUS_SEGMENTS = booleanArrayOf(false, false, false, false, false, false, true)

    /** 黒地に赤の 7 セグメント LED で 3 桁を描く（負数は先頭に -） */
    fun drawLedCounter(canvas: Canvas, rect: RectF, value: Int) {
        fill.isAntiAlias = false
        fill.style = Paint.Style.FILL
        fill.color = DARK
        canvas.drawRect(rect, fill)

        val v = value.coerceIn(-99, 999)
        val text = if (v < 0) "-" + (-v).toString().padStart(2, '0') else v.toString().padStart(3, '0')
        val pad = rect.height() * 0.12f
        val digitW = (rect.width() - pad * 2) / 3f
        for (i in 0 until 3) {
            val ch = text[i]
            val segs = if (ch == '-') MINUS_SEGMENTS else DIGIT_SEGMENTS[ch - '0']
            val l = rect.left + pad + digitW * i
            drawDigit(canvas, RectF(l + digitW * 0.12f, rect.top + pad, l + digitW * 0.88f, rect.bottom - pad), segs)
        }
    }

    private fun drawDigit(canvas: Canvas, r: RectF, segs: BooleanArray) {
        val t = r.width() * 0.18f
        val midY = r.centerY()
        val rects = arrayOf(
            RectF(r.left + t, r.top, r.right - t, r.top + t),                 // a
            RectF(r.right - t, r.top + t, r.right, midY - t / 2),             // b
            RectF(r.right - t, midY + t / 2, r.right, r.bottom - t),          // c
            RectF(r.left + t, r.bottom - t, r.right - t, r.bottom),           // d
            RectF(r.left, midY + t / 2, r.left + t, r.bottom - t),            // e
            RectF(r.left, r.top + t, r.left + t, midY - t / 2),               // f
            RectF(r.left + t, midY - t / 2, r.right - t, midY + t / 2),       // g
        )
        for (i in rects.indices) {
            fill.color = if (segs[i]) Color.RED else Color.rgb(0x40, 0x00, 0x00)
            canvas.drawRect(rects[i], fill)
        }
    }
}
