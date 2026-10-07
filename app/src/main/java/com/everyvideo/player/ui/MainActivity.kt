package com.everyvideo.player.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Recent
import com.everyvideo.player.net.DriveLinks
import com.everyvideo.player.record.ScreenRecordActivity
import com.everyvideo.player.record.ScreenRecordService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val db by lazy { (application as App).db }
    private var recents: List<Recent> = emptyList()

    private val pickVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Util.playIntent(this, uris))
    }

    private var pendingRecordWithMic = false
    private val requestRecordPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        if (pendingRecordWithMic && !micGranted) Util.toast(this, "마이크 권한이 없어 소리 없이 녹화합니다")
        startRecording(pendingRecordWithMic && micGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.btnOpenFile).setOnClickListener { pickVideos.launch(VIDEO_MIME_TYPES) }
        findViewById<View>(R.id.btnOpenUrl).setOnClickListener { showUrlDialog(null) }
        findViewById<View>(R.id.btnDrive).setOnClickListener { showDriveDialog() }
        findViewById<View>(R.id.btnServers).setOnClickListener { startActivity(Intent(this, ServersActivity::class.java)) }
        findViewById<View>(R.id.btnConcat).setOnClickListener { startActivity(Intent(this, ConcatActivity::class.java)) }
        findViewById<View>(R.id.btnBookmarks).setOnClickListener { startActivity(Intent(this, BookmarksActivity::class.java)) }
        findViewById<View>(R.id.btnRecord).setOnClickListener { onRecordClicked() }

        val list = findViewById<ListView>(R.id.list)
        val empty = findViewById<TextView>(R.id.empty)
        val adapter = object : ArrayAdapter<Recent>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val r = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = r.title
                val progress = if (r.durationMs > 0) "${Util.formatTime(r.positionMs)} / ${Util.formatTime(r.durationMs)}"
                else Util.formatTime(r.positionMs)
                v.findViewById<TextView>(android.R.id.text2).text = progress
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Util.playIntent(this, listOf(Uri.parse(recents[pos].videoUri))))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = recents[pos]
            AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(arrayOf("목록에서 삭제", "최근 목록 전체 삭제")) { _, which ->
                    lifecycleScope.launch { if (which == 0) db.recents().delete(r.videoKey) else db.recents().clear() }
                }
                .show()
            true
        }
        lifecycleScope.launch {
            db.recents().observe().collect {
                recents = it
                adapter.clear()
                adapter.addAll(it)
                empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.btnRecord).text =
            if (ScreenRecordService.isRecording) "녹화 중지" else getString(R.string.screen_record)
    }

    private fun showUrlDialog(prefill: String?) {
        val edit = EditText(this).apply {
            hint = "https://… , rtsp://… , 구글 드라이브 공유 링크"
            setText(prefill ?: "")
        }
        val box = LinearLayout(this).apply { setPadding(64, 16, 64, 0); addView(edit, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_url)
            .setView(box)
            .setPositiveButton("재생") { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val url = if (DriveLinks.isDriveLink(text)) DriveLinks.streamUrl(text) ?: text else text
                startActivity(Util.playIntent(this, listOf(Uri.parse(url))))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDriveDialog() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val clipLink = clip?.takeIf { DriveLinks.isDriveLink(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.google_drive)
            .setItems(
                arrayOf(
                    "내 드라이브에서 선택 (파일 선택기의 Google Drive)",
                    if (clipLink != null) "복사한 공유 링크로 바로 재생" else "공유 링크 붙여넣기"
                )
            ) { _, which ->
                if (which == 0) {
                    Util.toast(this, "파일 선택기 메뉴에서 Google Drive를 고르세요")
                    pickVideos.launch(VIDEO_MIME_TYPES)
                } else if (clipLink != null) {
                    DriveLinks.streamUrl(clipLink)?.let { startActivity(Util.playIntent(this, listOf(Uri.parse(it)))) }
                        ?: showUrlDialog(clipLink)
                } else {
                    showUrlDialog(null)
                }
            }
            .show()
    }

    private fun onRecordClicked() {
        if (ScreenRecordService.isRecording) {
            startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
            findViewById<TextView>(R.id.btnRecord).text = getString(R.string.screen_record)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.screen_record)
            .setMessage("화면을 동영상으로 녹화합니다. 알림창의 '중지'로 끝낼 수 있고, 파일은 동영상/EveryVideo 에 저장됩니다.\n\n보안 설정된 화면(일부 OTT·금융 앱 등)은 안드로이드 정책상 검게 녹화됩니다.")
            .setPositiveButton("마이크 소리와 함께") { _, _ -> askPermsAndRecord(true) }
            .setNeutralButton("소리 없이") { _, _ -> askPermsAndRecord(false) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPermsAndRecord(withMic: Boolean) {
        pendingRecordWithMic = withMic
        val perms = mutableListOf<String>()
        if (withMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) perms += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (perms.isEmpty()) startRecording(withMic) else requestRecordPerms.launch(perms.toTypedArray())
    }

    private fun startRecording(withMic: Boolean) {
        startActivity(
            Intent(this, ScreenRecordActivity::class.java).putExtra(ScreenRecordService.EXTRA_MIC, withMic)
        )
    }

    companion object {
        val VIDEO_MIME_TYPES = arrayOf(
            "video/*", "application/x-matroska", "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr",
            "application/x-mpegURL", "application/octet-stream"
        )
    }
}
