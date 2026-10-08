package com.everyvideo.player.ui.pages

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.RemoteServer
import com.everyvideo.player.net.RemoteBrowser
import com.everyvideo.player.net.RemoteEntry
import com.everyvideo.player.net.RemoteUris
import com.everyvideo.player.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** FTP / SMB 서버의 폴더를 탐색한다. */
class BrowsePage : ListPage(), BackHandler {
    companion object {
        fun of(serverId: Long) = BrowsePage().apply { arguments = Bundle().apply { putLong("id", serverId) } }
    }

    private var server: RemoteServer? = null
    private var path = ""
    private var entries: List<RemoteEntry> = emptyList()

    override fun setup() {
        upButton.visibility = android.view.View.VISIBLE
        upButton.setOnClickListener { if (!onBack()) main.show(MainActivity.Page.SERVERS) }
        val id = requireArguments().getLong("id")
        viewLifecycleOwner.lifecycleScope.launch {
            val s = (requireActivity().application as App).db.servers().get(id) ?: return@launch
            server = s
            load(s.path)
        }
    }

    override fun onBack(): Boolean {
        val s = server ?: return false
        if (path.trimEnd('/') != s.path.trimEnd('/') && path.isNotEmpty()) {
            load(path.substringBeforeLast('/')); return true
        }
        main.show(MainActivity.Page.SERVERS)
        return true
    }

    private fun sizeText(size: Long): String = when {
        size >= 1L shl 30 -> "%.2f GB".format(size / (1L shl 30).toDouble())
        size >= 1L shl 20 -> "%.1f MB".format(size / (1L shl 20).toDouble())
        else -> "${size / 1024} KB"
    }

    private fun load(newPath: String) {
        val s = server ?: return
        setTitle(s.name, "${s.type}://${s.host}/${s.share}$newPath")
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RemoteBrowser.list(s, newPath) } }
            setLoading(false)
            result.onSuccess { list ->
                path = newPath
                entries = list
                setEmpty(R.drawable.ic_folder, "비어 있는 폴더", "이 폴더에는 동영상이나 하위 폴더가 없습니다.")
                val videos = list.filter { !it.isDir }
                setRows(list.map { e ->
                    Row(
                        title = e.name,
                        subtitle = if (e.isDir) "폴더" else sizeText(e.size),
                        icon = if (e.isDir) R.drawable.ic_folder else R.drawable.ic_movie,
                        showMore = false,
                        onClick = {
                            if (e.isDir) load(e.path)
                            else main.play(videos.map { RemoteUris.build(s, it.path) }, videos.indexOf(e))
                        }
                    )
                })
            }.onFailure {
                setEmpty(R.drawable.ic_error, "연결하지 못했습니다", it.message ?: it.javaClass.simpleName, "다시 시도") { load(newPath) }
                setRows(emptyList())
            }
        }
    }
}
