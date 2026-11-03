package com.everyvideo.player.enhance

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * 화질 개선 설정. 재생 화면(실시간)과 저장할 때 같은 값을 쓴다.
 * sharpen, denoise: 0~100 (0 = 끔). upscale: 원본이 화면(또는 저장 해상도)보다 작으면 고화질로 확대.
 */
data class QualitySettings(val sharpen: Int = 0, val denoise: Int = 0, val upscale: Boolean = false) {
    val isOff get() = sharpen <= 0 && denoise <= 0 && !upscale

    fun label(): String = buildList {
        if (denoise > 0) add("노이즈 줄이기 $denoise")
        if (upscale) add("업스케일")
        if (sharpen > 0) add("선명하게 $sharpen")
    }.joinToString(" · ").ifEmpty { "끔" }
}

/**
 * GPU(OpenGL ES 셰이더)로 처리하는 가벼운 화질 개선 효과.
 * 순서: 노이즈 줄이기(원본 해상도) → 업스케일 → 선명하게(최종 해상도).
 */
@UnstableApi
object QualityEffects {

    /** maxW × maxH 안에 들어가도록 확대한다 (축소는 하지 않음). 0 이면 업스케일 안 함. */
    fun build(q: QualitySettings, maxW: Int, maxH: Int): List<Effect> {
        val list = mutableListOf<Effect>()
        if (q.denoise > 0) list += ShaderEffect(Kind.DENOISE, q.denoise / 100f)
        if (q.upscale && maxW > 0 && maxH > 0) list += UpscaleEffect(maxW, maxH)
        if (q.sharpen > 0) list += ShaderEffect(Kind.SHARPEN, q.sharpen / 100f)
        return list
    }

    internal enum class Kind { DENOISE, SHARPEN }

    internal const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTex;
void main() {
  gl_Position = aFramePosition;
  vTex = aFramePosition.xy * 0.5 + 0.5;
}
"""

    /** 가장자리는 지키면서 잔 노이즈만 고르게 하는 3×3 바이래터럴 필터. */
    private const val DENOISE = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uStrength;
varying vec2 vTex;
void main() {
  vec4 c = texture2D(uTexSampler, vTex);
  float sigma = mix(0.03, 0.13, uStrength);
  float inv = 1.0 / (2.0 * sigma * sigma);
  vec2 step = uTexel * mix(1.0, 1.8, uStrength);
  vec3 sum = c.rgb;
  float wsum = 1.0;
  for (int y = -1; y <= 1; y++) {
    for (int x = -1; x <= 1; x++) {
      if (x == 0 && y == 0) continue;
      vec3 s = texture2D(uTexSampler, vTex + vec2(float(x), float(y)) * step).rgb;
      vec3 d = s - c.rgb;
      float w = exp(-dot(d, d) * inv) * ((x != 0 && y != 0) ? 0.55 : 0.85);
      sum += s * w;
      wsum += w;
    }
  }
  gl_FragColor = vec4(sum / wsum, c.a);
}
"""

    /** 대비에 맞춰 세기를 조절하는 선명화 (AMD CAS 방식). 이미 선명한 곳은 덜, 흐린 곳은 더. */
    private const val SHARPEN = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uStrength;
varying vec2 vTex;
void main() {
  vec4 e4 = texture2D(uTexSampler, vTex);
  vec3 e = e4.rgb;
  vec3 a = texture2D(uTexSampler, vTex + vec2(0.0, -uTexel.y)).rgb;
  vec3 b = texture2D(uTexSampler, vTex + vec2(-uTexel.x, 0.0)).rgb;
  vec3 c = texture2D(uTexSampler, vTex + vec2(uTexel.x, 0.0)).rgb;
  vec3 d = texture2D(uTexSampler, vTex + vec2(0.0, uTexel.y)).rgb;
  vec3 mn = min(min(min(a, b), min(c, d)), e);
  vec3 mx = max(max(max(a, b), max(c, d)), e);
  vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
  vec3 w = amp * (-1.0 / mix(8.0, 4.2, uStrength));
  vec3 res = (e + (a + b + c + d) * w) / (1.0 + 4.0 * w);
  gl_FragColor = vec4(clamp(res, 0.0, 1.0), e4.a);
}
"""

    /** Catmull-Rom 바이큐빅 확대 (텍스처 선형 보간을 이용한 9탭). */
    private const val UPSCALE = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uSrcSize;
varying vec2 vTex;
void main() {
  vec2 samplePos = vTex * uSrcSize;
  vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
  vec2 f = samplePos - texPos1;
  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
  vec2 w3 = f * f * (-0.5 + 0.5 * f);
  vec2 w12 = w1 + w2;
  vec2 offset12 = w2 / w12;
  vec2 texPos0 = (texPos1 - 1.0) / uSrcSize;
  vec2 texPos3 = (texPos1 + 2.0) / uSrcSize;
  vec2 texPos12 = (texPos1 + offset12) / uSrcSize;
  vec4 r = vec4(0.0);
  r += texture2D(uTexSampler, vec2(texPos0.x, texPos0.y)) * w0.x * w0.y;
  r += texture2D(uTexSampler, vec2(texPos12.x, texPos0.y)) * w12.x * w0.y;
  r += texture2D(uTexSampler, vec2(texPos3.x, texPos0.y)) * w3.x * w0.y;
  r += texture2D(uTexSampler, vec2(texPos0.x, texPos12.y)) * w0.x * w12.y;
  r += texture2D(uTexSampler, vec2(texPos12.x, texPos12.y)) * w12.x * w12.y;
  r += texture2D(uTexSampler, vec2(texPos3.x, texPos12.y)) * w3.x * w12.y;
  r += texture2D(uTexSampler, vec2(texPos0.x, texPos3.y)) * w0.x * w3.y;
  r += texture2D(uTexSampler, vec2(texPos12.x, texPos3.y)) * w12.x * w3.y;
  r += texture2D(uTexSampler, vec2(texPos3.x, texPos3.y)) * w3.x * w3.y;
  gl_FragColor = clamp(r, 0.0, 1.0);
}
"""

    internal class ShaderEffect(private val kind: Kind, private val strength: Float) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            FilterProgram(useHdr, if (kind == Kind.DENOISE) DENOISE else SHARPEN, strength)
    }

    internal class UpscaleEffect(private val maxW: Int, private val maxH: Int) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = UpscaleProgram(useHdr, maxW, maxH)

        override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = targetSize(inputWidth, inputHeight, maxW, maxH) == null
    }

    /** 확대할 크기 (짝수). 원본이 이미 충분히 크면 null. */
    internal fun targetSize(w: Int, h: Int, maxW: Int, maxH: Int): Size? {
        if (w <= 0 || h <= 0) return null
        val scale = minOf(maxW / w.toFloat(), maxH / h.toFloat())
        if (scale < 1.15f) return null
        val s = minOf(scale, 4f)
        val ow = (w * s).toInt() and 1.inv()
        val oh = (h * s).toInt() and 1.inv()
        return Size(ow, oh)
    }

    private open class SimpleProgram(useHdr: Boolean, fragment: String) : BaseGlShaderProgram(useHdr, 1) {
        protected val program: GlProgram = try {
            GlProgram(VERTEX, fragment)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        protected var inW = 1
        protected var inH = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            inW = inputWidth
            inH = inputHeight
            return Size(inputWidth, inputHeight)
        }

        protected open fun setUniforms() {}

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                setUniforms()
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private class FilterProgram(useHdr: Boolean, fragment: String, private val strength: Float) : SimpleProgram(useHdr, fragment) {
        override fun setUniforms() {
            program.setFloatsUniform("uTexel", floatArrayOf(1f / inW, 1f / inH))
            program.setFloatUniform("uStrength", strength.coerceIn(0f, 1f))
        }
    }

    private class UpscaleProgram(useHdr: Boolean, private val maxW: Int, private val maxH: Int) : SimpleProgram(useHdr, UPSCALE) {
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            super.configure(inputWidth, inputHeight)
            return targetSize(inputWidth, inputHeight, maxW, maxH) ?: Size(inputWidth, inputHeight)
        }

        override fun setUniforms() {
            program.setFloatsUniform("uSrcSize", floatArrayOf(inW.toFloat(), inH.toFloat()))
        }
    }
}
