package com.everyvideo.player.ui.pages

import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Playlist
import com.everyvideo.player.data.PlaylistItem
import com.everyvideo.player.ui.AppDialog
import com.everyvideo.player.ui.Sorting
import com.everyvideo.player.ui.Util
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 재생목록 목록과, 하나를 고르면 그 안의 동영상 목록. */
class PlaylistsPage : ListPage(), BackHandler {
    private val dao by lazy { (requireActivity().application as App).db.playlists() }
    private var job: Job? = null
    private var openId: Long = -1
    private var items: List<PlaylistItem> = emptyList()

    override fun setup() {
        upButton.setOnClickListener { showLists() }
        showLists()
    }

    override fun onBack(): Boolean {
        if (openId >= 0) {
            showLists(); return true
        }
        return false
    }

    private fun showLists() {
        openId = -1
        upButton.visibility = View.GONE
        setTitle("재생목록")
        clearActions()
        addAction(R.drawable.ic_add, "새 재생목록", primary = true) { createPlaylist() }
        setEmpty(
            R.drawable.ic_playlist, "재생목록이 없습니다",
            "'새 재생목록'을 눌러 만들고 동영상을 추가하세요.\n재생 화면 오른쪽 재생목록에서 지금 목록을 저장할 수도 있습니다.",
            "새 재생목록"
        ) { createPlaylist() }
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            dao.observe().collect { lists ->
                setRows(lists.map { p ->
                    Row(
                        title = p.name,
                        subtitle = "동영상 ${p.count}개",
                        icon = R.drawable.ic_playlist,
                        onMore = { playlistMenu(p.id, p.name) },
                        onClick = { openPlaylist(p.id, p.name) }
                    )
                })
            }
        }
    }

    private fun createPlaylist(then: ((Long, String) -> Unit)? = null) {
        AppDialog.input(requireContext(), "새 재생목록", "이름", ok = "만들기", icon = R.drawable.ic_playlist) { name ->
            if (name.isEmpty()) return@input
            lifecycleScope.launch {
                val id = dao.insert(Playlist(name = name))
                if (then != null) then(id, name) else openPlaylist(id, name)
            }
        }
    }

    private fun playlistMenu(id: Long, name: String) {
        AppDialog.choice(
            requireContext(), name,
            listOf(
                AppDialog.Companion.Item("전체 재생", icon = R.drawable.ic_play),
                AppDialog.Companion.Item("이름 바꾸기", icon = R.drawable.ic_edit),
                AppDialog.Companion.Item("삭제", icon = R.drawable.ic_delete)
            )
        ) { which ->
            when (which) {
                0 -> playFrom(id, 0)
                1 -> AppDialog.input(requireContext(), "이름 바꾸기", "이름", name, icon = R.drawable.ic_edit) { n ->
                    if (n.isNotEmpty()) lifecycleScope.launch { dao.get(id)?.let { dao.update(it.copy(name = n)) } }
                }
                2 -> AppDialog.confirm(
                    requireContext(), "재생목록 삭제", "'$name' 재생목록을 삭제할까요? 동영상 파일은 지워지지 않습니다.",
                    ok = "삭제", icon = R.drawable.ic_delete, danger = true
                ) {
                    lifecycleScope.launch { dao.clearItems(id); dao.deletePlaylist(id) }
                    if (openId == id) showLists()
                }
            }
        }
    }

    private fun playFrom(id: Long, index: Int) {
        lifecycleScope.launch {
            val list = dao.items(id)
            if (list.isEmpty()) Util.toast(requireContext(), "재생목록이 비어 있습니다")
            else main.play(list.map { Uri.parse(it.uri) }, index.coerceIn(0, list.size - 1), playlistId = id)
        }
    }

    private fun openPlaylist(id: Long, name: String) {
        openId = id
        upButton.visibility = View.VISIBLE
        clearActions()
        setTitle(name, "X는 목록에서만 빼기 (파일은 그대로) · 손잡이를 끌어 순서 변경")
        addAction(R.drawable.ic_add, "추가") { addVideos(id) }
        addAction(R.drawable.ic_sort, "정렬") { sortItems() }
        addAction(R.drawable.ic_play, "전체 재생", primary = true) { playFrom(id, 0) }
        setEmpty(R.drawable.ic_movie, "비어 있는 재생목록", "'추가'를 눌러 동영상을 넣으세요.", "동영상 추가") { addVideos(id) }
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            dao.observeItems(id).collect { list ->
                items = list
                setRows(list.mapIndexed { i, item ->
                    Row(
                        title = item.title,
                        badge = (i + 1).toString(),
                        draggable = true,
                        onDelete = { removeItem(item) },
                        onClick = { playFrom(id, i) }
                    )
                })
            }
        }
    }

    private fun addVideos(id: Long) {
        main.chooseSourceAndPick("동영상 추가") { uris ->
            lifecycleScope.launch {
                var sort = dao.maxSort(id) + 1
                dao.insertItems(uris.map { u ->
                    PlaylistItem(playlistId = id, uri = u.toString(), title = Util.displayName(requireContext(), u), sort = sort++)
                })
            }
        }
    }

    private fun removeItem(item: PlaylistItem) {
        lifecycleScope.launch { dao.deleteItem(item) }
        Util.toast(requireContext(), "'${item.title}'을(를) 재생목록에서 뺐습니다")
    }

    override fun onRowMoved(from: Int, to: Int) {
        if (openId < 0 || from !in items.indices || to !in items.indices) return
        val list = items.toMutableList()
        list.add(to, list.removeAt(from))
        items = list
        lifecycleScope.launch { dao.updateItems(list.mapIndexed { i, it -> it.copy(sort = i) }) }
    }

    private fun sortItems() {
        if (items.size < 2) return
        Sorting.choose(requireContext()) { order ->
            lifecycleScope.launch {
                val sorted = Sorting.sort(requireContext(), items, order, { Uri.parse(it.uri) }, { it.title })
                dao.updateItems(sorted.mapIndexed { i, it -> it.copy(sort = i) })
            }
        }
    }
}
