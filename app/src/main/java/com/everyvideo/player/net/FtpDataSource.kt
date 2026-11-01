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
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

/**
 * FTP 연결.
 *
 * 한국 NAS/공유기 FTP 는 파일 이름을 CP949(EUC-KR) 로 주는 곳이 많다. 그래서 제어 연결은
 * ISO-8859-1 로 열어 바이트를 그대로 받고, 이름과 경로는 여기서 직접 바꾼다.
 * 자동이면 이름 바이트가 올바른 UTF-8 인지 보고, 아니면 CP949 로 읽는다.
 */
object Ftp {
    const val AUTO = "auto"
    const val UTF8 = "utf-8"
    const val CP949 = "cp949"

    private val LATIN1 = Charsets.ISO_8859_1
    val KOREAN: Charset = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR")
        .firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    /** 서버(host:port)별로 알아낸 인코딩. 자동 모드에서 재생할 때도 같은 인코딩을 쓴다. */
    private val detected = ConcurrentHashMap<String, Charset>()

    class Session(val client: FTPClient, val mode: String, private val key: String) {
        var charset: Charset = when (mode) {
            CP949 -> KOREAN
            UTF8 -> Charsets.UTF_8
            else -> detected[key] ?: Charsets.UTF_8
        }
            private set

        /** 유니코드 경로 → 서버로 보낼 문자열(바이트를 ISO-8859-1 로 담은 것). */
        fun encode(path: String, cs: Charset = charset): String = String(path.toByteArray(cs), LATIN1)

        /** 서버가 준 문자열(ISO-8859-1) → 유니코드 이름. */
        fun decode(raw: String): String {
            if (raw.all { it.code < 0x80 }) return raw
            val bytes = raw.toByteArray(LATIN1)
            if (mode == AUTO) return strictUtf8(bytes) ?: String(bytes, KOREAN)
            return String(bytes, charset)
        }

        /** 목록에서 본 이름들로 서버 인코딩을 정한다(자동 모드). */
        fun learn(rawNames: List<String>) {
            if (mode != AUTO) return
            val nonAscii = rawNames.filter { n -> n.any { it.code >= 0x80 } }
            if (nonAscii.isEmpty()) return
            val utf8 = nonAscii.all { strictUtf8(it.toByteArray(LATIN1)) != null }
            charset = if (utf8) Charsets.UTF_8 else KOREAN
            detected[key] = charset
        }

        /**
         * 한글 등 비 ASCII 경로를 서버에서 찾을 수 있는 인코딩으로 바꾼다.
         * 자동 모드에서 아직 모르면 UTF-8, CP949 를 차례로 시험한다.
         */
        fun resolve(path: String, isDir: Boolean): String {
            if (mode != AUTO || path.all { it.code < 0x80 }) return encode(path)
            val candidates = listOf(charset) + listOf(Charsets.UTF_8, KOREAN).filter { it != charset }
            for (cs in candidates) {
                val p = encode(path, cs)
                val found = runCatching {
                    if (isDir) client.changeWorkingDirectory(p)
                    else client.mlistFile(p) != null || client.listFiles(p).isNotEmpty()
                }.getOrDefault(false)
                if (found) {
                    charset = cs
                    detected[key] = cs
                    return p
                }
            }
            return encode(path)
        }
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** 주소의 ?enc= 값(서버 설정). 없으면 자동. */
    fun modeOf(uri: Uri): String = when (uri.getQueryParameter("enc")?.lowercase()) {
        UTF8, "utf8" -> UTF8
        CP949, "euc-kr", "ms949" -> CP949
        else -> AUTO
    }

    fun connect(uri: Uri): Session {
        val cred = RemoteUris.credentials(uri)
        val mode = modeOf(uri)
        val client = FTPClient()
        client.controlEncoding = LATIN1.name()
        client.autodetectUTF8 = false
        client.connectTimeout = 15_000
        client.setDataTimeout(java.time.Duration.ofSeconds(30))
        val port = if (uri.port > 0) uri.port else 21
        client.connect(uri.host, port)
        val ok = if (cred.user.isBlank()) client.login("anonymous", "everyvideo@")
        else client.login(cred.user, cred.password)
        if (!ok) {
            runCatching { client.disconnect() }
            throw IOException("FTP 로그인 실패: ${client.replyString?.trim()}")
        }
        // UTF-8 을 지원하는 서버는 UTF-8 로 바꿔 달라고 한다(지원하지 않으면 무시된다).
        if (mode != CP949) runCatching { client.sendCommand("OPTS", "UTF8 ON") }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.bufferSize = 256 * 1024
        return Session(client, mode, "${uri.host}:$port")
    }

    fun close(session: Session) = close(session.client)

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
            val session = Ftp.connect(dataSpec.uri)
            val c = session.client
            client = c
            val path = session.resolve(dataSpec.uri.path ?: "/", isDir = false)
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
        val stream = input ?: throw DataSourceException(
            IOException("FTP 스트림이 닫혀 있습니다"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        val n = try {
            stream.read(buffer, offset, toRead)
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
        // 데이터 연결을 먼저 닫고 control 연결의 pending 명령을 끝내야
        // 다음 seek에서 421/426 에러 없이 재사용된다.
        runCatching { input?.close() }
        try {
            client?.let {
                if (it.isConnected) {
                    // retrieveFileStream() 뒤에는 completePendingCommand()가 필요하다.
                    runCatching { it.completePendingCommand() }
                }
            }
        } catch (_: Exception) {
        }
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
