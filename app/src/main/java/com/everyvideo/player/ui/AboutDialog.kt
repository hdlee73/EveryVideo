package com.everyvideo.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.everyvideo.player.BuildConfig
import com.everyvideo.player.R
import com.everyvideo.player.net.UpdateChecker
import kotlinx.coroutines.launch

/** 앱 정보(이름, 버전, 업데이트 날짜, 만든 사람)와 새 버전 확인. */
object AboutDialog {
    const val AUTHOR = "이현덕"
    const val EMAIL = "hdlee73@gmail.com"

    private const val PREFS = "settings"
    private const val KEY_LAST_CHECK = "updateLastCheck"
    private const val KEY_SKIP = "updateSkipVersion"
    const val KEY_NOTIFY = "updateNotify"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    fun show(context: Context, scope: LifecycleCoroutineScope) {
        val v = LayoutInflater.from(context).inflate(R.layout.dialog_about, null)
        v.findViewById<TextView>(R.id.aboutVersion).text = "버전 ${BuildConfig.VERSION_NAME}  ·  업데이트 ${BuildConfig.BUILD_DATE}"
        v.findViewById<TextView>(R.id.aboutAuthor).text = AUTHOR
        v.findViewById<TextView>(R.id.aboutEmail).text = EMAIL
        v.findViewById<View>(R.id.aboutEmailRow).setOnClickListener {
            open(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "EveryVideo ${BuildConfig.VERSION_NAME} 문의"))
        }
        v.findViewById<View>(R.id.aboutLinkRow).setOnClickListener { openUrl(context, UpdateChecker.RELEASES_URL) }
        val status = v.findViewById<TextView>(R.id.aboutStatus)
        AppDialog(context)
            .icon(R.drawable.ic_info)
            .title(context.getString(R.string.app_name))
            .content(v)
            .secondary("닫기")
            .keepOpenOnPrimary()
            .primary("업데이트 확인") {
                status.visibility = View.VISIBLE
                status.text = "확인하는 중…"
                scope.launch {
                    val r = UpdateChecker.latest()
                    status.text = when {
                        r == null -> "확인하지 못했습니다. 인터넷 연결을 확인해 주세요."
                        UpdateChecker.isNewer(r.version, BuildConfig.VERSION_NAME) -> {
                            showUpdate(context, r, manual = true)
                            "새 버전 ${r.version}이 있습니다."
                        }
                        else -> "최신 버전입니다. (최신 ${r.version})"
                    }
                }
            }
            .show()
    }

    /** 앱을 열 때 하루 두 번까지 조용히 확인하고, 새 버전이면 알려준다. */
    fun checkOnLaunch(context: Context, scope: LifecycleCoroutineScope) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_NOTIFY, true)) return
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        scope.launch {
            val r = UpdateChecker.latest() ?: return@launch
            sp.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (UpdateChecker.isNewer(r.version, BuildConfig.VERSION_NAME) && sp.getString(KEY_SKIP, null) != r.version) {
                showUpdate(context, r, manual = false)
            }
        }
    }

    private fun showUpdate(context: Context, r: UpdateChecker.Release, manual: Boolean) {
        val notes = r.notes.trim().let { if (it.length > 500) it.take(500) + "…" else it }
        val d = AppDialog(context)
            .icon(R.drawable.ic_download)
            .title("새 버전이 나왔습니다")
            .message(
                "EveryVideo ${r.version}" + (if (r.publishedAt.isNotEmpty()) "  (${r.publishedAt})" else "") +
                    "\n지금 버전 ${BuildConfig.VERSION_NAME}" + (if (notes.isNotEmpty()) "\n\n$notes" else "")
            )
            .secondary("나중에")
            .primary("내려받기") { openUrl(context, r.apkUrl ?: r.pageUrl) }
        if (!manual) {
            d.tertiary("이 버전 건너뛰기") {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SKIP, r.version).apply()
            }
        }
        d.show()
    }

    fun openUrl(context: Context, url: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun open(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { Util.toast(context, "열 수 있는 앱이 없습니다") }
    }
}
