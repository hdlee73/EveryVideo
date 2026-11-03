package com.everyvideo.player.enhance

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 동영상 저장용 AI 컬러화 효과.
 * 프레임을 256×256 으로 줄여 AI 로 색(a, b)을 추정하고, GPU 에서 원본 해상도의 밝기에 색을 입힌다.
 * 프레임마다 색이 깜빡이지 않도록 앞 프레임의 색과 부드럽게 섞고, 장면이 바뀌면 바로 새 색을 쓴다.
 */
@UnstableApi
class ColorizeEffect(private val colorizer: Colorizer, private val interval: Int = 2) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr, colorizer, interval)

    private class Program(useHdr: Boolean, private val colorizer: Colorizer, private val interval: Int) :
        BaseGlShaderProgram(useHdr, 1) {

        private val n = Colorizer.N
        private val copy = GlProgram(QualityEffects.VERTEX, COPY)
        private val combine = GlProgram(QualityEffects.VERTEX, COMBINE)
        private val smallTex = GlUtil.createTexture(n, n, false)
        private val smallFbo = GlUtil.createFboForTexture(smallTex)
        private val abTex = GlUtil.generateTexture()
        private val readBuf = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        private val abBytes = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        private var prevAb: FloatArray? = null
        private var prevMeanL = -1f
        private var frame = 0
        private var hasAb = false

        init {
            val pos = GlUtil.getNormalizedCoordinateBounds()
            copy.setBufferAttribute("aFramePosition", pos, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            combine.setBufferAttribute("aFramePosition", pos, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                if (!hasAb || frame % interval == 0) updateColors(inputTexId)
                frame++
                combine.use()
                combine.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                combine.setSamplerTexIdUniform("uAb", abTex, 1)
                combine.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        /** 지금 프레임으로 색 텍스처를 새로 만든다. 그리는 대상(FBO)과 화면 영역은 원래대로 돌려 둔다. */
        private fun updateColors(inputTexId: Int) {
            val fbo = IntArray(1)
            val vp = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)

            GlUtil.focusFramebufferUsingCurrentContext(smallFbo, n, n)
            copy.use()
            copy.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            copy.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            readBuf.clear()
            GLES20.glReadPixels(0, 0, n, n, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])

            // glReadPixels 는 아래 줄부터 읽으므로 위아래를 뒤집어 모델에 넣는다.
            val l = FloatArray(n * n)
            var sum = 0f
            for (y in 0 until n) {
                val src = (n - 1 - y) * n * 4
                for (x in 0 until n) {
                    val o = src + x * 4
                    val argb = (0xFF shl 24) or
                        ((readBuf.get(o).toInt() and 0xFF) shl 16) or
                        ((readBuf.get(o + 1).toInt() and 0xFF) shl 8) or
                        (readBuf.get(o + 2).toInt() and 0xFF)
                    val v = Lab.lightness(argb)
                    l[y * n + x] = v
                    sum += v
                }
            }
            val meanL = sum / (n * n)
            var ab = colorizer.predict(l, n)
            val prev = prevAb
            // 장면이 크게 바뀌지 않았으면 앞 색과 섞어 깜빡임을 줄인다.
            if (prev != null && prevMeanL >= 0 && abs(meanL - prevMeanL) < 6f) {
                for (i in ab.indices) ab[i] = prev[i] * 0.45f + ab[i] * 0.55f
            }
            prevAb = ab
            prevMeanL = meanL

            abBytes.clear()
            val plane = n * n
            for (y in 0 until n) {
                val src = (n - 1 - y) * n
                for (x in 0 until n) {
                    val a = ab[src + x]
                    val b = ab[plane + src + x]
                    abBytes.put((a + 128f).toInt().coerceIn(0, 255).toByte())
                    abBytes.put((b + 128f).toInt().coerceIn(0, 255).toByte())
                    abBytes.put(0.toByte())
                    abBytes.put((-1).toByte())
                }
            }
            abBytes.position(0)
            GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, abTex, GLES20.GL_LINEAR)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, abBytes)
            GlUtil.checkGlError()
            hasAb = true
        }

        override fun release() {
            super.release()
            try {
                copy.delete()
                combine.delete()
                GlUtil.deleteFbo(smallFbo)
                GlUtil.deleteTexture(smallTex)
                GlUtil.deleteTexture(abTex)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        private const val COPY = """
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTex;
void main() { gl_FragColor = texture2D(uTexSampler, vTex); }
"""

        /** 원래 화소의 휘도(Y)는 그대로, a·b 는 AI 가 추정한 값으로 Lab → sRGB 변환. */
        private const val COMBINE = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uAb;
varying vec2 vTex;
vec3 toLin(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toSrgb(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
float f(float t) { return t > 0.008856 ? pow(t, 1.0 / 3.0) : 7.787 * t + 16.0 / 116.0; }
float finv(float t) { return t > 0.206893 ? t * t * t : (t - 16.0 / 116.0) / 7.787; }
void main() {
  vec4 c = texture2D(uTexSampler, vTex);
  vec3 lin = toLin(c.rgb);
  float y = dot(lin, vec3(0.212671, 0.715160, 0.072169));
  float fy = f(y);
  vec2 ab = texture2D(uAb, vTex).rg * 255.0 - 128.0;
  vec3 xyz = vec3(finv(fy + ab.x / 500.0) * 0.95047, y, finv(fy - ab.y / 200.0) * 1.08883);
  vec3 rgb = mat3(3.240479, -0.969256, 0.055648, -1.537150, 1.875992, -0.204043, -0.498535, 0.041556, 1.057311) * xyz;
  gl_FragColor = vec4(toSrgb(rgb), c.a);
}
"""
    }
}
