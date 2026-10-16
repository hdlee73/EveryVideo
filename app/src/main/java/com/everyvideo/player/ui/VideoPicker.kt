package com.everyvideo.player.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.everyvideo.player.R
import com.everyvideo.player.data.Prefs

/**
 * 동영상 파일 고르기. 시스템 파일 선택기를 기기는 다운로드 폴더, Google Drive 는 내 드라이브에서 연다
 * ('최근' 화면으로 시작하지 않게). 다른 앱에서 찾기는 선택기를 그대로 연다.
 * 액티비티의 프로퍼티 초기화 시점에 만들어야 한다 (결과 콜백 등록 때문).
 */
class VideoPicker(private val activity: ComponentActivity) {
    enum class Source { MY_FILES, DRIVE, OTHER }

    private var callback: ((List<Uri>) -> Unit)? = null
    private val prefs by lazy { Prefs(activity) }

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = callback
        callback = null
        if (result.resultCode != Activity.RESULT_OK || cb == null) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        uris.forEach { uri ->
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        uris.firstOrNull { it.authority == DRIVE_AUTHORITY }?.let(::rememberDriveStart)
        if (uris.isNotEmpty()) cb(uris)
    }

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun pick(source: Source, onPicked: (List<Uri>) -> Unit) {
        askMediaPermissionOnce()
        callback = onPicked
        val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_MIME_TYPES, VIDEO_MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val intent = when (source) {
            Source.MY_FILES -> Intent(openDoc).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOAD_DIR)
            Source.DRIVE -> {
                val start = prefs.driveStartUri
                if (start == null) {
                    Toast.makeText(activity, "왼쪽 위 메뉴(☰)에서 Google Drive 를 고르세요. 다음부터는 내 드라이브에서 바로 열립니다", Toast.LENGTH_LONG).show()
                }
                Intent(openDoc).apply { if (start != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, start) }
            }
            Source.OTHER -> openDoc
        }
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            runCatching { launcher.launch(openDoc) }
        }
    }

    /**
     * Drive 에서 고른 파일로 '내 드라이브' 맨 위 폴더를 알아내 다음 시작 위치로 저장한다.
     * Drive 가 경로를 알려주지 않으면 고른 파일 자체를 저장한다 (선택기가 그 파일이 든 폴더를 연다).
     */
    private fun rememberDriveStart(picked: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val top = runCatching {
                DocumentsContract.findDocumentPath(activity.contentResolver, picked)?.path?.firstOrNull()
            }.getOrNull()
            prefs.driveStartUri = if (top != null) DocumentsContract.buildDocumentUri(DRIVE_AUTHORITY, top) else picked
        }
    }

    fun chooseAndPick(title: String, onPicked: (List<Uri>) -> Unit) {
        AppDialog.choice(
            activity, title,
            listOf(
                AppDialog.Companion.Item("내 파일", "휴대폰 저장공간 · 다운로드 폴더에서 시작", R.drawable.ic_phone),
                AppDialog.Companion.Item("Google Drive", "내 드라이브의 동영상", R.drawable.ic_drive),
                AppDialog.Companion.Item("다른 앱에서 찾기", "시스템 파일 선택기", R.drawable.ic_apps)
            )
        ) { which -> pick(Source.entries[which], onPicked) }
    }

    private fun askMediaPermissionOnce() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) return
        val sp = activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
        if (!sp.getBoolean("askedMedia", false)) {
            sp.edit().putBoolean("askedMedia", true).apply()
            permission.launch(perm)
        }
    }

    companion object {
        const val DRIVE_AUTHORITY = "com.google.android.apps.docs.storage"
        /** 내장 메모리의 Download 폴더 (시스템 선택기의 시작 위치). */
        val DOWNLOAD_DIR: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
        val VIDEO_MIME_TYPES = arrayOf(
            "video/*", "application/x-matroska", "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr",
            "application/x-mpegURL", "application/octet-stream"
        )
    }
}

/** 저장 폴더(SAF 트리) 고르기. 액티비티 프로퍼티 초기화 시점에 만든다. */
class FolderPicker(private val activity: ComponentActivity) {
    private var callback: ((Uri) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val cb = callback
        callback = null
        if (uri != null && cb != null) {
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            cb(uri)
        }
    }

    fun pick(onPicked: (Uri) -> Unit) {
        callback = onPicked
        launcher.launch(null)
    }
}
