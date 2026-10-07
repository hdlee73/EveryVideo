package com.everyvideo.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.everyvideo.player.R
import com.everyvideo.player.media.MediaStoreSaver
import com.everyvideo.player.media.VideoExporter
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

@UnstableApi
class ConcatActivity : AppCompatActivity() {
    private val items = mutableListOf<Pair<Uri, String>>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var empty: TextView
    private lateinit var exporter: VideoExporter

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris?.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            items += uri to Util.displayName(this, uri)
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        exporter = VideoExporter(this)

        findViewById<TextView>(R.id.header).apply {
            visibility = View.VISIBLE
            text = "이어붙일 동영상을 순서대로 추가하세요. 항목을 누르면 순서를 바꾸거나 뺄 수 있습니다. 결과는 H.264 MP4 로 동영상/EveryVideo 에 저장됩니다."
        }
        empty = findViewById(R.id.empty)
        empty.text = "추가된 동영상이 없습니다"
        val list = findViewById<ListView>(R.id.list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this)
                .setTitle(items[pos].second)
                .setItems(arrayOf("위로", "아래로", "빼기")) { _, which ->
                    when (which) {
                        0 -> if (pos > 0) items.add(pos - 1, items.removeAt(pos))
                        1 -> if (pos < items.size - 1) items.add(pos + 1, items.removeAt(pos))
                        2 -> items.removeAt(pos)
                    }
                    refresh()
                }
                .show()
        }
        findViewById<View>(R.id.bottomBar).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.btnSecondary).apply {
            text = "동영상 추가"
            setOnClickListener { pick.launch(MainActivity.VIDEO_MIME_TYPES) }
        }
        findViewById<MaterialButton>(R.id.btnPrimary).apply {
            text = "이어붙이기"
            setOnClickListener { chooseQualityAndExport() }
        }
        refresh()
    }

    override fun onDestroy() {
        exporter.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        adapter.clear()
        adapter.addAll(items.mapIndexed { i, p -> "${i + 1}. ${p.second}" })
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun chooseQualityAndExport() {
        if (items.size < 2) {
            Util.toast(this, "동영상을 2개 이상 추가하세요")
            return
        }
        val heights = intArrayOf(480, 720, 1080, 1440, 2160)
        AlertDialog.Builder(this)
            .setTitle("결과 해상도 (세로 픽셀)")
            .setItems(heights.map { "${it}p" }.toTypedArray()) { _, which -> export(heights[which]) }
            .show()
    }

    private fun export(height: Int) {
        val bar = LinearProgressIndicator(this).apply { max = 100 }
        val box = LinearLayout(this).apply {
            setPadding(64, 32, 64, 0)
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("이어붙이는 중")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { _, _ -> exporter.cancel() }
            .show()
        exporter.exportConcat(items.map { it.first }, height, "concat_${MediaStoreSaver.stamp()}", object : VideoExporter.Callback {
            override fun onProgress(percent: Int) = bar.setProgressCompat(percent, true)

            override fun onDone(saved: Uri) {
                dialog.dismiss()
                AlertDialog.Builder(this@ConcatActivity)
                    .setTitle("저장 완료")
                    .setMessage("동영상/EveryVideo 에 저장했습니다.")
                    .setPositiveButton("재생") { _, _ -> startActivity(Util.playIntent(this@ConcatActivity, listOf(saved))) }
                    .setNegativeButton("닫기", null)
                    .show()
            }

            override fun onError(message: String) {
                dialog.dismiss()
                AlertDialog.Builder(this@ConcatActivity).setTitle("실패").setMessage(message)
                    .setPositiveButton("확인", null).show()
            }
        })
    }
}
