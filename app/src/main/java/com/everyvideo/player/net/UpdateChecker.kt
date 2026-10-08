package com.everyvideo.player.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 의 최신 릴리스를 확인한다.
 * GET https://api.github.com/repos/hdlee73/EveryVideo/releases/latest
 * tag_name(v0.3.0) 과 지금 버전을 숫자끼리 비교한다.
 */
object UpdateChecker {
    const val REPO = "hdlee73/EveryVideo"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(
        val version: String,
        val pageUrl: String,
        val apkUrl: String?,
        val publishedAt: String,
        val notes: String
    )

    /** 실패하면 null. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "EveryVideo-Android")
            try {
                if (conn.responseCode != 200) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apk = a.optString("browser_download_url"); break
                        }
                    }
                }
                Release(
                    version = json.optString("tag_name").removePrefix("v").removePrefix("V"),
                    pageUrl = json.optString("html_url").ifEmpty { RELEASES_URL },
                    apkUrl = apk,
                    publishedAt = json.optString("published_at").take(10),
                    notes = json.optString("body")
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** "0.10.0" > "0.9.2" 처럼 숫자 단위로 비교. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
