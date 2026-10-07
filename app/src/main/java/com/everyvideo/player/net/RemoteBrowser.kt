package com.everyvideo.player.net

import android.net.Uri
import com.everyvideo.player.data.RemoteServer
import com.hierynomus.msfscc.FileAttributes

data class RemoteEntry(val name: String, val isDir: Boolean, val size: Long, val path: String)

object RemoteBrowser {
    /** path 는 서버(SMB 는 공유 폴더) 기준 "/a/b" 형태. 블로킹 호출이므로 IO 스레드에서 부른다. */
    fun list(server: RemoteServer, path: String): List<RemoteEntry> {
        val entries = if (server.type == "smb") listSmb(server, path) else listFtp(server, path)
        return entries
            .filter { !it.name.startsWith(".") && (it.isDir || RemoteUris.isVideoName(it.name)) }
            .sortedWith(compareBy<RemoteEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun child(path: String, name: String) = path.trimEnd('/') + "/" + name

    private fun listFtp(server: RemoteServer, path: String): List<RemoteEntry> {
        val uri: Uri = RemoteUris.build(server, path)
        val c = Ftp.connect(uri)
        try {
            return c.listFiles(path.ifEmpty { "/" }).filterNotNull()
                .filter { it.name != "." && it.name != ".." }
                .map { RemoteEntry(it.name, it.isDirectory, it.size, child(path, it.name)) }
        } finally {
            Ftp.close(c)
        }
    }

    private fun listSmb(server: RemoteServer, path: String): List<RemoteEntry> {
        val t = Smb.open(RemoteUris.build(server, path))
        try {
            return t.share.list(t.path).filter { it.fileName != "." && it.fileName != ".." }.map {
                val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteEntry(it.fileName, dir, it.endOfFile, child(path, it.fileName))
            }
        } finally {
            t.close()
        }
    }
}
