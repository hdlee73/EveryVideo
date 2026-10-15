package com.everyvideo.player.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 따로 받은 자막 파일(SRT, SMI, VTT, ASS/SSA, TTML)을 읽어 재생기에 붙인다.
 * 한국어 자막에 흔한 CP949(EUC-KR) 글자와 SMI 형식은 UTF-8 SRT 로 바꾸고,
 * 싱크 조절은 시간 값을 직접 옮긴 새 파일을 만들어 적용한다.
 */
object Subtitles {
    data class Sub(val name: String, val format: String, val text: String)

    fun mime(format: String): String = when (format) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun load(context: Context, uri: Uri, name: String): Sub {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("자막 파일을 열 수 없습니다")
        if (bytes.size > 20 * 1024 * 1024) throw IllegalStateException("자막 파일이 너무 큽니다")
        val text = decode(bytes)
        val ext = name.substringAfterLast('.', "").lowercase()
        val head = text.take(2000).lowercase()
        return when {
            ext == "smi" || ext == "sami" || head.contains("<sami") -> Sub(name, "srt", smiToSrt(text))
            ext == "vtt" || head.startsWith("webvtt") -> Sub(name, "vtt", text)
            ext == "ass" || ext == "ssa" || head.contains("[script info]") -> Sub(name, "ass", text)
            ext == "ttml" || ext == "dfxp" || ext == "xml" -> Sub(name, "ttml", text)
            else -> Sub(name, "srt", text)
        }
    }

    /** 시간을 offsetMs 만큼 옮긴 자막 파일을 캐시에 쓰고 주소를 돌려준다. */
    fun write(context: Context, sub: Sub, offsetMs: Long): Uri {
        val dir = File(context.cacheDir, "subs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val ext = when (sub.format) { "vtt" -> "vtt"; "ass" -> "ass"; "ttml" -> "ttml"; else -> "srt" }
        val file = File(dir, "sub_${System.nanoTime()}.$ext")
        file.writeText(if (offsetMs == 0L) sub.text else shift(sub, offsetMs), Charsets.UTF_8)
        return Uri.fromFile(file)
    }

    fun canShift(sub: Sub) = sub.format != "ttml"

    // ---------------------------------------------------------------- 글자 인코딩

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, runCatching { Charset.forName("MS949") }.getOrDefault(Charset.forName("EUC-KR"))) }
    }

    // ---------------------------------------------------------------- 시간 옮기기

    private val SRT_TIME = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{3})""")
    private val ASS_TIME = Regex("""(\d):(\d{2}):(\d{2})\.(\d{2})""")

    private fun shift(sub: Sub, offset: Long): String = sub.text.lineSequence().joinToString("\n") { line ->
        when {
            (sub.format == "srt" || sub.format == "vtt") && line.contains("-->") ->
                SRT_TIME.replace(line) { m ->
                    val (h, mi, s, ms) = m.destructured
                    val t = ((h.toLongOrNull() ?: 0) * 3600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong() + offset).coerceAtLeast(0)
                    val sep = if (sub.format == "vtt") "." else ","
                    "%02d:%02d:%02d%s%03d".format(t / 3600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
                }
            sub.format == "ass" && (line.startsWith("Dialogue:") || line.startsWith("Comment:")) ->
                ASS_TIME.replace(line) { m ->
                    val (h, mi, s, cs) = m.destructured
                    val t = (h.toLong() * 3600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + cs.toLong() * 10 + offset).coerceAtLeast(0)
                    "%d:%02d:%02d.%02d".format(t / 3600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
                }
            else -> line
        }
    }

    // ---------------------------------------------------------------- SMI → SRT

    private val SYNC = Regex("""<sync[^>]*start\s*=\s*["']?(-?\d+)[^>]*>""", RegexOption.IGNORE_CASE)
    private val P_CLASS = Regex("""<p[^>]*class\s*=\s*["']?([\w-]+)[^>]*>""", RegexOption.IGNORE_CASE)

    fun smiToSrt(smi: String): String {
        val syncs = SYNC.findAll(smi).toList()
        if (syncs.isEmpty()) return ""
        // 여러 언어가 섞여 있으면 한국어(KRCC/KOKR) 우선, 없으면 처음 나온 언어
        val classes = P_CLASS.findAll(smi).map { it.groupValues[1].uppercase() }.distinct().toList()
        val lang = classes.firstOrNull { it.startsWith("KR") || it.startsWith("KO") } ?: classes.firstOrNull()
        val entries = mutableListOf<Triple<Long, Long, String>>()
        for ((i, m) in syncs.withIndex()) {
            val start = m.groupValues[1].toLong()
            val endIdx = if (i + 1 < syncs.size) syncs[i + 1].range.first else smi.length
            var chunk = smi.substring(m.range.last + 1, endIdx)
            if (lang != null && classes.size > 1) {
                val parts = chunk.split(Regex("(?=<p)", RegexOption.IGNORE_CASE))
                chunk = parts.firstOrNull { P_CLASS.find(it)?.groupValues?.get(1)?.uppercase() == lang } ?: ""
            }
            val text = chunk
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</?(body|sami)[^>]*>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            val end = if (i + 1 < syncs.size) syncs[i + 1].groupValues[1].toLong() else start + 4000
            if (text.isNotBlank() && end > start) entries += Triple(start.coerceAtLeast(0), end, text)
        }
        fun t(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        return entries.mapIndexed { i, (s, e, text) -> "${i + 1}\n${t(s)} --> ${t(e)}\n$text\n" }.joinToString("\n")
    }
}
