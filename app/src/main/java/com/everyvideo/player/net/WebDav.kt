package com.everyvideo.player.net

import android.net.Uri
import android.util.Base64
import android.util.Xml
import com.everyvideo.player.data.RemoteServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV (NAS, Nextcloud 등) 폴더 목록과 재생 주소.
 * 재생 주소는 dav:// (http) 또는 davs:// (https) 스킴에 계정을 담아 두고,
 * 실제로 읽을 때 http(s) 주소 + Authorization 헤더로 바꾼다.
 */
object WebDav {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun isDav(uri: Uri) = uri.scheme == "dav" || uri.scheme == "davs"

    /** dav://user:pw@host/a → http://host/a (계정 정보는 뺀다). */
    fun httpUri(uri: Uri): Uri {
        val scheme = if (uri.scheme == "davs") "https" else "http"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return Uri.parse("$scheme://${uri.host}$port${uri.encodedPath ?: "/"}${uri.encodedQuery?.let { "?$it" } ?: ""}")
    }

    fun authHeader(uri: Uri): String? {
        val c = RemoteUris.credentials(uri)
        if (c.user.isBlank()) return null
        val raw = "${c.user}:${c.password}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /** 서버에 적은 주소(https://nas:5006/dav 또는 nas.local)를 기준 주소로 바꾼다. */
    fun baseOf(server: RemoteServer): Uri {
        val h = server.host.trim()
        return Uri.parse(if (h.contains("://")) h else "http://$h")
    }

    private const val BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

    /** 블로킹 호출. path 는 서버 기준 "/a/b". */
    fun list(server: RemoteServer, path: String): List<RemoteEntry> {
        val target = RemoteUris.build(server, path)
        val url = httpUri(target).toString().trimEnd('/') + "/"
        val req = Request.Builder()
            .url(url)
            .method("PROPFIND", BODY.toRequestBody())
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .apply { authHeader(target)?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("로그인 실패 (401). 사용자와 비밀번호를 확인해 주세요.")
            if (!resp.isSuccessful) throw IOException("WebDAV 서버 응답 ${resp.code} ${resp.message}")
            val requested = Uri.parse(url).path?.trimEnd('/') ?: ""
            return parse(resp.body?.byteStream() ?: throw IOException("빈 응답"))
                .filter { Uri.decode(Uri.parse(it.first).path ?: it.first).trimEnd('/') != Uri.decode(requested) }
                .mapNotNull { (href, dir, size) ->
                    val name = Uri.decode((Uri.parse(href).path ?: href).trimEnd('/').substringAfterLast('/'))
                    if (name.isEmpty()) null else RemoteEntry(name, dir, size, path.trimEnd('/') + "/" + name)
                }
        }
    }

    private fun parse(input: java.io.InputStream): List<Triple<String, Boolean, Long>> {
        val out = mutableListOf<Triple<String, Boolean, Long>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)
        var href: String? = null
        var dir = false
        var size = 0L
        var text = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = null; dir = false; size = 0L }
                        "collection" -> dir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> if (href == null) href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0L
                    "response" -> href?.let { out += Triple(it, dir, size) }
                }
            }
        }
        return out
    }
}
