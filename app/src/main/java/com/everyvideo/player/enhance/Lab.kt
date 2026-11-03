package com.everyvideo.player.enhance

import kotlin.math.cbrt
import kotlin.math.pow

/** sRGB ↔ CIE Lab (D65). AI 컬러화 모델은 밝기(L)만 보고 색(a, b)을 추정한다. */
internal object Lab {
    private val toLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    private const val LUT = 4096
    private val toSrgb = IntArray(LUT + 1) { i ->
        val c = i / LUT.toFloat()
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
    private fun finv(t: Float) = if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f

    /** 상대 휘도 Y (0~1). */
    fun luminance(argb: Int): Float =
        0.212671f * toLinear[(argb shr 16) and 0xFF] + 0.715160f * toLinear[(argb shr 8) and 0xFF] + 0.072169f * toLinear[argb and 0xFF]

    /** L (0~100). */
    fun lightness(argb: Int): Float = 116f * f(luminance(argb)) - 16f

    private fun enc(v: Float): Int {
        val i = (v.coerceIn(0f, 1f) * LUT).toInt()
        return toSrgb[i]
    }

    /** 원래 화소의 밝기는 그대로 두고 색(a, b)만 입힌다. */
    fun withAb(argb: Int, a: Float, b: Float): Int {
        val y = luminance(argb)
        val fy = f(y)
        val x = finv(fy + a / 500f) * 0.95047f
        val z = finv(fy - b / 200f) * 1.08883f
        val r = 3.240479f * x - 1.537150f * y - 0.498535f * z
        val g = -0.969256f * x + 1.875992f * y + 0.041556f * z
        val bl = 0.055648f * x - 0.204043f * y + 1.057311f * z
        return (argb and -0x1000000) or (enc(r) shl 16) or (enc(g) shl 8) or enc(bl)
    }
}
