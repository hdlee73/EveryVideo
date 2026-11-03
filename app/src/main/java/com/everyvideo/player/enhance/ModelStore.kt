package com.everyvideo.player.enhance

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * AI 모델 파일. 앱 용량을 늘리지 않으려고 처음 쓸 때 한 번만 내려받아 앱 내부 저장소에 둔다.
 * 파일은 EveryVideo 저장소의 models/ 폴더에 있고, SHA-256 으로 내려받은 파일을 확인한다.
 */
object ModelStore {

    enum class Model(val fileName: String, val sha256: String, val sizeMb: Int, val title: String) {
        COLORIZE("colorize.onnx", "7322dac83f7c8e1a4a38d684b22bdf89cf23c76cbf79b71eaa773533ec96c545", 33, "AI 컬러화"),
        UPSCALE("upscale_x4.onnx", "8272c563563a7dd005b47a94389ff2ab0dd89ffc96a50b0063f45f11ef040b7c", 5, "AI 업스케일")
    }

    private val BASES = listOf(
        "https://raw.githubusercontent.com/hdlee73/everyvideo/main/models/",
        "https://raw.githubusercontent.com/hdlee73/everyvideo/claude/project-thread-qe24r9/models/"
    )

    fun file(context: Context, m: Model) = File(File(context.filesDir, "models"), m.fileName)

    fun isReady(context: Context, m: Model) = file(context, m).length() > 0

    fun delete(context: Context) {
        File(context.filesDir, "models").deleteRecursively()
    }

    /** 내려받은 모델 전체 크기 (바이트). */
    fun downloadedBytes(context: Context): Long =
        File(context.filesDir, "models").listFiles()?.sumOf { it.length() } ?: 0L

    /** 블로킹. 백그라운드 스레드에서 부른다. progress 는 0~100. */
    fun download(context: Context, m: Model, progress: (Int) -> Unit, isCancelled: () -> Boolean): File {
        val target = file(context, m)
        if (target.length() > 0) return target
        target.parentFile?.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        var last: Exception? = null
        for (base in BASES) {
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                client.newCall(Request.Builder().url(base + m.fileName).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("빈 응답")
                    val total = body.contentLength().takeIf { it > 0 } ?: (m.sizeMb * 1_000_000L)
                    val md = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                if (isCancelled()) throw InterruptedException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                done += n
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) { lastPct = pct; progress(pct) }
                            }
                        }
                    }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    if (hex != m.sha256) throw IOException("파일 확인(체크섬)에 실패했습니다")
                }
                if (!tmp.renameTo(target)) throw IOException("파일을 저장하지 못했습니다")
                return target
            } catch (e: InterruptedException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                last = e
            }
        }
        throw IOException("${m.title} 모델을 내려받지 못했습니다: ${last?.message}", last)
    }
}
