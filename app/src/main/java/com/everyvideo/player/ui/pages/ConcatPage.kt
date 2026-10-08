package com.everyvideo.player.ui.pages

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.everyvideo.player.R
import com.everyvideo.player.data.Prefs
import com.everyvideo.player.media.MediaStoreSaver
import com.everyvideo.player.media.VideoExporter
import com.everyvideo.player.ui.AppDialog
import com.everyvideo.player.ui.Util

/** 여러 동영상을 순서대로 이어붙여 하나의 MP4 로 저장. */
@UnstableApi
class ConcatPage : ListPage() {
    private val items = mutableListOf<Pair<Uri, String>>()
    private var exporter: VideoExporter? = null

    override fun setup() {
        setTitle("동영상 이어붙이기", "순서대로 추가한 뒤 '이어붙이기'를 누르세요")
        addAction(R.drawable.ic_add, "추가") { add() }
        addAction(R.drawable.ic_merge, "이어붙이기", primary = true) { chooseQuality() }
        setEmpty(R.drawable.ic_merge, "이어붙일 동영상을 추가하세요", "두 개 이상 추가하면 하나의 동영상(H.264 MP4)으로 합쳐 저장합니다.", "동영상 추가") { add() }
        refresh()
    }

    override fun onDestroyView() {
        exporter?.cancel()
        super.onDestroyView()
    }

    private fun add() {
        main.chooseSourceAndPick("동영상 추가") { uris ->
            uris.forEach { items += it to Util.displayName(requireContext(), it) }
            refresh()
        }
    }

    private fun refresh() {
        setRows(items.mapIndexed { i, (_, name) ->
            Row(title = name, badge = (i + 1).toString(), onMore = { menu(i) }, onClick = { menu(i) })
        })
    }

    private fun menu(pos: Int) {
        AppDialog.choice(
            requireContext(), items[pos].second,
            listOf(
                AppDialog.Companion.Item("위로 이동", icon = R.drawable.ic_up),
                AppDialog.Companion.Item("아래로 이동", icon = R.drawable.ic_down),
                AppDialog.Companion.Item("빼기", icon = R.drawable.ic_delete)
            )
        ) { which ->
            when (which) {
                0 -> if (pos > 0) items.add(pos - 1, items.removeAt(pos))
                1 -> if (pos < items.size - 1) items.add(pos + 1, items.removeAt(pos))
                2 -> items.removeAt(pos)
            }
            refresh()
        }
    }

    private fun chooseQuality() {
        if (items.size < 2) {
            Util.toast(requireContext(), "동영상을 2개 이상 추가하세요")
            return
        }
        val heights = intArrayOf(480, 720, 1080, 1440, 2160)
        val names = listOf("480p", "720p (HD)", "1080p (Full HD)", "1440p (QHD)", "2160p (4K)")
        AppDialog.choice(
            requireContext(), "결과 해상도",
            names.map { AppDialog.Companion.Item(it) }, checked = 2,
            message = "해상도가 다른 동영상은 이 크기로 맞춰집니다."
        ) { which -> askName(heights[which]) }
    }

    private fun askName(height: Int) {
        AppDialog.input(requireContext(), "파일 이름", "파일 이름", "concat_${MediaStoreSaver.stamp()}", "저장", R.drawable.ic_save) { name ->
            export(height, name.ifBlank { "concat_${MediaStoreSaver.stamp()}" })
        }
    }

    private fun export(height: Int, name: String) {
        val ex = VideoExporter(requireContext())
        exporter = ex
        val prefs = Prefs(requireContext())
        val folderLabel = prefs.folderLabel(prefs.videoFolder, MediaStoreSaver.DEFAULT_VIDEO_LABEL)
        val p = AppDialog.progress(requireContext(), "이어붙이는 중", "저장 위치: $folderLabel") { ex.cancel() }
        ex.exportConcat(items.map { it.first }, height, name, prefs.videoFolder, object : VideoExporter.Callback {
            override fun onProgress(percent: Int) = p.set(percent)

            override fun onDone(saved: Uri) {
                p.dialog.dismiss()
                val ctx = context ?: return
                AppDialog(ctx).icon(R.drawable.ic_check).title("저장했습니다").message("$folderLabel 에 저장했습니다.")
                    .secondary("닫기").primary("재생") { main.play(listOf(saved)) }.show()
            }

            override fun onError(message: String) {
                p.dialog.dismiss()
                val ctx = context ?: return
                AppDialog.info(ctx, "이어붙이지 못했습니다", message, R.drawable.ic_error)
            }
        })
    }
}
