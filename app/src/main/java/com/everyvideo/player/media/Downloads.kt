package com.everyvideo.player.media

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.URLUtil

/** 안드로이드 다운로드 관리자로 파일을 내려받는다. 진행률은 알림창에 보인다. */
object Downloads {
    /** 내려받기를 시작하고 저장 위치 설명을 돌려준다. */
    fun enqueue(context: Context, url: String, suggestedName: String? = null): String {
        val name = (suggestedName?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(url, null, null))
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("EveryVideo 내려받기")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .addRequestHeader("User-Agent", "EveryVideo/1.0 (Android)")
        val where = if (Build.VERSION.SDK_INT >= 29) {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "EveryVideo/$name")
            "다운로드/EveryVideo/$name"
        } else {
            req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, name)
            "Android/data/${context.packageName}/files/Movies/$name"
        }
        val dm = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("다운로드 관리자를 사용할 수 없습니다 (비활성화됨)")
        dm.enqueue(req)
        return where
    }
}
