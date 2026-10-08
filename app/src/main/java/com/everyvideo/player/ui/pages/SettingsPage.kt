package com.everyvideo.player.ui.pages

import com.everyvideo.player.BuildConfig
import com.everyvideo.player.R
import com.everyvideo.player.data.Prefs
import com.everyvideo.player.media.MediaStoreSaver
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.ui.AboutDialog
import com.everyvideo.player.ui.AppDialog

class SettingsPage : ListPage() {
    private val prefs by lazy { Prefs(requireContext()) }

    override fun setup() {
        setTitle("설정")
        render()
    }

    private fun render() {
        val sp = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val notify = sp.getBoolean(AboutDialog.KEY_NOTIFY, true)
        setRows(listOf(
            Row(
                "캡쳐 저장 위치", prefs.folderLabel(prefs.imageFolder, MediaStoreSaver.DEFAULT_IMAGE_LABEL),
                R.drawable.ic_camera, showMore = false
            ) { main.pickFolder { prefs.imageFolder = it; render() } },
            Row(
                "동영상 저장 위치 (구간 저장·이어붙이기·화면 녹화)",
                prefs.folderLabel(prefs.videoFolder, MediaStoreSaver.DEFAULT_VIDEO_LABEL),
                R.drawable.ic_movie, showMore = false
            ) { main.pickFolder { prefs.videoFolder = it; render() } },
            Row(
                "저장할 때 이름과 위치 묻기 (캡쳐·구간·GIF·썸네일)", if (prefs.askOnCapture) "켜짐 · 저장 전에 파일 이름을 정합니다" else "꺼짐 · 바로 저장합니다",
                R.drawable.ic_edit, showMore = false
            ) { prefs.askOnCapture = !prefs.askOnCapture; render() },
            Row("저장 위치를 기본값으로", "사진/EveryVideo, 동영상/EveryVideo", R.drawable.ic_folder, showMore = false) {
                AppDialog.confirm(requireContext(), "기본 위치로 되돌리기", "저장 위치를 기본 폴더로 되돌릴까요?", "되돌리기", R.drawable.ic_folder) {
                    prefs.imageFolder = null; prefs.videoFolder = null; render()
                }
            },
            Row("보안 폴더에서 쓰기", "삼성 보안 폴더의 기본 재생기로 지정하는 방법", R.drawable.ic_lock, showMore = false) {
                AppDialog.info(
                    requireContext(), "보안 폴더에서 쓰기",
                    "1. 보안 폴더를 열고 오른쪽 위 '+'(앱 추가)를 누릅니다.\n" +
                        "2. 목록에서 EveryVideo를 골라 추가합니다.\n" +
                        "3. 보안 폴더 안의 '내 파일'이나 '갤러리'에서 동영상을 누르고 '다른 앱으로 열기' → EveryVideo → '항상'을 고릅니다.\n\n" +
                        "보안 폴더 안의 앱은 따로 설치된 것과 같아서, 재생목록·즐겨찾기도 보안 폴더 안에서 따로 관리됩니다.",
                    R.drawable.ic_lock
                )
            },
            Row(
                "새 버전 알림", if (notify) "켜짐 · 앱을 열 때 새 버전이 있으면 알려줍니다" else "꺼짐",
                R.drawable.ic_download, showMore = false
            ) { sp.edit().putBoolean(AboutDialog.KEY_NOTIFY, !notify).apply(); render() },
            Row(
                "앱 정보", "EveryVideo ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_DATE} · ${AboutDialog.AUTHOR}",
                R.drawable.ic_info, showMore = false
            ) { AboutDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope) }
        ))
    }
}
