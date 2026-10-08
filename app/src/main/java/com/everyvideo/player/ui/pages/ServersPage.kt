package com.everyvideo.player.ui.pages

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.RemoteServer
import com.everyvideo.player.ui.AppDialog
import com.everyvideo.player.ui.Util
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch

class ServersPage : ListPage() {
    private val dao by lazy { (requireActivity().application as App).db.servers() }

    override fun setup() {
        setTitle("FTP / SMB 서버", "같은 네트워크의 PC 공유 폴더나 FTP 서버의 동영상을 바로 재생합니다")
        addAction(R.drawable.ic_add, "서버 추가", primary = true) { edit(null) }
        setEmpty(R.drawable.ic_server, "등록된 서버가 없습니다", "'서버 추가'를 눌러 PC 공유 폴더(SMB)나 FTP 서버를 등록하세요.", "서버 추가") { edit(null) }
        viewLifecycleOwner.lifecycleScope.launch {
            dao.observe().collect { list ->
                setRows(list.map { s ->
                    Row(
                        title = s.name,
                        subtitle = "${s.type.uppercase()} · ${s.host}${if (s.port > 0) ":" + s.port else ""}/${s.share}${s.path}",
                        icon = R.drawable.ic_server,
                        onMore = { menu(s) },
                        onClick = { main.showFragment(BrowsePage.of(s.id)) }
                    )
                })
            }
        }
    }

    private fun menu(s: RemoteServer) {
        AppDialog.choice(
            requireContext(), s.name,
            listOf(
                AppDialog.Companion.Item("수정", icon = R.drawable.ic_edit),
                AppDialog.Companion.Item("삭제", icon = R.drawable.ic_delete)
            )
        ) { which ->
            if (which == 0) edit(s) else AppDialog.confirm(
                requireContext(), "서버 삭제", "'${s.name}' 서버를 목록에서 지울까요?", "삭제", R.drawable.ic_delete, true
            ) { lifecycleScope.launch { dao.delete(s) } }
        }
    }

    private fun edit(existing: RemoteServer?) {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_server, null)
        val type = v.findViewById<MaterialButtonToggleGroup>(R.id.type)
        fun text(id: Int) = v.findViewById<EditText>(id)
        val shareLayout = v.findViewById<View>(R.id.shareLayout)
        val domainLayout = v.findViewById<View>(R.id.domainLayout)
        fun sync() {
            val smb = type.checkedButtonId == R.id.typeSmb
            shareLayout.visibility = if (smb) View.VISIBLE else View.GONE
            domainLayout.visibility = if (smb) View.VISIBLE else View.GONE
        }
        type.addOnButtonCheckedListener { _, _, _ -> sync() }
        existing?.let {
            type.check(if (it.type == "ftp") R.id.typeFtp else R.id.typeSmb)
            text(R.id.name).setText(it.name); text(R.id.host).setText(it.host)
            if (it.port > 0) text(R.id.port).setText(it.port.toString())
            text(R.id.share).setText(it.share); text(R.id.path).setText(it.path)
            text(R.id.user).setText(it.user); text(R.id.password).setText(it.password)
            text(R.id.domain).setText(it.domain)
        }
        sync()
        lateinit var dialog: AppDialog
        dialog = AppDialog(requireContext())
            .icon(R.drawable.ic_server)
            .title(if (existing == null) "서버 추가" else "서버 수정")
            .content(v)
            .secondary("취소")
            .keepOpenOnPrimary()
            .primary("저장") {
                val host = text(R.id.host).text.toString().trim().removePrefix("smb://").removePrefix("ftp://").trimEnd('/')
                val t = if (type.checkedButtonId == R.id.typeFtp) "ftp" else "smb"
                when {
                    host.isEmpty() -> Util.toast(requireContext(), "주소를 입력하세요")
                    t == "smb" && text(R.id.share).text.isBlank() -> Util.toast(requireContext(), "SMB 는 공유 폴더 이름이 필요합니다")
                    else -> {
                        val p = text(R.id.path).text.toString().trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
                        val server = RemoteServer(
                            id = existing?.id ?: 0, type = t,
                            name = text(R.id.name).text.toString().trim().ifEmpty { "$t://$host" },
                            host = host, port = text(R.id.port).text.toString().toIntOrNull() ?: 0,
                            share = text(R.id.share).text.toString().trim().trim('/'), path = p,
                            user = text(R.id.user).text.toString().trim(), password = text(R.id.password).text.toString(),
                            domain = text(R.id.domain).text.toString().trim()
                        )
                        lifecycleScope.launch { dao.upsert(server) }
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }
}
