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
import androidx.core.content.ContextCompat
import com.everyvideo.player.R

/**
 * 동영상 파일 고르기. 삼성 '내 파일', Google Drive(시스템 선택기), 다른 앱 중에서 연다.
 * 액티비티의 프로퍼티 초기화 시점에 만들어야 한다 (결과 콜백 등록 때문).
 */
class VideoPicker(private val activity: ComponentActivity) {
    enum class Source { MY_FILES, DRIVE, OTHER }

    private var callback: ((List<Uri>) -> Unit)? = null

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
            Source.MY_FILES -> {
                val myFiles = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    setPackage(MY_FILES_PACKAGE)
                }
                if (myFiles.resolveActivity(activity.packageManager) != null) myFiles else {
                    Util.toast(activity, "삼성 '내 파일' 앱이 없어 기본 파일 선택기로 엽니다")
                    openDoc
                }
            }
            Source.DRIVE -> {
                // Google Drive 앱의 자체 선택 화면 (빠름, 왼쪽 위 X 나 뒤로 가기로 그냥 닫을 수 있음).
                // Drive 앱이 없으면 시스템 파일 선택기를 Drive 위치로 연다.
                val drive = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    setPackage(DRIVE_PACKAGE)
                }
                if (drive.resolveActivity(activity.packageManager) != null) drive else openDoc.apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri(DRIVE_AUTHORITY, "root"))
                }
            }
            Source.OTHER -> openDoc
        }
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            runCatching { launcher.launch(openDoc) }
        }
    }

    fun chooseAndPick(title: String, onPicked: (List<Uri>) -> Unit) {
        AppDialog.choice(
            activity, title,
            listOf(
                AppDialog.Companion.Item("내 파일", "휴대폰 저장공간 · SD카드", R.drawable.ic_phone),
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
        const val MY_FILES_PACKAGE = "com.sec.android.app.myfiles"
        const val DRIVE_PACKAGE = "com.google.android.apps.docs"
        const val DRIVE_AUTHORITY = "com.google.android.apps.docs.storage"
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
