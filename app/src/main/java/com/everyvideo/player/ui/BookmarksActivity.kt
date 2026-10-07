package com.everyvideo.player.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Bookmark
import kotlinx.coroutines.launch

/** 모든 동영상의 즐겨찾기를 모아 보고, 누르면 그 시점부터 재생한다. */
class BookmarksActivity : AppCompatActivity() {
    private val db by lazy { (application as App).db }
    private var bookmarks: List<Bookmark> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val empty = findViewById<TextView>(R.id.empty)
        empty.text = "즐겨찾기가 없습니다. 재생 화면의 북마크 버튼으로 추가하세요."
        val list = findViewById<ListView>(R.id.list)
        val adapter = object : ArrayAdapter<Bookmark>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val b = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text =
                    Util.formatTime(b.positionMs) + if (b.label.isNotEmpty()) "  ${b.label}" else ""
                v.findViewById<TextView>(android.R.id.text2).text = b.videoTitle
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val b = bookmarks[pos]
            startActivity(Util.playIntent(this, listOf(Uri.parse(b.videoUri)), 0, b.positionMs))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val b = bookmarks[pos]
            AlertDialog.Builder(this)
                .setMessage("${b.videoTitle} · ${Util.formatTime(b.positionMs)}\n즐겨찾기를 삭제할까요?")
                .setPositiveButton("삭제") { _, _ -> lifecycleScope.launch { db.bookmarks().delete(b) } }
                .setNegativeButton("취소", null)
                .show()
            true
        }
        lifecycleScope.launch {
            db.bookmarks().observeAll().collect {
                bookmarks = it
                adapter.clear()
                adapter.addAll(it)
                empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
