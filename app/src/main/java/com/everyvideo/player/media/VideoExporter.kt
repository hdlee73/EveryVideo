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

        /** 다른 방법으로 다시 시도할 때 안내 문구. */
        fun onStatus(message: String) {}
    }

    /** 한 번의 내보내기 시도. 실패하면 다음 시도로 넘어간다. */
    private class Attempt(val status: String?, val note: String?, val build: () -> Pair<Transformer.Builder, Any>)

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressTask: Runnable? = null
    private var cancelled = false

    /** 성공했지만 사용자에게 알려야 할 점 (예: 소리를 빼고 저장함). */
    var resultNote: String? = null
        private set

    private fun assetLoader() = DefaultAssetLoaderFactory(
        context,
        DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
        Clock.DEFAULT,
        MediaSources.factory(context),
        DataSourceBitmapLoader(context)
    )

    /** 다시 인코딩하지 않고 그대로 옮겨 담는 빠른 방식 (형식이 맞으면 화질 손실 없음). */
    private fun copyBuilder(): Transformer.Builder = Transformer.Builder(context)
        .setAssetLoaderFactory(assetLoader())
        .setMaxDelayBetweenMuxerSamplesMs(MAX_MUXER_DELAY_MS)

    /** H.264 / AAC 로 다시 인코딩하는 방식. 느리지만 대부분의 입력에서 동작한다. */
    private fun encodeBuilder(): Transformer.Builder = copyBuilder()
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)

    private fun run(attempts: List<Attempt>, baseName: String, folder: Uri?, cb: Callback) {
        cancelled = false
        resultNote = null
        val errors = mutableListOf<String>()

        fun start(i: Int) {
            val attempt = attempts[i]
            attempt.status?.let { if (i > 0) cb.onStatus(it) }
            val out = tempFile()
            val (builder, input) = attempt.build()
            val t = builder.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopProgress()
                    cb.onProgress(100)
                    resultNote = attempt.note
                    Thread {
                        try {
                            val uri = MediaStoreSaver.saveVideoFile(context, out, baseName, folder)
                            handler.post { cb.onDone(uri) }
                        } catch (e: Exception) {
                            handler.post { cb.onError(e.message ?: "파일을 저장하지 못했습니다") }
                        } finally {
                            out.delete()
                        }
                    }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopProgress()
                    out.delete()
                    if (cancelled) return
                    errors += exportException.errorCodeName + ": " + (exportException.cause?.message ?: exportException.message)
                    if (i + 1 < attempts.size) {
                        start(i + 1)
                    } else {
                        cb.onError(
                            "이 동영상은 형식(코덱) 때문에 저장 기능으로 처리하지 못했습니다.\n\n자세한 내용:\n" + errors.joinToString("\n")
                        )
                    }
                }
            }).build()
            transformer = t
            when (input) {
                is EditedMediaItem -> t.start(input, out.absolutePath)
                is Composition -> t.start(input, out.absolutePath)
            }
            startProgress(t, cb)
        }
        start(0)
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

    private fun clipItem(uri: Uri, startMs: Long, endMs: Long, keyFrameStart: Boolean) = MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .setStartsAtKeyFrame(keyFrameStart)
                .build()
        )
        .build()

    /**
     * 한 동영상에서 [startMs, endMs] 구간만 새 동영상으로 저장한다.
     * 1) 그대로 옮겨 담기(시작은 가장 가까운 앞쪽 키프레임) → 2) H.264 로 다시 인코딩 → 3) 소리 빼고 다시 인코딩 순으로 시도한다.
     */
    fun exportClip(uri: Uri, startMs: Long, endMs: Long, removeAudio: Boolean, baseName: String, folder: Uri?, cb: Callback) {
        val attempts = mutableListOf(
            Attempt(null, null) {
                copyBuilder() to EditedMediaItem.Builder(clipItem(uri, startMs, endMs, true)).setRemoveAudio(removeAudio).build()
            },
            Attempt("다른 방식(다시 인코딩)으로 저장하는 중…", null) {
                encodeBuilder() to EditedMediaItem.Builder(clipItem(uri, startMs, endMs, false)).setRemoveAudio(removeAudio).build()
            }
        )
        if (!removeAudio) {
            attempts += Attempt("소리 형식을 처리하지 못해 소리 없이 저장하는 중…", "이 동영상의 소리 형식은 저장 기능에서 지원하지 않아 소리 없이 저장했습니다.") {
                encodeBuilder() to EditedMediaItem.Builder(clipItem(uri, startMs, endMs, false)).setRemoveAudio(true).build()
            }
        }
        run(attempts, baseName, folder, cb)
    }

    /** 여러 동영상을 순서대로 이어붙인다. 해상도가 다르면 targetHeight 로 맞춘다. */
    fun exportConcat(uris: List<Uri>, targetHeight: Int, baseName: String, folder: Uri?, cb: Callback) {
        fun composition(removeAudio: Boolean) = Composition.Builder(
            EditedMediaItemSequence(uris.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).setRemoveAudio(removeAudio).build() })
        ).setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight)))).build()
        run(
            listOf(
                Attempt(null, null) { encodeBuilder() to composition(false) },
                Attempt("소리 형식을 처리하지 못해 소리 없이 이어붙이는 중…", "일부 동영상의 소리 형식을 지원하지 않아 소리 없이 저장했습니다.") {
                    encodeBuilder() to composition(true)
                }
            ),
            baseName, folder, cb
        )
    }

    fun cancel() {
        cancelled = true
        stopProgress()
        transformer?.cancel()
        transformer = null
    }

    companion object {
        /** 앞부분을 건너뛰느라 오래 아무것도 안 써질 수 있어 기본 10초보다 넉넉하게 둔다. */
        private const val MAX_MUXER_DELAY_MS = 60_000L
    }
}
