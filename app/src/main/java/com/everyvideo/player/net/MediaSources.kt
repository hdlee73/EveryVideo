package com.everyvideo.player.net

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** 주소의 스킴에 따라 FTP / SMB / 그 외(파일, content, http 등) 로 나눠 읽는 DataSource. */
@UnstableApi
class RoutingDataSource(private val context: Context) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds: DataSource = when (dataSpec.uri.scheme?.lowercase()) {
            "ftp" -> FtpDataSource()
            "smb" -> SmbDataSource()
            else -> DefaultDataSource(
                context,
                DefaultHttpDataSource.Factory()
                    .setUserAgent("EveryVideo/1.0 (Android)")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                    .createDataSource()
            )
        }
        listeners.forEach { ds.addTransferListener(it) }
        current = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context.applicationContext)
    }
}

@UnstableApi
object MediaSources {
    fun extractors(): DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)

    fun factory(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(RoutingDataSource.Factory(context), extractors())
}
