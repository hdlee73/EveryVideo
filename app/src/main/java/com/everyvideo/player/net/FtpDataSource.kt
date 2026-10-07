package com.everyvideo.player.net

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.common.PlaybackException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

object Ftp {
    fun connect(uri: Uri): FTPClient {
        val cred = RemoteUris.credentials(uri)
        val client = FTPClient()
        client.controlEncoding = "UTF-8"
        client.connectTimeout = 15_000
        client.setDataTimeout(java.time.Duration.ofSeconds(30))
        client.connect(uri.host, if (uri.port > 0) uri.port else 21)
        val ok = if (cred.user.isBlank()) client.login("anonymous", "everyvideo@")
        else client.login(cred.user, cred.password)
        if (!ok) {
            runCatching { client.disconnect() }
            throw IOException("FTP 로그인 실패: ${client.replyString?.trim()}")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.bufferSize = 256 * 1024
        return client
    }

    fun close(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { client.disconnect() }
    }
}

@UnstableApi
class FtpDataSource : BaseDataSource(true) {
    private var client: FTPClient? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val c = Ftp.connect(dataSpec.uri)
            client = c
            val path = dataSpec.uri.path ?: "/"
            val size = c.mlistFile(path)?.size?.takeIf { it >= 0 }
                ?: c.listFiles(path).firstOrNull()?.size ?: C.LENGTH_UNSET.toLong()
            if (dataSpec.position > 0) c.restartOffset = dataSpec.position
            val stream = c.retrieveFileStream(path)
                ?: throw IOException("FTP 파일을 열 수 없습니다: ${c.replyString?.trim()}")
            input = BufferedInputStream(stream, 512 * 1024)
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                size >= 0 -> size - dataSpec.position
                else -> C.LENGTH_UNSET.toLong()
            }
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            input!!.read(buffer, offset, toRead)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { client?.abort() }
        runCatching { input?.close() }
        client?.let { Ftp.close(it) }
        input = null
        client = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
