package com.everyvideo.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.everyvideo.player.R

/**
 * 재생 막대 아래에 까는 필름: 동영상 전체를 같은 간격으로 나눈 장면 그림을 이어 그린다.
 * 터치는 받지 않는다 (위의 DefaultTimeBar 가 받음). 좌우 여백은 SectionBar.inset 과 같다.
 */
class FilmStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val inset = 12 * d
    private val radius = 8 * d
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.film_bg) }
    private val line = Paint().apply { color = 0x55FFFFFF; strokeWidth = d }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val box = RectF()
    private val src = Rect()
    private val dst = RectF()

    /** 칸 수만큼의 장면 그림. 아직 못 뽑은 칸은 null. */
    var frames: Array<Bitmap?> = emptyArray()
        set(v) { field = v; invalidate() }

    fun setFrame(index: Int, bmp: Bitmap) {
        if (index in frames.indices) { frames[index] = bmp; invalidate() }
    }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    override fun onDraw(canvas: Canvas) {
        box.set(inset, 0f, width - inset, height.toFloat())
        if (box.width() <= 0) return
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(box, bg)
        val n = frames.size
        if (n > 0) {
            val cell = box.width() / n
            for (i in 0 until n) {
                val left = box.left + i * cell
                frames[i]?.let { b ->
                    // 칸 비율에 맞게 가운데를 잘라 채운다
                    val want = cell / box.height()
                    val have = b.width.toFloat() / b.height
                    if (have > want) {
                        val w = (b.height * want).toInt()
                        src.set((b.width - w) / 2, 0, (b.width + w) / 2, b.height)
                    } else {
                        val h = (b.width / want).toInt()
                        src.set(0, (b.height - h) / 2, b.width, (b.height + h) / 2)
                    }
                    dst.set(left, box.top, left + cell, box.bottom)
                    canvas.drawBitmap(b, src, dst, bmpPaint)
                }
                if (i > 0) canvas.drawLine(left, box.top, left, box.bottom, line)
            }
        }
        canvas.restore()
    }
}
