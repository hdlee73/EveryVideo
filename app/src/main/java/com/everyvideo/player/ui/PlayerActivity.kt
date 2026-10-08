package com.everyvideo.player.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Bookmark
import com.everyvideo.player.data.Playlist
import com.everyvideo.player.data.PlaylistItem
import com.everyvideo.player.data.Prefs
import com.everyvideo.player.data.Recent
import com.everyvideo.player.media.GifMaker
import com.everyvideo.player.media.MediaStoreSaver
import com.everyvideo.player.media.Thumbnails
import com.everyvideo.player.media.VideoExporter
import com.everyvideo.player.net.MediaSources
import com.everyvideo.player.net.RemoteUris
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_INDEX = "index"
        const val EXTRA_START_MS = "startMs"
        const val EXTRA_PLAYLIST_ID = "playlistId"
        private const val HIDE_DELAY = 4000L
        private const val MAX_ZOOM = 5f
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 16f, 20f)
        /** 이보다 빠르면 안드로이드 소리 처리 한계로 소리를 끄고 화면만 빠르게 재생한다. */
        private const val MAX_AUDIO_SPEED = 8f
        private const val MAX_BRIGHTNESS = 200
    }

    private val db by lazy { (application as App).db }
    private val prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val picker = VideoPicker(this)
    private val folderPicker = FolderPicker(this)
    private lateinit var player: ExoPlayer
    private var previewPlayer: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    private lateinit var exporter: VideoExporter

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var titleText: TextView
    private lateinit var indicator: View
    private lateinit var indicatorIcon: ImageView
    private lateinit var indicatorText: TextView
    private lateinit var indicatorBar: LinearProgressIndicator
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnAb: TextView
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnUnlock: ImageButton
    private lateinit var preview: View
    private lateinit var previewTime: TextView
    private lateinit var zoomChip: TextView
    private lateinit var adjustPanel: View
    private lateinit var brightnessSlider: Slider
    private lateinit var volumeSlider: Slider
    private lateinit var queuePanel: View
    private lateinit var queueList: RecyclerView
    private lateinit var btnSpeedReset: TextView
    private lateinit var sectionPanel: View
    private lateinit var sectionInfo: TextView
    private lateinit var sectionBar: SectionBar
    private lateinit var queueInfo: TextView
    private val queueAdapter = QueueAdapter()

    private var fullscreen = true
    private var locked = false
    private var scrubbing = false
    private var abStart = C.TIME_UNSET
    private var abEnd = C.TIME_UNSET
    private var pendingStartMs = -1L
    private var playlistId = -1L
    private var bookmarks: List<Bookmark> = emptyList()
    private var bookmarkJob: Job? = null
    private var orientationChosen = false
    private var volumeAccumulator = 0f
    private var zoom = 1f
    /** 구간 편집: 저장하거나 지울 구간들 (시작 순으로 정렬). */
    private val sections = mutableListOf<Pair<Long, Long>>()
    private var sectionStart = C.TIME_UNSET

    private val hideControls = Runnable { setControlsVisible(false) }
    private val hideIndicator = Runnable { indicator.visibility = View.GONE }
    private val progressTask = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 200)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        exporter = VideoExporter(this)
        bindViews()
        initPlayer()
        loadIntent(intent)
        applyFullscreen()
        handler.post(progressTask)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveRecent()
        setIntent(intent)
        loadIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        saveRecent()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exporter.cancel()
        previewPlayer?.release()
        player.release()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            setControlsVisible(false)
            preview.visibility = View.INVISIBLE
            queuePanel.visibility = View.GONE
            adjustPanel.visibility = View.GONE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        playerView = findViewById(R.id.playerView)
        controls = findViewById(R.id.controls)
        timeBar = findViewById(R.id.timeBar)
        positionText = findViewById(R.id.position)
        durationText = findViewById(R.id.duration)
        titleText = findViewById(R.id.title)
        indicator = findViewById(R.id.indicator)
        indicatorIcon = findViewById(R.id.indicatorIcon)
        indicatorText = findViewById(R.id.indicatorText)
        indicatorBar = findViewById(R.id.indicatorBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnAb = findViewById(R.id.btnAb)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnUnlock = findViewById(R.id.btnUnlock)
        preview = findViewById(R.id.preview)
        previewTime = findViewById(R.id.previewTime)
        zoomChip = findViewById(R.id.zoomChip)
        adjustPanel = findViewById(R.id.adjustPanel)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        volumeSlider = findViewById(R.id.volumeSlider)
        queuePanel = findViewById(R.id.queuePanel)
        queueList = findViewById(R.id.queueList)
        queueInfo = findViewById(R.id.queueInfo)
        btnSpeedReset = findViewById(R.id.btnSpeedReset)
        sectionPanel = findViewById(R.id.sectionPanel)
        sectionInfo = findViewById(R.id.sectionInfo)
        sectionBar = findViewById(R.id.sectionBar)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnRew).setOnClickListener { seekBy(-10_000) }
        findViewById<View>(R.id.btnFwd).setOnClickListener { seekBy(10_000) }
        findViewById<View>(R.id.btnPrev).setOnClickListener {
            when {
                abActive() -> { player.seekTo(abStart); showIndicator("구간반복 처음으로", R.drawable.ic_repeat) }
                player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
                else -> player.seekTo(0)
            }
        }
        findViewById<View>(R.id.btnNext).setOnClickListener {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else showIndicator("다음 동영상이 없습니다", R.drawable.ic_next)
        }
        btnSpeed.setOnClickListener { showSpeedDialog() }
        btnSpeed.setOnLongClickListener { setSpeed(1f); true }
        btnSpeedReset.setOnClickListener { setSpeed(1f) }
        positionText.setOnClickListener { showJumpDialog() }
        btnAb.setOnClickListener { cycleAbRepeat() }
        btnRepeat.setOnClickListener { cycleRepeatMode() }
        findViewById<View>(R.id.btnTracks).setOnClickListener { showTracksMenu() }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleResizeMode() }
        findViewById<View>(R.id.btnQueue).setOnClickListener { toggleQueue() }
        findViewById<View>(R.id.btnLock).setOnClickListener { setLocked(true) }
        btnUnlock.setOnClickListener { setLocked(false) }
        findViewById<View>(R.id.btnAdjust).setOnClickListener { toggleAdjustPanel() }
        findViewById<View>(R.id.btnCapture).setOnClickListener { captureFrame() }
        findViewById<View>(R.id.btnClip).setOnClickListener { toggleSectionPanel() }
        findViewById<View>(R.id.btnThumb).setOnClickListener { makeThumbnail() }
        findViewById<View>(R.id.sectionClose).setOnClickListener { toggleSectionPanel() }
        findViewById<View>(R.id.secIn).setOnClickListener { markSectionStart() }
        findViewById<View>(R.id.secOut).setOnClickListener { markSectionEnd() }
        findViewById<View>(R.id.secList).setOnClickListener { showSectionList() }
        findViewById<View>(R.id.secSave).setOnClickListener { saveSections() }
        findViewById<View>(R.id.secDelete).setOnClickListener { deleteSections() }
        findViewById<View>(R.id.secGif).setOnClickListener { makeGif() }
        findViewById<View>(R.id.btnBookmarkAdd).setOnClickListener { addBookmark() }
        findViewById<View>(R.id.btnBookmarks).setOnClickListener { showBookmarks() }
        findViewById<View>(R.id.btnRotate).setOnClickListener { rotate() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPip() }
        btnFullscreen.setOnClickListener {
            fullscreen = !fullscreen
            applyFullscreen()
            if (fullscreen && player.videoSize.width > player.videoSize.height) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else if (!fullscreen) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        zoomChip.setOnClickListener { resetZoom() }
        // 화면을 돌리거나 크기가 바뀌면 이동 범위를 다시 맞춘다
        playerView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) clampPan()
        }

        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubbing = true
                handler.removeCallbacks(hideControls)
                showPreview(position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                positionText.text = Util.formatTime(position)
                showPreview(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                hidePreview()
                if (!canceled) {
                    val target = clampToAb(position)
                    if (target != position) showIndicator("구간반복 중에는 구간 안에서만 이동합니다", R.drawable.ic_repeat)
                    player.seekTo(target)
                }
                scheduleHide()
            }
        })

        // 마우스를 시간 막대 위에 올려두기만 해도 해당 시점 미리보기를 보여준다.
        timeBar.setOnHoverListener { v, e ->
            val duration = player.duration
            if (duration <= 0 || scrubbing) return@setOnHoverListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    handler.removeCallbacks(hideControls)
                    showPreview(positionAtX(v, e.x, duration))
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    hidePreview()
                    scheduleHide()
                }
            }
            true
        }

        setupGestures()
        setupAdjustPanel()
        prefs.brightnessLevel?.let { applyBrightness(it) }
        setupQueuePanel()

        root.setOnGenericMotionListener { _, e -> handleGenericMotion(e) }
        findViewById<View>(R.id.gestureLayer).setOnHoverListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE && !locked && controls.visibility != View.VISIBLE) {
                setControlsVisible(true)
            }
            false
        }
    }

    private fun initPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(MediaSources.factory(this))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) scheduleHide() else handler.removeCallbacks(hideControls)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onItemChanged(mediaItem)
                if (!queueDragging) refreshQueue()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !queueDragging) refreshQueue()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!orientationChosen && fullscreen && videoSize.width > 0 && videoSize.height > 0) {
                    orientationChosen = true
                    requestedOrientation = if (videoSize.width >= videoSize.height)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && pendingStartMs >= 0) {
                    player.seekTo(pendingStartMs)
                    pendingStartMs = -1
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppDialog(this@PlayerActivity)
                    .icon(R.drawable.ic_error)
                    .title("재생할 수 없습니다")
                    .message(friendlyError(error))
                    .tertiary(if (player.hasNextMediaItem()) "다음 동영상" else "닫기") {
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem(); player.prepare(); player.play()
                        }
                    }
                    .primary("다시 시도") { player.prepare(); player.play() }
                    .show()
            }
        })
    }

    private fun friendlyError(error: PlaybackException): String {
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "네트워크에 연결하지 못했습니다. 와이파이나 서버 주소를 확인해 주세요."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "파일을 찾을 수 없습니다. 옮겨졌거나 지워졌을 수 있어요."
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "이 파일을 읽을 권한이 없습니다. 파일 열기에서 다시 골라 주세요."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "서버가 파일을 내주지 않았습니다. 공유 링크의 공개 설정을 확인해 주세요."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "이 기기에서 지원하지 않는 영상 코덱입니다."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "파일 형식을 읽지 못했습니다. 파일이 손상되었을 수 있어요."
            else -> "재생 중 문제가 생겼습니다."
        }
        val detail = error.cause?.message ?: error.message
        return if (detail.isNullOrBlank()) reason else "$reason\n\n자세한 내용: $detail"
    }

    private fun loadIntent(intent: Intent) {
        val uris: List<Uri> = intent.getStringArrayListExtra(EXTRA_URIS)?.map(Uri::parse)
            ?: listOfNotNull(intent.data)
        if (uris.isEmpty()) {
            Util.toast(this, "재생할 동영상이 없습니다")
            finish()
            return
        }
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.size - 1)
        pendingStartMs = intent.getLongExtra(EXTRA_START_MS, -1L)
        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L)
        val items = uris.map(::mediaItemOf)
        clearAb()
        resetZoom()
        orientationChosen = false
        previewPlayer?.release()
        previewPlayer = null
        player.setMediaItems(items, index, 0)
        player.prepare()
        player.play()
        onItemChanged(items[index])
        refreshQueue()
    }

    private fun mediaItemOf(uri: Uri): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(Util.displayName(this, uri)).build())
        .build()

    private fun currentUri(): Uri? = player.currentMediaItem?.localConfiguration?.uri

    private fun currentKey(): String? = currentUri()?.let { RemoteUris.keyOf(it) }

    private fun currentTitle(): String =
        player.currentMediaItem?.mediaMetadata?.title?.toString() ?: currentUri()?.let { Util.displayName(this, it) } ?: ""

    private fun onItemChanged(item: MediaItem?) {
        item ?: return
        titleText.text = item.mediaMetadata.title
        clearAb()
        sections.clear()
        sectionStart = C.TIME_UNSET
        if (::sectionBar.isInitialized) updateSections()
        previewPlayer?.let { p ->
            p.setMediaItem(item)
            p.prepare()
        }
        val uri = item.localConfiguration?.uri ?: return
        val key = RemoteUris.keyOf(uri)
        bookmarkJob?.cancel()
        bookmarkJob = lifecycleScope.launch {
            db.bookmarks().observeFor(key).collect {
                bookmarks = it
                updateMarkers()
            }
        }
        if (pendingStartMs < 0) {
            lifecycleScope.launch {
                val recent = db.recents().get(key) ?: return@launch
                if (recent.positionMs > 5_000 && (recent.durationMs <= 0 || recent.positionMs < recent.durationMs - 5_000)) {
                    if (currentKey() == key && player.currentPosition < 3_000) {
                        player.seekTo(recent.positionMs)
                        showIndicator("이어서 재생  ${Util.formatTime(recent.positionMs)}", R.drawable.ic_play)
                    }
                }
            }
        }
    }

    private fun saveRecent() {
        val uri = currentUri() ?: return
        val recent = Recent(
            videoKey = RemoteUris.keyOf(uri),
            videoUri = uri.toString(),
            title = currentTitle(),
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0)
        )
        App.instance.appScope.launch { db.recents().upsert(recent) }
    }

    // ---------------------------------------------------------------- progress / controls

    private fun updateProgress() {
        val duration = player.duration
        val position = player.currentPosition
        if (abStart != C.TIME_UNSET && abEnd != C.TIME_UNSET && position >= abEnd) {
            player.seekTo(abStart)
        }
        if (!scrubbing) {
            positionText.text = Util.formatTime(position)
            timeBar.setPosition(position)
        }
        timeBar.setBufferedPosition(player.bufferedPosition)
        if (duration > 0) {
            timeBar.setDuration(duration)
            durationText.text = Util.formatTime(duration)
            sectionBar.duration = duration
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        if (locked && visible) {
            btnUnlock.visibility = View.VISIBLE
            handler.removeCallbacks(hideControls)
            handler.postDelayed(hideControls, HIDE_DELAY)
            return
        }
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            btnUnlock.visibility = View.GONE
            adjustPanel.visibility = View.GONE
            hidePreview()
        }
        if (visible) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        val busy = scrubbing || adjustPanel.visibility == View.VISIBLE || queuePanel.visibility == View.VISIBLE ||
            sectionPanel.visibility == View.VISIBLE
        if (player.isPlaying && !busy) handler.postDelayed(hideControls, HIDE_DELAY)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(if (abActive()) abStart else 0)
            player.play()
        }
    }

    private fun abActive() = abStart != C.TIME_UNSET && abEnd != C.TIME_UNSET

    /** 구간반복(A-B)이 켜져 있으면 그 안으로 묶는다. */
    private fun clampToAb(target: Long): Long = if (abActive()) target.coerceIn(abStart, abEnd) else target

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = clampToAb((player.currentPosition + deltaMs).coerceIn(0, duration))
        player.seekTo(target)
        showIndicator(
            (if (deltaMs > 0) "+" else "-") + "${abs(deltaMs) / 1000}초   ${Util.formatTime(target)}",
            if (deltaMs > 0) R.drawable.ic_forward10 else R.drawable.ic_replay10
        )
    }

    /** 가운데 둥근 안내. percent 가 있으면 막대도 보여준다. */
    private fun showIndicator(text: String, icon: Int? = null, percent: Int? = null, duration: Long = 900) {
        indicatorText.text = text
        if (icon != null) {
            indicatorIcon.setImageResource(icon)
            indicatorIcon.visibility = View.VISIBLE
        } else indicatorIcon.visibility = View.GONE
        if (percent != null) {
            indicatorBar.visibility = View.VISIBLE
            indicatorBar.progress = percent
        } else indicatorBar.visibility = View.GONE
        indicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicator)
        handler.postDelayed(hideIndicator, duration)
    }

    private fun applyFullscreen() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            btnFullscreen.setImageResource(R.drawable.ic_fullscreen)
        }
        for (v in listOf(controls, queuePanel)) {
            ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(
                    if (view === queuePanel) view.paddingLeft else bars.left, bars.top, bars.right, bars.bottom
                )
                insets
            }
            v.requestApplyInsets()
        }
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        if (lock) {
            controls.visibility = View.GONE
            queuePanel.visibility = View.GONE
            adjustPanel.visibility = View.GONE
            btnUnlock.visibility = View.VISIBLE
            handler.postDelayed(hideControls, HIDE_DELAY)
            showIndicator("화면 잠금", R.drawable.ic_lock)
        } else {
            btnUnlock.visibility = View.GONE
            setControlsVisible(true)
        }
    }

    private fun rotate() {
        orientationChosen = true
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun enterPip() {
        val size = player.videoSize
        var ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
        val value = ratio.toFloat()
        if (value > 2.39f) ratio = Rational(239, 100) else if (value < 1 / 2.39f) ratio = Rational(100, 239)
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        }.onFailure { Util.toast(this, "작은 창 모드를 사용할 수 없습니다") }
    }

    // ---------------------------------------------------------------- gestures (밝기 / 볼륨 / 탐색 / 확대)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val layer = findViewById<View>(R.id.gestureLayer)
        var mode = 0 // 0 없음, 1 밝기, 2 볼륨, 3 탐색, 4 이동(확대 상태)
        var seekStartPos = 0L
        var seekTarget = 0L
        var scaling = false

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (locked) return false
                scaling = true
                mode = 0
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                mode = 0
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    queuePanel.visibility == View.VISIBLE -> toggleQueue()
                    adjustPanel.visibility == View.VISIBLE -> toggleAdjustPanel()
                    locked -> setControlsVisible(true)
                    else -> {
                        // 화면을 한 번 누르면 재생/정지
                        togglePlay()
                        showIndicator(if (player.playWhenReady) "재생" else "일시정지", if (player.playWhenReady) R.drawable.ic_play else R.drawable.ic_pause)
                        setControlsVisible(true)
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = layer.width
                when {
                    e.x < w / 3f -> seekBy(-10_000)
                    e.x > w * 2 / 3f -> seekBy(10_000)
                    // 가운데 두 번 누르기: 조작 버튼 숨기기/보이기
                    else -> setControlsVisible(controls.visibility != View.VISIBLE)
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || scaling || e2.pointerCount > 1) return false
                if (mode == 0) {
                    mode = when {
                        zoom > 1.01f -> 4
                        abs(e2.x - e1.x) > abs(e2.y - e1.y) -> 3
                        e1.x < layer.width / 2f -> 1
                        else -> 2
                    }
                    seekStartPos = player.currentPosition
                }
                when (mode) {
                    1 -> changeBrightness(dy / layer.height)
                    2 -> changeVolume(dy / layer.height)
                    3 -> {
                        val delta = ((e2.x - e1.x) / layer.width * 120_000).toLong()
                        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        seekTarget = clampToAb((seekStartPos + delta).coerceIn(0, duration))
                        showIndicator(
                            Util.formatTime(seekTarget) + "  (" + (if (delta >= 0) "+" else "-") + "${abs(delta) / 1000}초)",
                            if (delta >= 0) R.drawable.ic_forward10 else R.drawable.ic_replay10
                        )
                    }
                    4 -> panBy(-dx, -dy)
                }
                return true
            }
        })

        layer.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            val handled = if (!scaleDetector.isInProgress) detector.onTouchEvent(e) else true
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == 3) player.seekTo(seekTarget)
                mode = 0
                scaling = false
            }
            handled || scaling
        }
    }

    /** 화면 확대. focus 지점을 기준으로 커지고 작아진다. */
    private fun setZoom(newZoom: Float, focusX: Float = playerView.width / 2f, focusY: Float = playerView.height / 2f) {
        val z = newZoom.coerceIn(1f, MAX_ZOOM)
        val factor = z / zoom
        // 초점이 같은 화면 위치에 머물도록 이동량 보정
        val cx = playerView.width / 2f
        val cy = playerView.height / 2f
        playerView.translationX = (playerView.translationX - (focusX - cx)) * factor + (focusX - cx)
        playerView.translationY = (playerView.translationY - (focusY - cy)) * factor + (focusY - cy)
        zoom = z
        playerView.scaleX = z
        playerView.scaleY = z
        clampPan()
        if (z <= 1.01f) {
            zoomChip.visibility = View.GONE
        } else {
            zoomChip.text = "확대 %.1fx   ·   눌러서 원래 크기".format(z)
            zoomChip.visibility = View.VISIBLE
        }
    }

    private fun panBy(dx: Float, dy: Float) {
        playerView.translationX += dx
        playerView.translationY += dy
        clampPan()
    }

    /**
     * 확대한 영상의 가장자리까지 정확히 옮길 수 있게 이동 범위를 제한한다.
     * 영상이 화면보다 작은 방향(위아래 검은 띠 등)은 가운데에 둔다.
     */
    private fun clampPan() {
        val vw = playerView.width.toFloat()
        val vh = playerView.height.toFloat()
        val (cw, ch) = contentSize(vw, vh)
        val maxX = ((cw * zoom - vw) / 2f).coerceAtLeast(0f)
        val maxY = ((ch * zoom - vh) / 2f).coerceAtLeast(0f)
        playerView.translationX = playerView.translationX.coerceIn(-maxX, maxX)
        playerView.translationY = playerView.translationY.coerceIn(-maxY, maxY)
    }

    /** 확대 전 화면 위에서 영상이 실제로 차지하는 크기. */
    private fun contentSize(vw: Float, vh: Float): Pair<Float, Float> {
        val size = player.videoSize
        if (size.width <= 0 || size.height <= 0 || vw <= 0 || vh <= 0) return vw to vh
        val ratio = size.width * size.pixelWidthHeightRatio / size.height
        return when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> if (vw / vh > ratio) (vh * ratio) to vh else vw to (vw / ratio)
            // 화면 채우기·늘리기는 영상이 화면 전체를 덮는다
            else -> vw to vh
        }
    }

    private fun resetZoom() {
        zoom = 1f
        if (!::playerView.isInitialized) return
        playerView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(180).start()
        zoomChip.visibility = View.GONE
    }

    /**
     * 밝기 단계: -50 ~ 200.
     *  0~100  화면 밝기(감마 곡선, 100 이면 기기 최대 밝기)
     *  100~200 기기 최대 밝기에서 영상 자체를 더 밝게 (색 보정 필터로 최대 2.2배)
     *  0 미만  화면을 최저로 둔 채 검은 막을 덧씌워 더 어둡게
     */
    private var brightnessLevel: Int? = null

    private fun systemBrightnessLevel(): Int {
        val raw = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return (Math.pow(raw / 255.0, 1 / 2.2) * 100).toInt().coerceIn(0, 100)
    }

    private fun currentBrightnessLevel(): Int = brightnessLevel ?: systemBrightnessLevel()

    private fun applyBrightness(level: Int?) {
        brightnessLevel = level
        val lp = window.attributes
        val dim = findViewById<View>(R.id.dimOverlay)
        var boost = 0f
        if (level == null) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            dim.alpha = 0f
        } else {
            val l = level.coerceIn(-50, MAX_BRIGHTNESS)
            lp.screenBrightness = when {
                l <= 0 -> 0.004f
                l >= 100 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                else -> Math.pow(l / 100.0, 2.2).toFloat().coerceAtLeast(0.004f)
            }
            dim.alpha = if (l < 0) -l / 50f * 0.8f else 0f
            if (l > 100) boost = (l - 100) / 100f
        }
        window.attributes = lp
        applyVideoBoost(boost)
        prefs.brightnessLevel = level
    }

    /** 영상 화면(TextureView)에 색 보정을 걸어 기기 최대 밝기보다 더 밝아 보이게 한다. boost 0~1. */
    private fun applyVideoBoost(boost: Float) {
        val texture = playerView.videoSurfaceView as? TextureView ?: return
        if (boost <= 0f) {
            texture.setLayerPaint(null)
            return
        }
        val gain = 1f + boost * 1.2f          // 밝은 부분까지 함께 키움
        val lift = boost * 28f                // 어두운 부분을 끌어올림
        val m = ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, lift,
            0f, gain, 0f, 0f, lift,
            0f, 0f, gain, 0f, lift,
            0f, 0f, 0f, 1f, 0f
        ))
        texture.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    }

    private fun brightnessLabel(level: Int) = when {
        level < 0 -> "밝기 0% · 더 어둡게 ${-level * 2}%"
        level > 100 -> "최대 밝기 · 영상 더 밝게 +${level - 100}%"
        else -> "밝기 $level%"
    }

    private var brightnessAccumulator = 0f

    private fun changeBrightness(delta: Float) {
        // 화면 높이의 절반만 밀어도 끝에서 끝까지 (250단계)
        brightnessAccumulator += delta * 500f
        val steps = brightnessAccumulator.toInt()
        if (steps != 0) {
            brightnessAccumulator -= steps
            applyBrightness((currentBrightnessLevel() + steps).coerceIn(-50, MAX_BRIGHTNESS))
        }
        val level = currentBrightnessLevel()
        showIndicator(brightnessLabel(level), R.drawable.ic_brightness, ((level + 50) * 100 / (MAX_BRIGHTNESS + 50)))
        syncAdjustPanel()
    }

    private fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun changeVolume(delta: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeAccumulator += delta * max * 1.5f
        val steps = volumeAccumulator.toInt()
        if (steps != 0) {
            volumeAccumulator -= steps
            val v = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        }
        val pct = volumePercent()
        showIndicator("볼륨 $pct%", R.drawable.ic_volume, pct)
        syncAdjustPanel()
    }

    private fun handleGenericMotion(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                if (e.metaState and KeyEvent.META_CTRL_ON != 0) {
                    // Ctrl + 휠: 마우스 위치를 중심으로 확대/축소
                    setZoom(zoom * (if (v > 0) 1.15f else 1 / 1.15f), e.x, e.y)
                } else {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, if (v > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0
                    )
                    val pct = volumePercent()
                    showIndicator("볼륨 $pct%", R.drawable.ic_volume, pct)
                    syncAdjustPanel()
                }
                return true
            }
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !locked) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (controls.visibility != View.VISIBLE) { seekBy(-10_000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (controls.visibility != View.VISIBLE) { seekBy(10_000); return true }
                KeyEvent.KEYCODE_F -> { btnFullscreen.performClick(); return true }
                KeyEvent.KEYCODE_B -> { addBookmark(); return true }
                KeyEvent.KEYCODE_L -> { toggleQueue(); return true }
                KeyEvent.KEYCODE_0 -> { resetZoom(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- 밝기 / 볼륨 패널

    private fun setupAdjustPanel() {
        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) applyBrightness(value.toInt())
            val v = value.toInt()
            findViewById<TextView>(R.id.brightnessValue).text = when {
                v < 0 -> "어둡게"
                v > 100 -> "+${v - 100}%"
                else -> "$v%"
            }
        }
        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (value / 100f * max).toInt(), 0)
            }
            findViewById<TextView>(R.id.volumeValue).text = "${value.toInt()}%"
        }
        findViewById<View>(R.id.brightnessAuto).setOnClickListener {
            applyBrightness(null)
            syncAdjustPanel()
            showIndicator("밝기: 시스템 설정", R.drawable.ic_brightness)
        }
    }

    private fun syncAdjustPanel() {
        brightnessSlider.value = currentBrightnessLevel().coerceIn(-50, MAX_BRIGHTNESS).toFloat()
        volumeSlider.value = volumePercent().coerceIn(0, 100).toFloat()
    }

    private fun toggleAdjustPanel() {
        if (adjustPanel.visibility == View.VISIBLE) {
            adjustPanel.visibility = View.GONE
        } else {
            syncAdjustPanel()
            adjustPanel.visibility = View.VISIBLE
        }
        scheduleHide()
    }

    // ---------------------------------------------------------------- 오른쪽 재생목록 패널

    private var queueDragging = false
    private val queueReorder = DragReorder(
        onMove = { f, t -> queueDragging = true; player.moveMediaItem(f, t) },
        onDrop = { _, _ -> queueDragging = false; refreshQueue(); syncPlaylist() }
    )

    private fun setupQueuePanel() {
        queueList.layoutManager = LinearLayoutManager(this)
        queueList.adapter = queueAdapter
        queueReorder.attach(queueList)
        findViewById<View>(R.id.queueClose).setOnClickListener { toggleQueue() }
        findViewById<View>(R.id.queueAdd).setOnClickListener {
            picker.chooseAndPick("재생목록에 추가") { uris ->
                player.addMediaItems(uris.map(::mediaItemOf))
                showIndicator("${uris.size}개 추가", R.drawable.ic_add)
                syncPlaylist()
            }
        }
        findViewById<View>(R.id.queueSave).setOnClickListener { saveQueue() }
        findViewById<View>(R.id.queueSort).setOnClickListener { sortQueue() }
    }

    private fun toggleQueue() {
        val show = queuePanel.visibility != View.VISIBLE
        if (show) {
            refreshQueue()
            queuePanel.visibility = View.VISIBLE
            queuePanel.translationX = queuePanel.width.toFloat().takeIf { it > 0 } ?: (340 * resources.displayMetrics.density)
            queuePanel.animate().translationX(0f).setDuration(200).start()
            (queueList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset((player.currentMediaItemIndex - 2).coerceAtLeast(0), 0)
            setControlsVisible(false)
        } else {
            queuePanel.animate().translationX(queuePanel.width.toFloat()).setDuration(180)
                .withEndAction { queuePanel.visibility = View.GONE }.start()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshQueue() {
        if (!::queueInfo.isInitialized) return
        queueInfo.text = "${player.mediaItemCount}개 · 손잡이를 끌어 순서 변경 · X는 목록에서만 빼기"
        queueAdapter.notifyDataSetChanged()
    }

    private fun removeFromQueue(pos: Int) {
        if (pos !in 0 until player.mediaItemCount) return
        if (player.mediaItemCount <= 1) {
            Util.toast(this, "마지막 동영상은 뺄 수 없습니다")
            return
        }
        val title = player.getMediaItemAt(pos).mediaMetadata.title
        player.removeMediaItem(pos)
        refreshQueue()
        syncPlaylist()
        showIndicator("'$title' 뺌", R.drawable.ic_delete)
    }

    private fun sortQueue() {
        if (player.mediaItemCount < 2) return
        Sorting.choose(this) { order ->
            lifecycleScope.launch {
                val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                val sorted = Sorting.sort(
                    this@PlayerActivity, items, order,
                    { it.localConfiguration?.uri ?: Uri.parse(it.mediaId) }, { it.mediaMetadata.title?.toString() ?: "" }
                )
                // 정렬된 순서대로 하나씩 제자리로 옮긴다 (재생은 끊기지 않음)
                sorted.forEachIndexed { target, item ->
                    val from = (target until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId } ?: return@forEachIndexed
                    if (from != target) player.moveMediaItem(from, target)
                }
                refreshQueue()
                syncPlaylist()
                showIndicator(order.label, R.drawable.ic_sort)
            }
        }
    }

    /** 저장된 재생목록을 재생 중이면, 패널에서 바꾼 순서·삭제를 그 재생목록에도 반영한다. */
    private fun syncPlaylist() {
        val id = playlistId
        if (id < 0) return
        App.instance.appScope.launch { writeQueue(id) }
    }

    private fun queueUris(): List<Pair<String, String>> = (0 until player.mediaItemCount).map { i ->
        val it = player.getMediaItemAt(i)
        (it.localConfiguration?.uri?.toString() ?: it.mediaId) to (it.mediaMetadata.title?.toString() ?: "")
    }

    private fun saveQueue() {
        lifecycleScope.launch {
            val lists = db.playlists().all()
            val items = mutableListOf(AppDialog.Companion.Item("새 재생목록으로 저장", icon = R.drawable.ic_add))
            lists.forEach { p ->
                items += AppDialog.Companion.Item(
                    p.name, if (p.id == playlistId) "지금 재생 중인 목록 · 이 내용으로 바꾸기" else "이 내용으로 바꾸기", R.drawable.ic_playlist
                )
            }
            AppDialog.choice(this@PlayerActivity, "재생목록 저장", items, message = "지금 목록 ${player.mediaItemCount}개를 저장합니다.") { which ->
                if (which == 0) {
                    AppDialog.input(this@PlayerActivity, "새 재생목록", "이름", currentTitle().substringBeforeLast('.'), "저장", R.drawable.ic_playlist) { name ->
                        if (name.isEmpty()) return@input
                        lifecycleScope.launch {
                            val id = db.playlists().insert(Playlist(name = name))
                            writeQueue(id)
                            playlistId = id
                            showIndicator("'$name' 저장", R.drawable.ic_check)
                        }
                    }
                } else {
                    val p = lists[which - 1]
                    lifecycleScope.launch {
                        writeQueue(p.id)
                        playlistId = p.id
                        showIndicator("'${p.name}' 저장", R.drawable.ic_check)
                    }
                }
            }
        }
    }

    private suspend fun writeQueue(id: Long) {
        val dao = db.playlists()
        dao.clearItems(id)
        dao.insertItems(queueUris().mapIndexed { i, (uri, title) -> PlaylistItem(playlistId = id, uri = uri, title = title, sort = i) })
    }

    private class QueueHolder(v: View) : RecyclerView.ViewHolder(v)

    private inner class QueueAdapter : RecyclerView.Adapter<QueueHolder>() {
        override fun getItemCount() = if (::player.isInitialized) player.mediaItemCount else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            QueueHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false))

        override fun onBindViewHolder(holder: QueueHolder, position: Int) {
            val v = holder.itemView
            val current = position == player.currentMediaItemIndex
            v.isSelected = current
            v.findViewById<TextView>(R.id.num).apply {
                text = (position + 1).toString()
                visibility = if (current) View.GONE else View.VISIBLE
            }
            v.findViewById<View>(R.id.playing).visibility = if (current) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.name).text = player.getMediaItemAt(position).mediaMetadata.title
            v.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos >= 0) { player.seekTo(pos, 0); player.play() }
            }
            v.findViewById<View>(R.id.delete).setOnClickListener { removeFromQueue(holder.bindingAdapterPosition) }
            queueReorder.bindHandle(v.findViewById(R.id.drag), holder)
        }
    }

    // ---------------------------------------------------------------- seek preview (작은 화면 미리보기)

    private fun positionAtX(v: View, x: Float, duration: Long): Long {
        val inset = sectionBar.inset
        val left = v.paddingLeft + inset
        val right = v.width - v.paddingRight - inset
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return (fraction * duration).toLong()
    }

    private fun ensurePreviewPlayer(): ExoPlayer {
        previewPlayer?.let { return it }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(MediaSources.factory(this))
            .build()
        p.volume = 0f
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.setVideoTextureView(findViewById<TextureView>(R.id.previewTexture))
        player.currentMediaItem?.let { p.setMediaItem(it) }
        p.playWhenReady = false
        p.prepare()
        previewPlayer = p
        return p
    }

    private fun showPreview(position: Long) {
        val duration = player.duration
        if (duration <= 0 || isInPictureInPictureMode) return
        ensurePreviewPlayer().seekTo(position)
        previewTime.text = Util.formatTime(position)
        preview.visibility = View.VISIBLE

        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        timeBar.getLocationInWindow(loc)
        root.getLocationInWindow(rootLoc)
        val inset = sectionBar.inset
        val barLeft = loc[0] - rootLoc[0] + timeBar.paddingLeft + inset
        val barWidth = timeBar.width - timeBar.paddingLeft - timeBar.paddingRight - 2 * inset
        val centerX = barLeft + barWidth * (position.toFloat() / duration)
        val w = preview.width.takeIf { it > 0 } ?: (196 * resources.displayMetrics.density).toInt()
        preview.translationX = (centerX - w / 2f).coerceIn(0f, (root.width - w).toFloat().coerceAtLeast(0f))
        val barTop = loc[1] - rootLoc[1]
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        val margin = (root.height - barTop + (4 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            preview.layoutParams = lp
        }
    }

    private fun hidePreview() {
        preview.visibility = View.INVISIBLE
    }

    // ---------------------------------------------------------------- 속도 / 구간반복 / 반복 / 트랙 / 비율

    private fun speedText(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

    private fun showSpeedDialog() {
        val current = SPEEDS.indexOfFirst { it == player.playbackParameters.speed }
        AppDialog.choice(
            this, "재생 속도",
            SPEEDS.map {
                AppDialog.Companion.Item(
                    if (it == 1f) "1x (원래 속도)" else speedText(it),
                    if (it > MAX_AUDIO_SPEED) "소리 없이 화면만 빠르게" else null
                )
            },
            current,
            message = "원래 속도로 돌아가려면 위쪽 '1x로'를 누르거나 속도 버튼을 길게 누르세요. 구간 저장은 언제나 원래 속도로 저장됩니다."
        ) { which -> setSpeed(SPEEDS[which]) }
    }

    /** 8배를 넘으면 소리를 끄고(안드로이드 소리 처리 한계) 화면만 그 속도로 재생한다. */
    private fun setSpeed(speed: Float) {
        val muteAudio = speed > MAX_AUDIO_SPEED
        val params = player.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) != muteAudio) {
            player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muteAudio).build()
        }
        player.setPlaybackSpeed(speed)
        btnSpeed.text = speedText(speed)
        btnSpeedReset.visibility = if (speed != 1f) View.VISIBLE else View.GONE
        showIndicator(
            if (speed == 1f) "원래 속도 (1x)" else "재생 속도 ${speedText(speed)}" + if (muteAudio) " · 소리 끔" else "",
            R.drawable.ic_speed
        )
    }

    private fun showJumpDialog() {
        if (player.duration <= 0) return
        AppDialog.input(this, "시간으로 이동", "예: 1:23:45, 12:30, 90", Util.formatTime(player.currentPosition), "이동", R.drawable.ic_forward10) { text ->
            val t = Util.parseTime(text) ?: run { Util.toast(this, "시간 형식을 확인해 주세요"); return@input }
            player.seekTo(clampToAb(t.coerceIn(0, player.duration)))
        }
    }

    private fun cycleAbRepeat() {
        when {
            abStart == C.TIME_UNSET -> {
                abStart = player.currentPosition
                showIndicator("A 지점  ${Util.formatTime(abStart)}", R.drawable.ic_repeat)
            }
            abEnd == C.TIME_UNSET -> {
                val pos = player.currentPosition
                if (pos <= abStart + 500) {
                    showIndicator("B 지점은 A 지점보다 뒤여야 합니다", R.drawable.ic_info)
                    return
                }
                abEnd = pos
                player.seekTo(abStart)
                showIndicator("구간반복  ${Util.formatTime(abStart)} ~ ${Util.formatTime(abEnd)} · 이 안에서만 이동", R.drawable.ic_repeat)
            }
            else -> {
                clearAb()
                showIndicator("구간반복 해제", R.drawable.ic_repeat)
            }
        }
        updateAbButton()
    }

    private fun clearAb() {
        abStart = C.TIME_UNSET
        abEnd = C.TIME_UNSET
        if (::btnAb.isInitialized) updateAbButton()
    }

    private fun updateAbButton() {
        sectionBar.abRange = if (abActive()) abStart to abEnd else null
        btnAb.text = when {
            abStart == C.TIME_UNSET -> "A-B"
            abEnd == C.TIME_UNSET -> "A-?"
            else -> "A↔B"
        }
        btnAb.setTextColor(
            if (abStart == C.TIME_UNSET) 0xFFFFFFFF.toInt() else ContextCompat.getColor(this, R.color.accent)
        )
    }

    private fun cycleRepeatMode() {
        val next = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        btnRepeat.setImageResource(if (next == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        btnRepeat.alpha = if (next == Player.REPEAT_MODE_OFF) 0.5f else 1f
        showIndicator(
            when (next) {
                Player.REPEAT_MODE_ONE -> "한 개 반복"
                Player.REPEAT_MODE_ALL -> "전체 반복"
                else -> "반복 끔"
            },
            if (next == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
    }

    private fun showTracksMenu() {
        AppDialog.choice(
            this, "자막 · 음성",
            listOf(
                AppDialog.Companion.Item("음성 트랙", icon = R.drawable.ic_volume),
                AppDialog.Companion.Item("자막", icon = R.drawable.ic_tracks),
                AppDialog.Companion.Item("영상 트랙", icon = R.drawable.ic_movie)
            )
        ) { which ->
            val type = when (which) {
                0 -> C.TRACK_TYPE_AUDIO
                1 -> C.TRACK_TYPE_TEXT
                else -> C.TRACK_TYPE_VIDEO
            }
            showTrackChooser(type, listOf("음성 트랙", "자막", "영상 트랙")[which])
        }
    }

    private fun showTrackChooser(type: Int, title: String) {
        val names = DefaultTrackNameProvider(resources)
        val options = mutableListOf<Pair<String, (() -> Unit)>>()
        var checked = -1
        val params = player.trackSelectionParameters
        if (type == C.TRACK_TYPE_TEXT) {
            if (params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) checked = 0
            options += "끄기" to {
                player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
        }
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                if (group.isTrackSelected(i) && checked < 0) checked = options.size
                val label = names.getTrackName(group.getTrackFormat(i))
                options += label to {
                    player.trackSelectionParameters = params.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        if (options.isEmpty() || (type == C.TRACK_TYPE_TEXT && options.size == 1)) {
            AppDialog.info(this, title, "이 동영상에는 고를 수 있는 ${title}이 없습니다.", R.drawable.ic_tracks)
            return
        }
        AppDialog.choice(this, title, options.map { AppDialog.Companion.Item(it.first) }, checked) { which ->
            options[which].second()
        }
    }

    private fun cycleResizeMode() {
        val (next, label) = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "화면 채우기 (잘림)"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL to "늘려서 채우기"
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT to "원본 비율"
        }
        playerView.resizeMode = next
        showIndicator(label, R.drawable.ic_aspect)
    }

    // ---------------------------------------------------------------- 캡쳐 / 구간 저장

    private fun baseName(prefix: String): String {
        val title = currentTitle().substringBeforeLast('.').take(40)
        val time = Util.formatTime(player.currentPosition).replace(':', '-')
        return "${prefix}_${title}_$time".replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /** 저장 대화상자에서 고른 값. */
    private class SaveChoice(val name: String, val folder: Uri?, val range: Pair<Long, Long>?, val removeAudio: Boolean, val caption: String)

    /**
     * 파일 이름 + 저장 위치(+ 구간, 소리 빼기, 썸네일 글자) 입력창. 위치를 바꾸면 설정에도 저장된다.
     * 설정에서 '저장할 때 이름과 위치 묻기'를 끄면 묻지 않고 바로 onSave 를 부른다 (썸네일 글자는 예외).
     */
    private fun showSaveDialog(
        title: String, icon: Int, defaultName: String, image: Boolean,
        range: Pair<Long, Long>? = null, audioOption: Boolean = range != null, caption: Boolean = false,
        message: String? = null, onSave: (SaveChoice) -> Unit
    ) {
        if (!prefs.askOnCapture && !caption) {
            onSave(SaveChoice(defaultName, if (image) prefs.imageFolder else prefs.videoFolder, range, false, ""))
            return
        }
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_save, null)
        val name = v.findViewById<EditText>(R.id.name)
        val folderText = v.findViewById<TextView>(R.id.folder)
        val removeAudio = v.findViewById<MaterialSwitch>(R.id.removeAudio)
        val start = v.findViewById<EditText>(R.id.start)
        val end = v.findViewById<EditText>(R.id.end)
        val captionEdit = v.findViewById<EditText>(R.id.caption)
        var folder: Uri? = if (image) prefs.imageFolder else prefs.videoFolder
        val defaultLabel = if (image) MediaStoreSaver.DEFAULT_IMAGE_LABEL else MediaStoreSaver.DEFAULT_VIDEO_LABEL
        name.setText(defaultName)
        folderText.text = prefs.folderLabel(folder, defaultLabel)
        v.findViewById<View>(R.id.folderRow).setOnClickListener {
            folderPicker.pick { uri ->
                folder = uri
                if (image) prefs.imageFolder = uri else prefs.videoFolder = uri
                folderText.text = prefs.folderLabel(uri, defaultLabel)
            }
        }
        if (range != null) {
            v.findViewById<View>(R.id.rangeBox).visibility = View.VISIBLE
            start.setText(Util.formatTime(range.first))
            end.setText(Util.formatTime(range.second))
        }
        if (audioOption) removeAudio.visibility = View.VISIBLE
        if (caption) v.findViewById<View>(R.id.captionLayout).visibility = View.VISIBLE
        lateinit var dialog: AppDialog
        dialog = AppDialog(this)
            .icon(icon)
            .title(title)
            .apply { message?.let { message(it) } }
            .content(v)
            .secondary("취소")
            .keepOpenOnPrimary()
            .primary("저장") {
                var r: Pair<Long, Long>? = null
                if (range != null) {
                    val s = Util.parseTime(start.text.toString())
                    val e = Util.parseTime(end.text.toString())
                    if (s == null || e == null || s < 0 || e <= s) {
                        Util.toast(this, "시작과 끝 시간을 확인해 주세요 (예: 01:30)")
                        return@primary
                    }
                    r = s to e
                }
                dialog.dismiss()
                onSave(SaveChoice(name.text.toString().ifBlank { defaultName }, folder, r, removeAudio.isChecked, captionEdit.text.toString()))
            }
            .show()
    }

    private fun currentFrame(): Bitmap? {
        val texture = playerView.videoSurfaceView as? TextureView
        if (player.videoSize.width == 0) return null
        return texture?.bitmap
    }

    private fun captureFrame() {
        val bitmap = currentFrame() ?: run { showIndicator("캡쳐할 화면이 없습니다", R.drawable.ic_camera); return }
        val wasPlaying = player.isPlaying
        if (prefs.askOnCapture) player.pause()
        showSaveDialog("화면 캡쳐", R.drawable.ic_camera, baseName("capture"), image = true) { c ->
            saveBitmap(bitmap, c.name, c.folder, "캡쳐 저장")
            if (wasPlaying) player.play()
        }
    }

    /** 지금 장면으로 썸네일(가로 1280 JPEG)을 만든다. 글자를 넣을 수 있다. */
    private fun makeThumbnail() {
        val frame = currentFrame() ?: run { showIndicator("썸네일로 만들 화면이 없습니다", R.drawable.ic_image); return }
        val wasPlaying = player.isPlaying
        player.pause()
        showSaveDialog(
            "썸네일 만들기", R.drawable.ic_image, baseName("thumb"), image = true, caption = true,
            message = "지금 장면을 썸네일 이미지로 저장합니다. 이어붙이기에서 '썸네일'로 골라 동영상 맨 앞에 넣을 수 있어요."
        ) { c ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val img = Thumbnails.render(frame, c.caption)
                        MediaStoreSaver.saveImage(this@PlayerActivity, img, c.name, c.folder, jpeg = true).also { img.recycle() }
                    }
                }
                result.onSuccess { showIndicator("썸네일 저장  ·  " + prefs.folderLabel(c.folder, MediaStoreSaver.DEFAULT_IMAGE_LABEL), R.drawable.ic_check, duration = 2500) }
                    .onFailure { AppDialog.info(this@PlayerActivity, "썸네일을 만들지 못했습니다", it.message ?: "", R.drawable.ic_error) }
                if (wasPlaying) player.play()
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String, folder: Uri?, done: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaStoreSaver.saveImage(this@PlayerActivity, bitmap, name, folder) }
            }
            result.onSuccess {
                showIndicator("$done  ·  " + prefs.folderLabel(folder, MediaStoreSaver.DEFAULT_IMAGE_LABEL), R.drawable.ic_check, duration = 2000)
            }.onFailure { AppDialog.info(this@PlayerActivity, "저장하지 못했습니다", it.message ?: "", R.drawable.ic_error) }
        }
    }

    // ---------------------------------------------------------------- 구간 편집 (여러 구간 저장 / 삭제 / GIF)

    private fun toggleSectionPanel() {
        if (sectionPanel.visibility == View.VISIBLE) {
            sectionPanel.visibility = View.GONE
        } else {
            if (player.duration <= 0) {
                AppDialog.info(this, "구간 편집", "길이를 알 수 없는 동영상(실시간 방송 등)은 구간을 저장할 수 없습니다.", R.drawable.ic_cut)
                return
            }
            sectionPanel.visibility = View.VISIBLE
            updateSections()
            setControlsVisible(true)
        }
        scheduleHide()
    }

    private fun updateSections() {
        sectionBar.sections = sections.toList()
        sectionBar.pendingStart = if (sectionStart != C.TIME_UNSET) sectionStart else -1
        sectionInfo.text = when {
            sectionStart != C.TIME_UNSET -> "시작 ${Util.formatTime(sectionStart)} · 끝낼 곳에서 '끝'을 누르세요"
            sections.isEmpty() && abActive() -> "구간을 고르지 않으면 구간반복(A-B) ${Util.formatTime(abStart)} ~ ${Util.formatTime(abEnd)}을 씁니다"
            sections.isEmpty() -> "원하는 곳에서 '시작'과 '끝'을 눌러 구간을 고르세요. 여러 개 고를 수 있어요."
            else -> "구간 ${sections.size}개 · 합계 ${Util.formatTime(sections.sumOf { it.second - it.first })}"
        }
    }

    private fun markSectionStart() {
        sectionStart = player.currentPosition
        updateSections()
        showIndicator("시작  ${Util.formatTime(sectionStart)}", R.drawable.ic_mark_in)
    }

    private fun markSectionEnd() {
        val pos = player.currentPosition
        val start = sectionStart
        if (start == C.TIME_UNSET) {
            showIndicator("먼저 '시작'을 누르세요", R.drawable.ic_info)
            return
        }
        if (pos <= start + 300) {
            showIndicator("끝은 시작보다 뒤여야 합니다", R.drawable.ic_info)
            return
        }
        sections += start to pos
        sectionStart = C.TIME_UNSET
        mergeSections()
        updateSections()
        showIndicator("구간 추가  ${Util.formatTime(start)} ~ ${Util.formatTime(pos)}", R.drawable.ic_mark_out)
    }

    /** 겹치는 구간은 하나로 합치고 시작 순으로 정렬. */
    private fun mergeSections() {
        val sorted = sections.sortedBy { it.first }
        val merged = mutableListOf<Pair<Long, Long>>()
        for (r in sorted) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, r.second)
            else merged += r
        }
        sections.clear()
        sections.addAll(merged)
    }

    private fun showSectionList() {
        if (sections.isEmpty()) {
            showIndicator("고른 구간이 없습니다", R.drawable.ic_list)
            return
        }
        val items = sections.mapIndexed { i, (s, e) ->
            AppDialog.Companion.Item("${i + 1}.  ${Util.formatTime(s)} ~ ${Util.formatTime(e)}", "길이 ${Util.formatTime(e - s)}", R.drawable.ic_cut)
        } + AppDialog.Companion.Item("모두 지우기", null, R.drawable.ic_delete)
        AppDialog.choice(
            this, "고른 구간", items, message = "누르면 그 구간으로 이동, 길게 누르면 목록에서 지웁니다.",
            onLongPick = { which ->
                if (which < sections.size) {
                    sections.removeAt(which); updateSections()
                }
            }
        ) { which ->
            if (which < sections.size) player.seekTo(sections[which].first)
            else {
                sections.clear(); sectionStart = C.TIME_UNSET; updateSections()
            }
        }
    }

    /** 작업 대상 구간: 고른 구간들, 없으면 구간반복(A-B). */
    private fun targetRanges(): List<Pair<Long, Long>>? {
        if (sections.isNotEmpty()) return sections.toList()
        if (abActive()) return listOf(abStart to abEnd)
        showIndicator("먼저 '시작'과 '끝'으로 구간을 고르세요", R.drawable.ic_info)
        return null
    }

    private class ExportJob(val name: String, val ranges: List<Pair<Long, Long>>)

    private fun saveSections() {
        val ranges = targetRanges() ?: return
        player.pause()
        val go = { separate: Boolean ->
            val single = if (ranges.size == 1) ranges[0] else null
            showSaveDialog(
                "구간 저장", R.drawable.ic_cut, baseName("clip"), image = false, range = single, audioOption = true,
                message = if (ranges.size > 1) (if (separate) "구간 ${ranges.size}개를 각각 파일로 저장합니다 (이름 뒤에 _1, _2 …)." else "구간 ${ranges.size}개를 순서대로 이어 한 파일로 저장합니다.")
                else "재생 속도와 상관없이 원래 속도로 저장됩니다."
            ) { c ->
                val rs = c.range?.let { listOf(it) } ?: ranges
                val jobs = if (separate && rs.size > 1) rs.mapIndexed { i, r -> ExportJob("${c.name}_${i + 1}", listOf(r)) }
                else listOf(ExportJob(c.name, rs))
                runExports("구간 저장 중", jobs, c.removeAudio, c.folder)
            }
        }
        if (ranges.size == 1) go(false)
        else AppDialog.choice(
            this, "구간 ${ranges.size}개 저장",
            listOf(
                AppDialog.Companion.Item("하나로 이어서 저장", "고른 구간들을 차례로 붙여 한 파일로", R.drawable.ic_merge),
                AppDialog.Companion.Item("구간마다 따로 저장", "구간 수만큼 파일을 만듭니다", R.drawable.ic_cut)
            )
        ) { which -> go(which == 1) }
    }

    private fun deleteSections() {
        val ranges = targetRanges() ?: return
        val duration = player.duration
        val keep = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for ((s, e) in ranges.sortedBy { it.first }) {
            if (s - cursor > 300) keep += cursor to s
            cursor = maxOf(cursor, e)
        }
        if (duration - cursor > 300) keep += cursor to duration
        if (keep.isEmpty()) {
            AppDialog.info(this, "구간 삭제", "고른 구간이 동영상 전체라서 남는 부분이 없습니다.", R.drawable.ic_delete)
            return
        }
        player.pause()
        val removed = ranges.sumOf { it.second - it.first }
        showSaveDialog(
            "구간 삭제", R.drawable.ic_delete, baseName("cut"), image = false, audioOption = true,
            message = "고른 구간 ${ranges.size}개(합계 ${Util.formatTime(removed)})를 뺀 나머지를 새 동영상으로 저장합니다. 원본 파일은 그대로 둡니다."
        ) { c -> runExports("구간 삭제 중", listOf(ExportJob(c.name, keep)), c.removeAudio, c.folder) }
    }

    /** 내보내기를 차례로 실행. 끝나면 별도 창 없이 안내만 잠깐 보여준다. */
    private fun runExports(title: String, jobs: List<ExportJob>, removeAudio: Boolean, folder: Uri?) {
        val uri = currentUri() ?: return
        val duration = player.duration
        val label = prefs.folderLabel(folder, MediaStoreSaver.DEFAULT_VIDEO_LABEL)
        val p = AppDialog.progress(this, title, "저장 위치: $label") { exporter.cancel() }
        val notes = mutableSetOf<String>()

        fun run(i: Int) {
            val job = jobs[i]
            if (jobs.size > 1) p.dialog.title("$title (${i + 1}/${jobs.size})")
            p.set(0)
            val ranges = job.ranges.map { (s, e) -> s to e.coerceAtMost(duration) }
            exporter.exportRanges(uri, ranges, removeAudio, job.name, folder, object : VideoExporter.Callback {
                override fun onProgress(percent: Int) = p.set(percent)

                override fun onStatus(message: String) {
                    p.dialog.message(message)
                    p.set(0)
                }

                override fun onDone(saved: Uri) {
                    exporter.resultNote?.let { notes += it }
                    if (i + 1 < jobs.size) {
                        run(i + 1)
                        return
                    }
                    p.dialog.dismiss()
                    val count = if (jobs.size > 1) "${jobs.size}개 " else ""
                    showIndicator("${count}저장했습니다  ·  $label", R.drawable.ic_check, duration = 2500)
                    if (notes.isNotEmpty()) Util.toast(this@PlayerActivity, notes.joinToString(" "))
                }

                override fun onError(message: String) {
                    p.dialog.dismiss()
                    AppDialog.info(this@PlayerActivity, "저장하지 못했습니다", message, R.drawable.ic_error)
                }
            })
        }
        run(0)
    }

    /** 고른 구간(마지막 것) 또는 구간반복을 GIF 로. 없으면 지금부터 5초. */
    private fun makeGif() {
        val uri = currentUri() ?: return
        val duration = player.duration
        if (duration <= 0) return
        var range = sections.lastOrNull() ?: (if (abActive()) abStart to abEnd else null)
            ?: (player.currentPosition to (player.currentPosition + 5_000).coerceAtMost(duration))
        var note = ""
        if (range.second - range.first > GifMaker.MAX_SECONDS * 1000L) {
            range = range.first to range.first + GifMaker.MAX_SECONDS * 1000L
            note = "GIF는 최대 ${GifMaker.MAX_SECONDS}초까지 만들 수 있어 앞부분만 사용합니다.\n"
        }
        player.pause()
        AppDialog.choice(
            this, "GIF 만들기",
            GifMaker.QUALITIES.map { AppDialog.Companion.Item(it.label) }, checked = 1,
            message = note + "${Util.formatTime(range.first)} ~ ${Util.formatTime(range.second)} 구간 (소리 없음). 크게 할수록 파일이 커지고 오래 걸립니다."
        ) { which ->
            val q = GifMaker.QUALITIES[which]
            showSaveDialog("GIF 저장", R.drawable.ic_gif, baseName("gif"), image = true, range = range, audioOption = false) { c ->
                val r = c.range ?: range
                val (s, e) = r.first to minOf(r.second, r.first + GifMaker.MAX_SECONDS * 1000L, duration)
                var cancelled = false
                val p = AppDialog.progress(this, "GIF 만드는 중", "${Util.formatTime(s)} ~ ${Util.formatTime(e)}") { cancelled = true }
                p.dialog.icon(R.drawable.ic_gif)
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching {
                            val file = GifMaker(this@PlayerActivity).make(uri, s, e, q, { cancelled }) { pct ->
                                runOnUiThread { p.set(pct) }
                            }
                            try {
                                MediaStoreSaver.saveGifFile(this@PlayerActivity, file, c.name, c.folder)
                            } finally {
                                file.delete()
                            }
                        }
                    }
                    p.dialog.dismiss()
                    if (cancelled) return@launch
                    result.onSuccess {
                        showIndicator("GIF 저장  ·  " + prefs.folderLabel(c.folder, MediaStoreSaver.DEFAULT_IMAGE_LABEL), R.drawable.ic_check, duration = 2500)
                    }.onFailure {
                        AppDialog.info(this@PlayerActivity, "GIF를 만들지 못했습니다", it.message ?: it.javaClass.simpleName, R.drawable.ic_error)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 즐겨찾기

    private fun updateMarkers() {
        val times = bookmarks.map { it.positionMs }.toLongArray()
        timeBar.setAdGroupTimesMs(times, BooleanArray(times.size), times.size)
    }

    private fun addBookmark() {
        val uri = currentUri() ?: return
        val pos = player.currentPosition
        AppDialog.input(this, "즐겨찾기 추가  ·  ${Util.formatTime(pos)}", "메모 (선택)", ok = "추가", icon = R.drawable.ic_bookmark_add) { label ->
            lifecycleScope.launch {
                db.bookmarks().insert(
                    Bookmark(
                        videoKey = RemoteUris.keyOf(uri), videoUri = uri.toString(), videoTitle = currentTitle(),
                        positionMs = pos, label = label
                    )
                )
                showIndicator("즐겨찾기 추가  ${Util.formatTime(pos)}", R.drawable.ic_bookmark_add)
            }
        }
    }

    private fun showBookmarks() {
        val list = bookmarks
        if (list.isEmpty()) {
            AppDialog.info(this, "즐겨찾기", "이 동영상에 저장한 즐겨찾기가 없습니다.\n북마크+ 버튼을 누르면 지금 시점이 저장됩니다.", R.drawable.ic_bookmarks)
            return
        }
        AppDialog.choice(
            this, "즐겨찾기",
            list.map { AppDialog.Companion.Item(Util.formatTime(it.positionMs), it.label.ifEmpty { null }, R.drawable.ic_bookmarks) },
            message = "누르면 이동, 길게 누르면 삭제",
            onLongPick = { which ->
                val b = list[which]
                AppDialog.confirm(this, "즐겨찾기 삭제", "${Util.formatTime(b.positionMs)} 즐겨찾기를 삭제할까요?", "삭제", R.drawable.ic_delete, true) {
                    lifecycleScope.launch { db.bookmarks().delete(b) }
                }
            }
        ) { which ->
            player.seekTo(list[which].positionMs)
            player.play()
        }
    }
}
