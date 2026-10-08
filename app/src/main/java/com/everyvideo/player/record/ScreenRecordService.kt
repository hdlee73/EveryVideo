package com.everyvideo.player.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Prefs
import com.everyvideo.player.media.MediaStoreSaver

/** MediaProjection 으로 화면을 MP4 로 녹화하는 포그라운드 서비스. */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIC = "mic"
        private const val NOTIFICATION_ID = 42

        @Volatile
        var isRecording = false
            private set
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: MediaStoreSaver.Target? = null
    private var outputFd: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRecording) start(intent)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_RECORD)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(getString(R.string.recording))
            .setContentText("눌러서 녹화를 끝낼 수 있습니다")
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(R.drawable.ic_record, getString(R.string.stop), stop)
            .build()
    }

    private fun start(intent: Intent) {
        val withMic = intent.getBooleanExtra(EXTRA_MIC, false)
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (withMic && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        // Android 14 부터 getMediaProjection 전에 포그라운드 서비스가 시작되어 있어야 한다.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            stopSelf(); return
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("화면 녹화 권한을 받지 못했습니다")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, null)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            var width = metrics.widthPixels
            var height = metrics.heightPixels
            val maxSide = 1920
            val scale = maxSide.toFloat() / maxOf(width, height)
            if (scale < 1f) {
                width = (width * scale).toInt()
                height = (height * scale).toInt()
            }
            width -= width % 2
            height -= height % 2

            val target = MediaStoreSaver.createVideo(this, "screen_${MediaStoreSaver.stamp()}")
            output = target
            val fd = contentResolver.openFileDescriptor(target.uri, "w") ?: throw IllegalStateException("파일을 만들 수 없습니다")
            outputFd = fd

            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            if (withMic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(fd.fileDescriptor)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(8_000_000)
            if (withMic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.prepare()
            recorder = rec

            display = proj.createVirtualDisplay(
                "EveryVideoRecord", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null
            )
            rec.start()
            isRecording = true
            Toast.makeText(this, "화면 녹화를 시작합니다", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "녹화를 시작할 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
            cleanup(success = false)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording && recorder == null) {
            stopSelf(); return
        }
        var ok = true
        try {
            recorder?.stop()
        } catch (e: Exception) {
            ok = false
        }
        cleanup(ok)
        Toast.makeText(
            this, if (ok) "녹화 저장: " + Prefs(this).folderLabel(Prefs(this).videoFolder, MediaStoreSaver.DEFAULT_VIDEO_LABEL) else "녹화가 너무 짧아 저장하지 못했습니다",
            Toast.LENGTH_LONG
        ).show()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup(success: Boolean) {
        isRecording = false
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        val proj = projection
        projection = null
        runCatching { proj?.stop() }
        runCatching { outputFd?.close() }
        outputFd = null
        output?.let { t -> if (success) t.finish(this) else t.discard(this) }
        output = null
    }

    override fun onDestroy() {
        if (isRecording) stopRecording()
        super.onDestroy()
    }
}
