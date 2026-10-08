package com.everyvideo.player.ui.pages

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Bookmark
import com.everyvideo.player.ui.AppDialog
import com.everyvideo.player.ui.Util
import kotlinx.coroutines.launch

/** 모든 동영상의 즐겨찾기. 누르면 그 시점부터 재생. */
class BookmarksPage : ListPage() {
    private val dao by lazy { (requireActivity().application as App).db.bookmarks() }

    override fun setup() {
        setTitle("즐겨찾기")
        setEmpty(
            R.drawable.ic_bookmarks, "즐겨찾기가 없습니다",
            "동영상을 보다가 재생 화면의 북마크 버튼을 누르면 그 시점이 여기에 저장됩니다."
        )
        viewLifecycleOwner.lifecycleScope.launch {
            dao.observeAll().collect { list ->
                setRows(list.map { b ->
                    Row(
                        title = Util.formatTime(b.positionMs) + if (b.label.isNotEmpty()) "  ·  ${b.label}" else "",
                        subtitle = b.videoTitle,
                        icon = R.drawable.ic_bookmarks,
                        onMore = { menu(b) },
                        onClick = { main.play(listOf(Uri.parse(b.videoUri)), 0, b.positionMs) }
                    )
                })
            }
        }
    }

    private fun menu(b: Bookmark) {
        AppDialog.choice(
            requireContext(), "${b.videoTitle} · ${Util.formatTime(b.positionMs)}",
            listOf(
                AppDialog.Companion.Item("메모 수정", icon = R.drawable.ic_edit),
                AppDialog.Companion.Item("삭제", icon = R.drawable.ic_delete)
            )
        ) { which ->
            if (which == 0) {
                AppDialog.input(requireContext(), "메모 수정", "메모", b.label, icon = R.drawable.ic_edit) { t ->
                    lifecycleScope.launch { dao.update(b.copy(label = t)) }
                }
            } else {
                AppDialog.confirm(requireContext(), "즐겨찾기 삭제", "이 즐겨찾기를 삭제할까요?", "삭제", R.drawable.ic_delete, true) {
                    lifecycleScope.launch { dao.delete(b) }
                }
            }
        }
    }
}
