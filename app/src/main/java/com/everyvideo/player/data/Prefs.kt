package com.everyvideo.player.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** 캡쳐/동영상 저장 폴더 등 간단한 설정. 폴더는 SAF 트리 주소, 비어 있으면 기본 폴더. */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var imageFolder: Uri?
        get() = sp.getString("imageFolder", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("imageFolder", v?.toString()).apply()

    var videoFolder: Uri?
        get() = sp.getString("videoFolder", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("videoFolder", v?.toString()).apply()

    /** 재생 화면 밝기 (-50~100, 0 미만은 화면 위에 어둡게 덧씌움). null 이면 시스템 밝기. */
    var brightnessLevel: Int?
        get() = if (sp.contains("brightnessLevel")) sp.getInt("brightnessLevel", 50) else null
        set(v) = sp.edit().apply { if (v == null) remove("brightnessLevel") else putInt("brightnessLevel", v) }.apply()

    /** 캡쳐할 때 파일 이름과 위치를 매번 물을지. */
    var askOnCapture: Boolean
        get() = sp.getBoolean("askOnCapture", true)
        set(v) = sp.edit().putBoolean("askOnCapture", v).apply()

    /** 화면 대비, 채도 (-100~100, 0 이 원본). */
    var contrast: Int
        get() = sp.getInt("contrast", 0)
        set(v) = sp.edit().putInt("contrast", v).apply()

    var saturation: Int
        get() = sp.getInt("saturation", 0)
        set(v) = sp.edit().putInt("saturation", v).apply()

    /** 큰 화면 가로 재생 화면에서 오른쪽 도구 패널을 접어 둘지. */
    var hideToolPanel: Boolean
        get() = sp.getBoolean("hideToolPanel", false)
        set(v) = sp.edit().putBoolean("hideToolPanel", v).apply()

    /** 다시 열 때 마지막으로 보던 곳부터 재생할지. */
    var autoResume: Boolean
        get() = sp.getBoolean("autoResume", true)
        set(v) = sp.edit().putBoolean("autoResume", v).apply()

    /** 첫 화면(홈)에 최근 재생 목록을 보일지. 기본은 숨김. */
    var showRecentOnHome: Boolean
        get() = sp.getBoolean("showRecentOnHome", false)
        set(v) = sp.edit().putBoolean("showRecentOnHome", v).apply()

    /** Google Drive 에서 고를 때 처음 열 위치 (내 드라이브). 한 번 고르면 기억한다. */
    var driveStartUri: Uri?
        get() = sp.getString("driveStartUri", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("driveStartUri", v?.toString()).apply()

    fun folderLabel(folder: Uri?, defaultLabel: String): String {
        folder ?: return defaultLabel
        val doc = runCatching { DocumentFile.fromTreeUri(app, folder) }.getOrNull()
        val name = doc?.name ?: return defaultLabel
        val id = folder.lastPathSegment ?: return name
        // "primary:Movies/Clips" → "내장 메모리/Movies/Clips"
        val path = id.substringAfter(':', name)
        val volume = id.substringBefore(':', "")
        val prefix = if (volume == "primary") "내장 메모리" else volume
        return if (path.isEmpty()) prefix else "$prefix/$path"
    }
}
