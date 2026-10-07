package com.everyvideo.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import com.everyvideo.player.net.RemoteUris

object Util {
    fun formatTime(ms: Long): String {
        if (ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** "1:02:03", "02:03", "123", "12.5" → 밀리초. 해석할 수 없으면 null. */
    fun parseTime(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (p in parts) {
            val v = p.trim().toDoubleOrNull() ?: return null
            seconds = seconds * 60 + v
        }
        return (seconds * 1000).toLong()
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val name = it.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        if (uri.host?.contains("google") == true && uri.getQueryParameter("id") != null) return "Google Drive 동영상"
        return Uri.decode(RemoteUris.displayName(uri))
    }

    fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun playIntent(context: Context, uris: List<Uri>, index: Int = 0, startMs: Long = -1L): Intent =
        Intent(context, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(uris.map { it.toString() }))
            putExtra(PlayerActivity.EXTRA_INDEX, index)
            putExtra(PlayerActivity.EXTRA_START_MS, startMs)
        }
}
