package com.everyvideo.player.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
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

    /**
     * 한 동영상의 여러 구간을 순서대로 이어 하나의 동영상으로 저장한다 (구간 여러 개 저장, 구간 삭제 후 남은 부분 저장).
     * 구간이 하나면 [exportClip] 과 같다.
     */
    fun exportRanges(uri: Uri, ranges: List<Pair<Long, Long>>, removeAudio: Boolean, baseName: String, folder: Uri?, cb: Callback) {
        if (ranges.size == 1) {
            exportClip(uri, ranges[0].first, ranges[0].second, removeAudio, baseName, folder, cb)
            return
        }
        fun composition(noAudio: Boolean) = Composition.Builder(
            EditedMediaItemSequence(ranges.map { (s, e) ->
                EditedMediaItem.Builder(clipItem(uri, s, e, false)).setRemoveAudio(noAudio).build()
            })
        ).build()
        val attempts = mutableListOf(Attempt(null, null) { encodeBuilder() to composition(removeAudio) })
        if (!removeAudio) {
            attempts += Attempt("소리 형식을 처리하지 못해 소리 없이 저장하는 중…", "이 동영상의 소리 형식은 저장 기능에서 지원하지 않아 소리 없이 저장했습니다.") {
                encodeBuilder() to composition(true)
            }
        }
        run(attempts, baseName, folder, cb)
    }

    /** 이어붙이기 맨 앞에 넣을 썸네일 이미지와 보여줄 시간. */
    data class Cover(val image: Uri, val durationMs: Long)

    /** 여러 동영상을 순서대로 이어붙인다. 해상도가 다르면 targetHeight 로 맞춘다. cover 가 있으면 맨 앞에 넣는다. */
    fun exportConcat(uris: List<Uri>, targetHeight: Int, baseName: String, folder: Uri?, cover: Cover?, cb: Callback) {
        cancelled = false
        if (cover == null) {
            startConcat(uris, targetHeight, baseName, folder, null, cb)
            return
        }
        cb.onStatus("썸네일 이미지를 준비하는 중…")
        Thread {
            val file = runCatching { prepareCover(cover.image, uris.first(), targetHeight) }.getOrNull()
            handler.post {
                if (cancelled) return@post
                startConcat(uris, targetHeight, baseName, folder, file?.let { it to cover.durationMs }, cb)
            }
        }.start()
    }

    private fun startConcat(uris: List<Uri>, targetHeight: Int, baseName: String, folder: Uri?, cover: Pair<File, Long>?, cb: Callback) {
        fun composition(removeAudio: Boolean, withCover: Boolean): Composition {
            val items = mutableListOf<EditedMediaItem>()
            if (withCover && cover != null) {
                val (file, ms) = cover
                val item = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMimeType(MimeTypes.IMAGE_JPEG)
                    .setImageDurationMs(ms)
                    .build()
                items += EditedMediaItem.Builder(item).setDurationUs(ms * 1000).setFrameRate(30).setRemoveAudio(removeAudio).build()
            }
            uris.forEach { items += EditedMediaItem.Builder(MediaItem.fromUri(it)).setRemoveAudio(removeAudio).build() }
            return Composition.Builder(EditedMediaItemSequence(items))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
                .experimentalSetForceAudioTrack(withCover && cover != null && !removeAudio)
                .build()
        }
        val noAudioNote = "일부 동영상의 소리 형식을 지원하지 않아 소리 없이 저장했습니다."
        val attempts = mutableListOf<Attempt>()
        if (cover != null) {
            attempts += Attempt(null, null) { encodeBuilder() to composition(false, true) }
            attempts += Attempt("소리 없이 다시 시도하는 중…", noAudioNote) { encodeBuilder() to composition(true, true) }
        }
        val coverNote = if (cover != null) "썸네일 이미지를 넣지 못해 빼고 저장했습니다." else null
        attempts += Attempt(if (cover != null) "썸네일 없이 다시 시도하는 중…" else null, coverNote) { encodeBuilder() to composition(false, false) }
        attempts += Attempt("소리 형식을 처리하지 못해 소리 없이 이어붙이는 중…", listOfNotNull(coverNote, noAudioNote).joinToString("\n")) {
            encodeBuilder() to composition(true, false)
        }
        run(attempts, baseName, folder, object : Callback by cb {
            override fun onDone(saved: Uri) { cover?.first?.delete(); cb.onDone(saved) }
            override fun onError(message: String) { cover?.first?.delete(); cb.onError(message) }
        })
    }

    /** 고른 이미지를 첫 동영상의 화면 비율에 맞춰(남는 곳은 검게) JPEG 로 만든다. */
    private fun prepareCover(image: Uri, firstVideo: Uri, targetHeight: Int): File {
        var aspect = 16f / 9f
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, firstVideo)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) aspect = if (rot == 90 || rot == 270) h / w else w / h
            } finally {
                r.release()
            }
        }
        val outH = targetHeight - targetHeight % 2
        var outW = (outH * aspect).toInt()
        outW -= outW % 2
        val src = Thumbnails.decode(context, image, maxOf(outW, outH)) ?: throw IllegalStateException("이미지를 읽지 못했습니다")
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(outW / src.width.toFloat(), outH / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        val dst = RectF((outW - dw) / 2f, (outH - dh) / 2f, (outW + dw) / 2f, (outH + dh) / 2f)
        canvas.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        val file = File(context.cacheDir, "cover_${System.nanoTime()}.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        src.recycle()
        out.recycle()
        return file
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
