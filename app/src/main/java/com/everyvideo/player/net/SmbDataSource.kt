package com.everyvideo.player.net

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

object Smb {
    val client: SMBClient by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .withReadBufferSize(4 * 1024 * 1024)
                .build()
        )
    }

    class Target(val session: Session, val share: DiskShare, val path: String) {
        fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
        }
    }

    /** smb://host/share/a/b.mkv → 공유 "share", 경로 "a\b.mkv" */
    fun open(uri: Uri): Target {
        val segments = uri.pathSegments
        if (segments.isEmpty()) throw IOException("SMB 주소에 공유 폴더 이름이 없습니다")
        val cred = RemoteUris.credentials(uri)
        val connection = client.connect(uri.host, if (uri.port > 0) uri.port else 445)
        val auth = if (cred.user.isBlank()) AuthenticationContext.anonymous()
        else AuthenticationContext(cred.user, cred.password.toCharArray(), cred.domain.ifBlank { null })
        val session = connection.authenticate(auth)
        val share = session.connectShare(segments[0]) as? DiskShare
            ?: run { session.close(); throw IOException("디스크 공유가 아닙니다: ${segments[0]}") }
        return Target(session, share, segments.drop(1).joinToString("\\"))
    }
}

@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var target: Smb.Target? = null
    private var file: File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    private val chunk = ByteArray(1024 * 1024)
    private var chunkStart = 0L
    private var chunkLen = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val t = Smb.open(dataSpec.uri)
            target = t
            val f = t.share.openFile(
                t.path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            file = f
            val size = f.fileInformation.standardInformation.endOfFile
            position = dataSpec.position
            chunkLen = 0
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        if (position < chunkStart || position >= chunkStart + chunkLen) {
            val n = try {
                file!!.read(chunk, position, 0, chunk.size)
            } catch (e: Exception) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            chunkStart = position
            chunkLen = n
        }
        val inChunk = (position - chunkStart).toInt()
        val n = minOf(length.toLong(), (chunkLen - inChunk).toLong(), bytesRemaining).toInt()
        System.arraycopy(chunk, inChunk, buffer, offset, n)
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { file?.close() }
        target?.close()
        file = null
        target = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
