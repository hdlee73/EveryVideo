package com.everyvideo.player.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * 동영상 구간을 움직이는 GIF 로 만든다. 백그라운드 스레드에서 호출한다.
 * 장면은 MediaMetadataRetriever 로 뽑고, 색은 252색 고정 팔레트 + 디더링으로 줄인다.
 */
class GifMaker(private val context: Context) {

    data class Quality(val label: String, val width: Int, val fps: Int)

    companion object {
        val QUALITIES = listOf(
            Quality("작게 · 가로 320 · 초당 10장", 320, 10),
            Quality("보통 · 가로 480 · 초당 12장", 480, 12),
            Quality("크게 · 가로 640 · 초당 15장", 640, 15)
        )
        const val MAX_SECONDS = 30
    }

    /** 만들어진 GIF 임시 파일을 돌려준다. 취소되면 CancellationException. */
    fun make(uri: Uri, startMs: Long, endMs: Long, q: Quality, isCancelled: () -> Boolean, onProgress: (Int) -> Unit): File {
        val r = MediaMetadataRetriever()
        val file = File(context.cacheDir, "gif_${System.nanoTime()}.gif")
        try {
            when (uri.scheme) {
                "content", "file", "android.resource" -> r.setDataSource(context, uri)
                "http", "https" -> r.setDataSource(uri.toString(), HashMap())
                else -> throw IllegalArgumentException("FTP·SMB 등 서버의 동영상은 GIF로 만들 수 없습니다. 먼저 구간 저장으로 내려받아 주세요.")
            }
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (vw <= 0 || vh <= 0) throw IllegalStateException("영상 크기를 알 수 없습니다")
            val (srcW, srcH) = if (rot == 90 || rot == 270) vh to vw else vw to vh
            val outW = minOf(q.width, srcW)
            val outH = (outW * srcH / srcW.toFloat()).toInt().coerceAtLeast(2)
            val step = 1000L / q.fps
            val count = ((endMs - startMs) / step).toInt().coerceAtLeast(1)
            val pixels = IntArray(outW * outH)
            BufferedOutputStream(file.outputStream()).use { out ->
                val enc = GifEncoder(out, outW, outH, (100 / q.fps).coerceAtLeast(2))
                for (i in 0 until count) {
                    if (isCancelled()) throw CancellationException()
                    val us = (startMs + i * step) * 1000
                    val frame = (if (Build.VERSION.SDK_INT >= 27) {
                        r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, outW, outH)
                    } else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)) ?: continue
                    val scaled = if (frame.width == outW && frame.height == outH) frame
                    else Bitmap.createScaledBitmap(frame, outW, outH, true).also { frame.recycle() }
                    scaled.getPixels(pixels, 0, outW, 0, 0, outW, outH)
                    scaled.recycle()
                    enc.addFrame(pixels)
                    onProgress((i + 1) * 100 / count)
                }
                enc.finish()
            }
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            r.release()
        }
    }
}

/** 최소한의 GIF89a 인코더 (전역 팔레트 하나, 무한 반복). */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int, private val delayCs: Int) {
    private val indices = ByteArray(width * height)

    init {
        out.write("GIF89a".toByteArray())
        short(width); short(height)
        out.write(0xF7) // 전역 색상표 있음, 256색
        out.write(0); out.write(0)
        for (i in 0 until 256) {
            if (i < 252) {
                val r = i / 42
                val g = (i / 6) % 7
                val b = i % 6
                out.write(r * 255 / 5); out.write(g * 255 / 6); out.write(b * 255 / 5)
            } else {
                out.write(0); out.write(0); out.write(0)
            }
        }
        // NETSCAPE2.0 무한 반복
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray())
        out.write(3); out.write(1); short(0); out.write(0)
    }

    private fun short(v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    fun addFrame(argb: IntArray) {
        // 4x4 Bayer 디더링으로 6x7x6 팔레트에 맞춘다
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val d = BAYER[(y and 3) * 4 + (x and 3)] // -0.5 .. 0.5
                val r = quant((p shr 16) and 0xFF, 5, d)
                val g = quant((p shr 8) and 0xFF, 6, d)
                val b = quant(p and 0xFF, 5, d)
                indices[y * width + x] = (r * 42 + g * 6 + b).toByte()
            }
        }
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x04) // 이전 장면 위에 그대로 덮기
        short(delayCs)
        out.write(0); out.write(0)
        out.write(0x2C)
        short(0); short(0); short(width); short(height)
        out.write(0)
        lzw()
    }

    private fun quant(v: Int, levels: Int, d: Float): Int {
        val f = v / 255f * levels + d
        return (f + 0.5f).toInt().coerceIn(0, levels)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    // ---- LZW (최소 코드 크기 8)
    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    private fun emit(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += size
        while (bitCount >= 8) {
            block[blockLen++] = (bitBuf and 0xFF).toByte()
            if (blockLen == 255) flushBlock()
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun flushBlock() {
        if (blockLen == 0) return
        out.write(blockLen)
        out.write(block, 0, blockLen)
        blockLen = 0
    }

    private fun lzw() {
        val minCode = 8
        val clear = 1 shl minCode
        val eoi = clear + 1
        out.write(minCode)
        bitBuf = 0; bitCount = 0; blockLen = 0
        var codeSize = minCode + 1
        var next = eoi + 1
        val table = HashMap<Int, Int>(8192)
        emit(clear, codeSize)
        var cur = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (cur shl 8) or k
            val found = table[key]
            if (found != null) {
                cur = found
                continue
            }
            emit(cur, codeSize)
            if (next == 4096) {
                emit(clear, codeSize)
                next = eoi + 1
                codeSize = minCode + 1
                table.clear()
            } else {
                if (next >= (1 shl codeSize)) codeSize++
                table[key] = next++
            }
            cur = k
        }
        emit(cur, codeSize)
        emit(eoi, codeSize)
        if (bitCount > 0) {
            block[blockLen++] = (bitBuf and 0xFF).toByte()
            if (blockLen == 255) flushBlock()
        }
        flushBlock()
        out.write(0)
    }

    companion object {
        private val BAYER = floatArrayOf(0f, 8f, 2f, 10f, 12f, 4f, 14f, 6f, 3f, 11f, 1f, 9f, 15f, 7f, 13f, 5f).map { it / 16f - 0.47f }.toFloatArray()
    }
}
