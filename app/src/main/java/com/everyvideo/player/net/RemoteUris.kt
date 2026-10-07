package com.everyvideo.player.net

import android.net.Uri
import com.everyvideo.player.data.RemoteServer

data class Credentials(val user: String, val password: String, val domain: String)

object RemoteUris {
    val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "asf", "flv", "f4v", "3gp", "3g2",
        "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "ogv", "ogg", "divx", "xvid", "rm", "rmvb",
        "m3u8", "mpd", "mxf", "dv", "tp", "trp", "k3g", "skm"
    )

    fun isVideoName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /** 서버 설정으로 smb:// 또는 ftp:// 주소를 만든다. path 는 공유 폴더 기준 상대 경로. */
    fun build(server: RemoteServer, path: String): Uri {
        val auth = buildString {
            if (server.user.isNotBlank()) {
                append(Uri.encode(server.user))
                if (server.password.isNotEmpty()) append(':').append(Uri.encode(server.password))
                append('@')
            }
            append(server.host)
            if (server.port > 0) append(':').append(server.port)
        }
        val b = Uri.Builder().scheme(server.type).encodedAuthority(auth)
        if (server.type == "smb" && server.share.isNotBlank()) b.appendPath(server.share)
        path.split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
        if (server.type == "smb" && server.domain.isNotBlank()) b.appendQueryParameter("domain", server.domain)
        return b.build()
    }

    fun credentials(uri: Uri): Credentials {
        val info = uri.encodedUserInfo
        var user = ""
        var pass = ""
        if (!info.isNullOrEmpty()) {
            val i = info.indexOf(':')
            if (i >= 0) {
                user = Uri.decode(info.substring(0, i)); pass = Uri.decode(info.substring(i + 1))
            } else user = Uri.decode(info)
        }
        return Credentials(user, pass, uri.getQueryParameter("domain") ?: "")
    }

    /** 즐겨찾기/최근 기록 키: 비밀번호 등 사용자 정보와 쿼리 중 도메인을 뺀 주소. */
    fun keyOf(uri: Uri): String {
        if (uri.scheme == "smb" || uri.scheme == "ftp") {
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return "${uri.scheme}://${uri.host}$port${uri.encodedPath ?: ""}"
        }
        return uri.toString()
    }

    fun displayName(uri: Uri): String {
        val last = uri.lastPathSegment ?: return uri.toString()
        return last.substringAfterLast('/')
    }
}

object DriveLinks {
    private val patterns = listOf(
        Regex("drive\\.google\\.com/file/d/([\\w-]{10,})"),
        Regex("[?&]id=([\\w-]{10,})"),
        Regex("docs\\.google\\.com/[^/]+/d/([\\w-]{10,})")
    )

    fun isDriveLink(text: String) = text.contains("drive.google.com") || text.contains("docs.google.com") ||
        text.contains("drive.usercontent.google.com")

    fun fileId(link: String): String? = patterns.firstNotNullOfOrNull { it.find(link)?.groupValues?.get(1) }

    /** '링크가 있는 모든 사용자' 로 공유된 파일을 바로 스트리밍할 수 있는 주소. */
    fun streamUrl(link: String): String? = fileId(link)?.let {
        "https://drive.usercontent.google.com/download?id=$it&export=download&confirm=t"
    }
}
