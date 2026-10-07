package com.everyvideo.player.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.everyvideo.player.net.MediaSources
import java.io.File

/**
 * Media3 Transformer 로 구간 잘라내기와 이어붙이기를 한다.
 * 모든 메서드는 메인 스레드에서 호출해야 한다.
 */
@UnstableApi
class VideoExporter(private val context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onDone(saved: Uri)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressTask: Runnable? = null

    private fun buildTransformer(cb: Callback, out: File, baseName: String): Transformer {
        val assetLoader = DefaultAssetLoaderFactory(
            context,
            DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
            Clock.DEFAULT,
            MediaSources.factory(context),
            DataSourceBitmapLoader(context)
        )
        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setAssetLoaderFactory(assetLoader)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopProgress()
                    cb.onProgress(100)
                    Thread {
                        try {
                            val uri = MediaStoreSaver.saveVideoFile(context, out, baseName)
                            handler.post { cb.onDone(uri) }
                        } catch (e: Exception) {
                            handler.post { cb.onError(e.message ?: "저장 실패") }
                        } finally {
                            out.delete()
                        }
                    }.start()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    stopProgress()
                    out.delete()
                    cb.onError(exportException.errorCodeName + ": " + (exportException.cause?.message ?: exportException.message))
                }
            })
            .build()
    }

    private fun startProgress(t: Transformer, cb: Callback) {
        val holder = ProgressHolder()
        val task = object : Runnable {
            override fun run() {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) cb.onProgress(holder.progress)
                handler.postDelayed(this, 300)
            }
        }
        progressTask = task
        handler.post(task)
    }

    private fun stopProgress() {
        progressTask?.let { handler.removeCallbacks(it) }
        progressTask = null
    }

    private fun tempFile() = File(context.cacheDir, "export_${System.nanoTime()}.mp4")

    /** 한 동영상에서 [startMs, endMs] 구간만 새 동영상으로 저장한다. */
    fun exportClip(uri: Uri, startMs: Long, endMs: Long, removeAudio: Boolean, baseName: String, cb: Callback) {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
        val edited = EditedMediaItem.Builder(item).setRemoveAudio(removeAudio).build()
        val out = tempFile()
        val t = buildTransformer(cb, out, baseName)
        transformer = t
        t.start(edited, out.absolutePath)
        startProgress(t, cb)
    }

    /** 여러 동영상을 순서대로 이어붙인다. 해상도가 다르면 targetHeight 로 맞춘다. */
    fun exportConcat(uris: List<Uri>, targetHeight: Int, baseName: String, cb: Callback) {
        val items = uris.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val composition = Composition.Builder(EditedMediaItemSequence(items))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
            .build()
        val out = tempFile()
        val t = buildTransformer(cb, out, baseName)
        transformer = t
        t.start(composition, out.absolutePath)
        startProgress(t, cb)
    }

    fun cancel() {
        stopProgress()
        transformer?.cancel()
        transformer = null
    }
}
