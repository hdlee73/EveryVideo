package com.everyvideo.player.enhance

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

private fun sessionOptions() = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2)))
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    // int8 로 줄여 둔 가중치를 세션을 만들 때 한 번만 풀어 둔다 (매 프레임 풀지 않음).
    addConfigEntry("session.disable_quant_qdq", "1")
}

/**
 * 흑백 → 컬러 (Zhang et al. SIGGRAPH 2017 자동 컬러화 모델).
 * 256×256 밝기(L)로 색(a, b)을 추정하고, 원본 해상도의 밝기에 그 색을 입힌다.
 */
class Colorizer(model: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(model.path, sessionOptions())

    /** l: N×N 의 L(0~100), 위에서 아래 순서. 반환: a 평면 N×N 다음 b 평면 N×N. */
    fun predict(l: FloatArray, n: Int): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(l), longArrayOf(1, 1, n.toLong(), n.toLong())).use { input ->
            session.run(mapOf("l" to input)).use { result ->
                val fb = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    fun colorize(src: Bitmap, amount: Float = 1f): Bitmap {
        val small = Bitmap.createScaledBitmap(src, N, N, true)
        val sp = IntArray(N * N)
        small.getPixels(sp, 0, N, 0, 0, N, N)
        if (small !== src) small.recycle()
        val ab = predict(FloatArray(N * N) { Lab.lightness(sp[it]) }, N)
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val fy = ((y + 0.5f) * N / h - 0.5f).coerceIn(0f, N - 1f)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, N - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * N / w - 0.5f).coerceIn(0f, N - 1f)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, N - 1)
                val tx = fx - x0
                val i00 = y0 * N + x0
                val i01 = y0 * N + x1
                val i10 = y1 * N + x0
                val i11 = y1 * N + x1
                val w00 = (1 - tx) * (1 - ty)
                val w01 = tx * (1 - ty)
                val w10 = (1 - tx) * ty
                val w11 = tx * ty
                val o = N * N
                val a = ab[i00] * w00 + ab[i01] * w01 + ab[i10] * w10 + ab[i11] * w11
                val b = ab[o + i00] * w00 + ab[o + i01] * w01 + ab[o + i10] * w10 + ab[o + i11] * w11
                row[x] = Lab.withAb(row[x], a * amount, b * amount)
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val N = 256
    }
}

/** Real-ESRGAN (general x4v3, 경량 모델) 4배 확대. 메모리를 아끼려고 조각(타일)으로 나눠 처리한다. */
class Upscaler(model: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(model.path, sessionOptions())

    fun upscale(src: Bitmap, progress: (Int) -> Unit, isCancelled: () -> Boolean): Bitmap? {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)
        val tilesX = (w + TILE - 1) / TILE
        val tilesY = (h + TILE - 1) / TILE
        var done = 0
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            if (isCancelled()) { out.recycle(); return null }
            val ix = tx * TILE
            val iy = ty * TILE
            val iw = minOf(TILE, w - ix)
            val ih = minOf(TILE, h - iy)
            val x0 = maxOf(0, ix - PAD)
            val y0 = maxOf(0, iy - PAD)
            val x1 = minOf(w, ix + iw + PAD)
            val y1 = minOf(h, iy + ih + PAD)
            val pw = x1 - x0
            val ph = y1 - y0
            val px = IntArray(pw * ph)
            src.getPixels(px, 0, pw, x0, y0, pw, ph)
            val plane = pw * ph
            val chw = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val c = px[i]
                chw[i] = ((c shr 16) and 0xFF) / 255f
                chw[plane + i] = ((c shr 8) and 0xFF) / 255f
                chw[2 * plane + i] = (c and 0xFF) / 255f
            }
            val res = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, ph.toLong(), pw.toLong())).use { input ->
                session.run(mapOf("rgb" to input)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
            val ow = pw * SCALE
            val op = ow * ph * SCALE
            val cw = iw * SCALE
            val ch = ih * SCALE
            val offX = (ix - x0) * SCALE
            val offY = (iy - y0) * SCALE
            val tile = IntArray(cw * ch)
            for (yy in 0 until ch) for (xx in 0 until cw) {
                val si = (offY + yy) * ow + offX + xx
                val r = (res[si] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (res[op + si] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (res[2 * op + si] * 255f + 0.5f).toInt().coerceIn(0, 255)
                tile[yy * cw + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(tile, 0, cw, ix * SCALE, iy * SCALE, cw, ch)
            done++
            progress(done * 100 / (tilesX * tilesY))
        }
        return out
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val SCALE = 4
        private const val TILE = 160
        private const val PAD = 10

        /** 이보다 큰 원본은 이미 충분히 선명해 AI 업스케일을 하지 않는다 (긴 변 기준). */
        const val MAX_INPUT = 1280
    }
}
