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

    /** 캡쳐할 때 파일 이름과 위치를 매번 물을지. */
    var askOnCapture: Boolean
        get() = sp.getBoolean("askOnCapture", true)
        set(v) = sp.edit().putBoolean("askOnCapture", v).apply()

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
