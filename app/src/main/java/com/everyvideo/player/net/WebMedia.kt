package com.everyvideo.player.net

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** 웹 페이지 주소에서 동영상 링크(mp4, m3u8 등)를 찾는다. */
object WebMedia {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val EXT = (RemoteUris.VIDEO_EXTENSIONS + setOf("mp3", "m4a", "flac", "wav", "aac", "opus"))
        .joinToString("|")
    /** 따옴표 안이나 맨 주소로 적힌 미디어 링크 (상대 경로 포함). */
    private val LINK = Regex("""(?:["'(=]|^|\s)((?:https?:)?//[^\s"'<>()]+?\.(?:$EXT)(?:\?[^\s"'<>()]*)?|[^\s"'<>():]+?\.(?:$EXT)(?:\?[^\s"'<>()]*)?)(?=["')\s>]|$)""", RegexOption.IGNORE_CASE)
    private val OG = Regex("""<meta[^>]+(?:property|name)=["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    data class Link(val url: String, val name: String, val streaming: Boolean)

    /** 동영상 파일을 바로 가리키는 주소인지 (웹 페이지가 아닌지). */
    fun isDirectMedia(url: String): Boolean {
        val u = Uri.parse(url)
        if (u.scheme != "http" && u.scheme != "https") return true
        if (DriveLinks.isDriveLink(url)) return true
        val path = u.path ?: return false
        return RemoteUris.isVideoName(path) || path.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "flac", "wav", "aac", "opus")
    }

    fun isStreaming(url: String): Boolean {
        val p = (Uri.parse(url).path ?: url).lowercase()
        return p.endsWith(".m3u8") || p.endsWith(".mpd")
    }

    fun linkOf(url: String): Link {
        val name = Uri.decode(Uri.parse(url).lastPathSegment ?: url)
        return Link(url, name, isStreaming(url))
    }

    /** 블로킹 호출. 찾은 링크를 중복 없이 돌려준다 (최대 60개). */
    fun find(pageUrl: String): List<Link> {
        val req = Request.Builder().url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
            .build()
        val (finalUrl, html) = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("웹 페이지 응답 ${resp.code}")
            val type = resp.header("Content-Type") ?: ""
            if (type.startsWith("video/") || type.startsWith("audio/") || type.contains("mpegurl")) {
                return listOf(linkOf(resp.request.url.toString()))
            }
            resp.request.url.toString() to (resp.body?.string() ?: "")
        }
        val base = runCatching { URI(finalUrl) }.getOrNull()
        val found = LinkedHashSet<String>()
        fun add(raw: String) {
            val cleaned = raw.trim().replace("&amp;", "&").replace("\\/", "/")
            val abs = runCatching {
                when {
                    cleaned.startsWith("//") -> (base?.scheme ?: "https") + ":" + cleaned
                    cleaned.startsWith("http", true) -> cleaned
                    base != null -> base.resolve(cleaned).toString()
                    else -> null
                }
            }.getOrNull() ?: return
            if (abs.startsWith("http")) found += abs
        }
        OG.findAll(html).forEach { add(it.groupValues[1]) }
        LINK.findAll(html.replace("\\/", "/")).forEach { add(it.groupValues[1]) }
        return found.take(60).map(::linkOf)
    }
}
