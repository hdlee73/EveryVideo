package com.everyvideo.player.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.RemoteServer
import com.everyvideo.player.net.RemoteBrowser
import com.everyvideo.player.net.RemoteEntry
import com.everyvideo.player.net.RemoteUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SERVER_ID = "serverId"
    }

    private lateinit var server: RemoteServer
    private var path = ""
    private var entries: List<RemoteEntry> = emptyList()
    private lateinit var adapter: ArrayAdapter<RemoteEntry>
    private lateinit var header: TextView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        header = findViewById(R.id.header)
        header.visibility = View.VISIBLE
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)

        val list = findViewById<ListView>(R.id.list)
        adapter = object : ArrayAdapter<RemoteEntry>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = (if (e.isDir) "📁 " else "🎬 ") + e.name
                v.findViewById<TextView>(android.R.id.text2).text = if (e.isDir) "폴더" else sizeText(e.size)
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = entries[pos]
            if (e.isDir) load(e.path) else {
                val videos = entries.filter { !it.isDir }
                val uris = videos.map { RemoteUris.build(server, it.path) }
                startActivity(Util.playIntent(this, uris, videos.indexOf(e)))
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::server.isInitialized && path.trimEnd('/') != server.path.trimEnd('/') && path.isNotEmpty()) {
                    load(path.substringBeforeLast('/'))
                } else finish()
            }
        })

        val id = intent.getLongExtra(EXTRA_SERVER_ID, -1)
        lifecycleScope.launch {
            val s = (application as App).db.servers().get(id)
            if (s == null) {
                finish(); return@launch
            }
            server = s
            title = s.name
            load(s.path)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun sizeText(size: Long): String = when {
        size >= 1L shl 30 -> "%.2f GB".format(size / (1L shl 30).toDouble())
        size >= 1L shl 20 -> "%.1f MB".format(size / (1L shl 20).toDouble())
        else -> "${size / 1024} KB"
    }

    private fun load(newPath: String) {
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        header.text = "${server.type}://${server.host}/${server.share}$newPath".replace("//$", "/")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RemoteBrowser.list(server, newPath) } }
            progress.visibility = View.GONE
            result.onSuccess {
                path = newPath
                entries = it
                adapter.clear()
                adapter.addAll(it)
                empty.text = "동영상이나 폴더가 없습니다"
                empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                empty.text = "연결 실패: ${it.message ?: it.javaClass.simpleName}"
                empty.visibility = View.VISIBLE
            }
        }
    }
}
