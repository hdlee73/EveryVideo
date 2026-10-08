package com.everyvideo.player.ui.pages

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.everyvideo.player.R
import com.everyvideo.player.data.Prefs
import com.everyvideo.player.media.MediaStoreSaver
import com.everyvideo.player.media.VideoExporter
import com.everyvideo.player.ui.AppDialog
import com.everyvideo.player.ui.Sorting
import com.everyvideo.player.ui.Util
import kotlinx.coroutines.launch

/** 여러 동영상을 순서대로 이어붙여 하나의 MP4 로 저장. 맨 앞에 썸네일 이미지를 넣을 수 있다. */
@UnstableApi
class ConcatPage : ListPage() {
    private val items = mutableListOf<Pair<Uri, String>>()
    private var exporter: VideoExporter? = null
    private var thumb: Uri? = null
    private var thumbName = ""
    private var thumbSeconds = 2

    override fun setup() {
        addAction(R.drawable.ic_add, "추가") { add() }
        addAction(R.drawable.ic_sort, "정렬") { sort() }
        addAction(R.drawable.ic_image, "썸네일") { chooseThumb() }
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
        val sub = buildString {
            append("오른쪽 손잡이를 끌어 순서를 바꾸세요")
            if (thumb != null) append("  ·  썸네일: $thumbName (맨 앞 ${thumbSeconds}초)")
        }
        setTitle("동영상 이어붙이기", sub)
        setRows(items.mapIndexed { i, (_, name) ->
            Row(
                title = name, badge = (i + 1).toString(), draggable = true,
                onDelete = { items.removeAt(i); refresh() },
                onClick = { main.play(items.map { it.first }, i) }
            )
        })
    }

    override fun onRowMoved(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        refresh()
    }

    private fun sort() {
        if (items.size < 2) return
        Sorting.choose(requireContext()) { order ->
            viewLifecycleOwner.lifecycleScope.launch {
                val sorted = Sorting.sort(requireContext(), items.toList(), order, { it.first }, { it.second })
                items.clear()
                items.addAll(sorted)
                refresh()
            }
        }
    }

    private fun chooseThumb() {
        val options = mutableListOf(
            AppDialog.Companion.Item("이미지 고르기", "갤러리·파일에서 사진 선택 (재생 화면의 '썸네일 만들기'로 만든 것도 가능)", R.drawable.ic_image)
        )
        if (thumb != null) options += AppDialog.Companion.Item("썸네일 빼기", thumbName, R.drawable.ic_delete)
        AppDialog.choice(
            requireContext(), "썸네일 넣기", options,
            message = "고른 이미지를 결과 동영상 맨 앞에 몇 초 보여줍니다. 갤러리 등에서는 이 장면이 동영상의 대표 이미지로 보입니다."
        ) { which ->
            if (which == 1) {
                thumb = null; refresh(); return@choice
            }
            main.pickImage { uri ->
                val secs = intArrayOf(1, 2, 3, 5)
                AppDialog.choice(
                    requireContext(), "맨 앞에 보여줄 시간",
                    secs.map { AppDialog.Companion.Item("${it}초") }, checked = secs.indexOf(thumbSeconds)
                ) { s ->
                    thumb = uri
                    thumbName = Util.displayName(requireContext(), uri)
                    thumbSeconds = secs[s]
                    refresh()
                }
            }
        }
    }

    private fun chooseQuality() {
        if (items.size < 2 && !(items.size == 1 && thumb != null)) {
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
        val cover = thumb?.let { VideoExporter.Cover(it, thumbSeconds * 1000L) }
        ex.exportConcat(items.map { it.first }, height, name, prefs.videoFolder, cover, object : VideoExporter.Callback {
            override fun onProgress(percent: Int) = p.set(percent)

            override fun onStatus(message: String) {
                p.dialog.message(message)
                p.set(0)
            }

            override fun onDone(saved: Uri) {
                p.dialog.dismiss()
                val ctx = context ?: return
                AppDialog(ctx).icon(R.drawable.ic_check).title("저장했습니다").message("$folderLabel 에 저장했습니다." + (ex.resultNote?.let { "\n\n$it" } ?: ""))
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
