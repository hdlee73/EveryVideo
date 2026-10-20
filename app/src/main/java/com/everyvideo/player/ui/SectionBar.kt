package com.everyvideo.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 재생 막대 위에 겹쳐 그리는 구간 표시. 터치는 받지 않는다 (아래 DefaultTimeBar 가 받음).
 * inset 은 DefaultTimeBar 의 좌우 여백(스크러버 크기의 절반)과 같아야 한다.
 */
class SectionBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var sections: List<Pair<Long, Long>> = emptyList()
        set(v) { field = v; invalidate() }
    var pendingStart: Long = -1
        set(v) { field = v; invalidate() }
    var abRange: Pair<Long, Long>? = null
        set(v) { field = v; invalidate() }
    var duration: Long = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    val inset = 12 * resources.displayMetrics.density
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFB020 }
    private val ab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE2D7FF9.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB020.toInt(); strokeWidth = 2f * d; style = Paint.Style.STROKE
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB020.toInt(); strokeWidth = 2 * d }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    private fun x(ms: Long): Float {
        val w = width - 2 * inset
        return inset + w * (ms.toFloat() / duration).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        if (duration <= 0) return
        // 가운데 가는 막대를 감싸는 띠로 구간을 주황 테두리 상자로, A-B 는 막대 바로 아래 파란 띠로 그린다
        val cy = height / 2f
        val top = cy - 8 * d
        val bottom = cy + 8 * d
        abRange?.let { (s, e) -> canvas.drawRoundRect(x(s), cy + 4 * d, x(e), cy + 8 * d, 2 * d, 2 * d, ab) }
        for ((s, e) in sections) {
            val r = maxOf(x(e), x(s) + 4 * d)
            canvas.drawRoundRect(x(s), top, r, bottom, 5 * d, 5 * d, fill)
            canvas.drawRoundRect(x(s), top, r, bottom, 5 * d, 5 * d, edge)
        }
        if (pendingStart >= 0) canvas.drawLine(x(pendingStart), 0f, x(pendingStart), height.toFloat(), mark)
    }
}
