package com.everyvideo.player.ui.pages

import android.net.Uri
import android.text.format.DateUtils
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Prefs
import com.everyvideo.player.data.Recent
import com.everyvideo.player.ui.AppDialog
import com.everyvideo.player.ui.Util
import com.everyvideo.player.ui.VideoPicker
import kotlinx.coroutines.launch

/** 시작 화면: 동영상 열기. 설정에서 켜면 최근에 본 동영상도 보여준다. */
class HomePage : ListPage() {
    private val dao by lazy { (requireActivity().application as App).db.recents() }

    override fun setup() {
        val showRecent = Prefs(requireContext()).showRecentOnHome
        if (showRecent) setTitle("최근 재생", "보던 곳부터 이어서 봅니다. 길게 누르면 처음부터 보거나 기록에서 지울 수 있어요")
        else setTitle("동영상 열기")
        addAction(R.drawable.ic_link, "주소") { main.showUrlDialog() }
        addAction(R.drawable.ic_open, "파일 열기", primary = true) {
            main.chooseSourceAndPick("동영상 열기") { main.play(it) }
        }
        setEmpty(
            R.drawable.ic_movie, "동영상 열기",
            "휴대폰의 다운로드 폴더나 Google Drive 의 동영상을 고르세요.",
            "내 파일에서 열기"
        ) { main.pickVideos(VideoPicker.Source.MY_FILES) { main.play(it) } }
        if (!showRecent) {
            setRows(emptyList())
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            dao.observe().collect { list -> setRows(list.map(::rowOf)) }
        }
    }

    private fun rowOf(r: Recent): Row {
        val done = r.durationMs > 0 && r.positionMs >= r.durationMs - 5_000
        val pct = if (r.durationMs > 0) (r.positionMs * 100 / r.durationMs).toInt().coerceIn(0, 100) else 0
        val where = when {
            done -> "다 봄"
            r.durationMs > 0 -> "${Util.formatTime(r.positionMs)} / ${Util.formatTime(r.durationMs)}"
            else -> Util.formatTime(r.positionMs)
        }
        val ago = DateUtils.getRelativeTimeSpanString(r.lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return Row(
            title = r.title.ifBlank { Util.displayName(requireContext(), Uri.parse(r.videoUri)) },
            subtitle = "$where · $ago",
            icon = if (done) R.drawable.ic_check else R.drawable.ic_play,
            badge = if (!done && pct in 1..99) "$pct%" else null,
            onMore = { menu(r) },
            onClick = { main.play(listOf(Uri.parse(r.videoUri)), startMs = if (done) 0L else -1L) }
        )
    }

    private fun menu(r: Recent) {
        AppDialog.choice(
            requireContext(), r.title,
            listOf(
                AppDialog.Companion.Item("처음부터 재생", icon = R.drawable.ic_replay10),
                AppDialog.Companion.Item("기록에서 지우기", icon = R.drawable.ic_close),
                AppDialog.Companion.Item("최근 기록 모두 지우기", icon = R.drawable.ic_delete)
            )
        ) { which ->
            when (which) {
                0 -> main.play(listOf(Uri.parse(r.videoUri)), startMs = 0L)
                1 -> lifecycleScope.launch { dao.delete(r.videoKey) }
                else -> AppDialog.confirm(
                    requireContext(), "최근 기록 지우기", "최근 재생 목록을 모두 지울까요? 동영상 파일은 그대로 있습니다.",
                    "모두 지우기", R.drawable.ic_delete, danger = true
                ) { lifecycleScope.launch { dao.clear() } }
            }
        }
    }
}
