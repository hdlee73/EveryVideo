package com.everyvideo.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.RemoteServer
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ServersActivity : AppCompatActivity() {
    private val db by lazy { (application as App).db }
    private var servers: List<RemoteServer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val header = findViewById<TextView>(R.id.header)
        header.visibility = View.VISIBLE
        header.text = "같은 네트워크의 PC 공유 폴더(SMB)나 FTP 서버를 등록하세요. 길게 눌러 수정/삭제할 수 있습니다."
        val empty = findViewById<TextView>(R.id.empty)
        empty.text = "등록된 서버가 없습니다"
        findViewById<View>(R.id.bottomBar).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.btnSecondary).visibility = View.GONE
        findViewById<MaterialButton>(R.id.btnPrimary).apply {
            text = "서버 추가"
            setOnClickListener { editServer(null) }
        }

        val list = findViewById<ListView>(R.id.list)
        val itemAdapter = object : ArrayAdapter<RemoteServer>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val s = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = s.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    "${s.type.uppercase()}  ${s.host}${if (s.port > 0) ":" + s.port else ""}/${s.share}${s.path}"
                return v
            }
        }
        list.adapter = itemAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SERVER_ID, servers[pos].id))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val s = servers[pos]
            AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(arrayOf("수정", "삭제")) { _, which ->
                    if (which == 0) editServer(s) else lifecycleScope.launch { db.servers().delete(s) }
                }
                .show()
            true
        }
        lifecycleScope.launch {
            db.servers().observe().collect {
                servers = it
                itemAdapter.clear()
                itemAdapter.addAll(it)
                empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun editServer(existing: RemoteServer?) {
        val v = layoutInflater.inflate(R.layout.dialog_server, null)
        val smb = v.findViewById<RadioButton>(R.id.typeSmb)
        val ftp = v.findViewById<RadioButton>(R.id.typeFtp)
        val name = v.findViewById<EditText>(R.id.name)
        val host = v.findViewById<EditText>(R.id.host)
        val port = v.findViewById<EditText>(R.id.port)
        val share = v.findViewById<EditText>(R.id.share)
        val path = v.findViewById<EditText>(R.id.path)
        val user = v.findViewById<EditText>(R.id.user)
        val password = v.findViewById<EditText>(R.id.password)
        val domain = v.findViewById<EditText>(R.id.domain)
        fun syncType() {
            val isSmb = smb.isChecked
            share.visibility = if (isSmb) View.VISIBLE else View.GONE
            domain.visibility = if (isSmb) View.VISIBLE else View.GONE
        }
        smb.setOnCheckedChangeListener { _, _ -> syncType() }
        existing?.let {
            if (it.type == "ftp") ftp.isChecked = true else smb.isChecked = true
            name.setText(it.name); host.setText(it.host)
            if (it.port > 0) port.setText(it.port.toString())
            share.setText(it.share); path.setText(it.path); user.setText(it.user)
            password.setText(it.password); domain.setText(it.domain)
        }
        syncType()
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "서버 추가" else "서버 수정")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val h = host.text.toString().trim()
                    .removePrefix("smb://").removePrefix("ftp://").trimEnd('/')
                if (h.isEmpty()) {
                    Util.toast(this, "호스트를 입력하세요")
                    return@setPositiveButton
                }
                val type = if (smb.isChecked) "smb" else "ftp"
                if (type == "smb" && share.text.isBlank()) {
                    Util.toast(this, "SMB 는 공유 폴더 이름이 필요합니다")
                    return@setPositiveButton
                }
                val p = path.text.toString().trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
                val server = RemoteServer(
                    id = existing?.id ?: 0,
                    type = type,
                    name = name.text.toString().trim().ifEmpty { "$type://$h" },
                    host = h,
                    port = port.text.toString().toIntOrNull() ?: 0,
                    share = share.text.toString().trim().trim('/'),
                    path = p,
                    user = user.text.toString().trim(),
                    password = password.text.toString(),
                    domain = domain.text.toString().trim()
                )
                lifecycleScope.launch { db.servers().upsert(server) }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
