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
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
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

    /**
     * 장면 모음: 영상 전체에서 고르게 cols×rows 장면을 뽑아 바둑판으로 놓는다.
     * 맨 위에 제목, 각 장면 오른쪽 아래에 시각을 쓴다. 백그라운드 스레드에서 부른다.
     */
    fun renderSheet(
        context: Context, uri: Uri, cols: Int, rows: Int, title: String,
        isCancelled: () -> Boolean = { false }, onProgress: (Int) -> Unit = {}
    ): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            when (uri.scheme) {
                "content", "file", "android.resource" -> r.setDataSource(context, uri)
                "http", "https" -> r.setDataSource(uri.toString(), HashMap())
                else -> throw IllegalArgumentException("FTP·SMB·WebDAV 서버의 동영상은 장면 모음을 만들 수 없습니다. 기기에 저장된 동영상에서 만들어 주세요.")
            }
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration <= 0) throw IllegalStateException("동영상 길이를 알 수 없습니다")
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (sw, sh) = if (rot == 90 || rot == 270) vh to vw else vw to vh

            val tileW = if (cols >= 5) 320 else 400
            val tileH = (tileW * sh / sw.toFloat()).toInt().coerceIn(tileW / 3, tileW * 2)
            val gap = 10
            val margin = 28
            val header = if (title.isBlank()) 0 else 96
            val w = margin * 2 + cols * tileW + (cols - 1) * gap
            val h = margin * 2 + header + rows * tileH + (rows - 1) * gap
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(0xFF111318.toInt())

            if (header > 0) {
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = 40f
                }
                val line = android.text.TextUtils.ellipsize(title.trim(), tp, (w - margin * 2).toFloat(), android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(line, margin.toFloat(), margin + 44f, tp)
                val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA3B2.toInt(); textSize = 22f }
                canvas.drawText("길이 ${time(duration)} · ${sw}×${sh} · 장면 ${cols * rows}개", margin.toFloat(), margin + 80f, sub)
            }

            val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
            val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f; typeface = Typeface.MONOSPACE }
            val n = cols * rows
            for (i in 0 until n) {
                if (isCancelled()) throw java.util.concurrent.CancellationException()
                val at = duration * (2 * i + 1) / (2 * n)
                val frame = (if (android.os.Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tileW, tileH)
                else r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                val x = margin + (i % cols) * (tileW + gap)
                val y = margin + header + (i / cols) * (tileH + gap)
                val dst = Rect(x, y, x + tileW, y + tileH)
                if (frame != null) {
                    canvas.drawBitmap(frame, null, dst, tilePaint)
                    frame.recycle()
                } else {
                    canvas.drawRect(dst, Paint().apply { color = 0xFF22252C.toInt() })
                }
                val label = time(at)
                val tw = badgeText.measureText(label)
                val bx = dst.right - tw - 16f
                val by = dst.bottom - 34f
                canvas.drawRoundRect(RectF(bx, by, dst.right - 6f, dst.bottom - 6f), 8f, 8f, badge)
                canvas.drawText(label, bx + 5f, dst.bottom - 13f, badgeText)
                onProgress((i + 1) * 100 / n)
            }
            return out
        } finally {
            runCatching { r.release() }
        }
    }

    private fun time(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
    }
}
