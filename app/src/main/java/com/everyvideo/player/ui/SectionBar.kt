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
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFB300.toInt() }
    private val ab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5539C6FF }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB300.toInt(); strokeWidth = 2 * d }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    private fun x(ms: Long): Float {
        val w = width - 2 * inset
        return inset + w * (ms.toFloat() / duration).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        if (duration <= 0) return
        val cy = height / 2f
        abRange?.let { (s, e) -> canvas.drawRect(x(s), cy - 9 * d, x(e), cy + 9 * d, ab) }
        for ((s, e) in sections) {
            canvas.drawRoundRect(x(s), cy - 6 * d, x(e), cy + 6 * d, 3 * d, 3 * d, fill)
            canvas.drawLine(x(s), cy - 10 * d, x(s), cy + 10 * d, edge)
            canvas.drawLine(x(e), cy - 10 * d, x(e), cy + 10 * d, edge)
        }
        if (pendingStart >= 0) canvas.drawLine(x(pendingStart), cy - 12 * d, x(pendingStart), cy + 12 * d, edge)
    }
}
