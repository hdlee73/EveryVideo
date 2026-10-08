package com.everyvideo.player.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** 썸네일 이미지 만들기: 동영상 한 장면에 제목 글자를 얹어 JPEG 로 저장. */
object Thumbnails {
    const val WIDTH = 1280

    /** 이미지 파일을 긴 변이 maxSide 근처가 되도록 줄여 읽는다. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= maxSide) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= maxSide) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    /** 장면을 가로 WIDTH 로 맞추고, 제목이 있으면 아래쪽에 어두운 띠와 함께 크게 쓴다. */
    fun render(frame: Bitmap, title: String): Bitmap {
        val w = WIDTH
        val h = (frame.height * (w / frame.width.toFloat())).toInt().coerceAtLeast(2)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(frame, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val text = title.trim()
        if (text.isEmpty()) return out

        val pad = w * 0.05f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = h * 0.11f
            setShadowLayer(h * 0.012f, 0f, h * 0.006f, 0xCC000000.toInt())
        }
        val maxWidth = (w - pad * 2).toInt()
        var layout = layoutOf(text, paint, maxWidth)
        while (layout.lineCount > 2 && paint.textSize > h * 0.05f) {
            paint.textSize *= 0.9f
            layout = layoutOf(text, paint, maxWidth)
        }
        val top = h - pad - layout.height
        val shade = Paint().apply {
            shader = LinearGradient(0f, top - pad * 2, 0f, h.toFloat(), 0x00000000, 0xB3000000.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, top - pad * 2, w.toFloat(), h.toFloat(), shade)
        canvas.save()
        canvas.translate(pad, top)
        layout.draw(canvas)
        canvas.restore()
        return out
    }

    private fun layoutOf(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .build()
}
