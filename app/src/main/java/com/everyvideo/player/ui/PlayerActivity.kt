package com.everyvideo.player.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.everyvideo.player.App
import com.everyvideo.player.R
import com.everyvideo.player.data.Bookmark
import com.everyvideo.player.data.Recent
import com.everyvideo.player.media.MediaStoreSaver
import com.everyvideo.player.media.VideoExporter
import com.everyvideo.player.net.MediaSources
import com.everyvideo.player.net.RemoteUris
import com.google.android.material.progressindicator.LinearProgressIndicator
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
        private const val HIDE_DELAY = 4000L
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
    }

    private val db by lazy { (application as App).db }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private var previewPlayer: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    private lateinit var exporter: VideoExporter

    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var titleText: TextView
    private lateinit var indicator: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnAb: TextView
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnUnlock: ImageButton
    private lateinit var preview: View
    private lateinit var previewTime: TextView

    private var fullscreen = true
    private var locked = false
    private var scrubbing = false
    private var abStart = C.TIME_UNSET
    private var abEnd = C.TIME_UNSET
    private var pendingStartMs = -1L
    private var bookmarks: List<Bookmark> = emptyList()
    private var bookmarkJob: Job? = null
    private var orientationChosen = false
    private var volumeAccumulator = 0f

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
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        controls = findViewById(R.id.controls)
        timeBar = findViewById(R.id.timeBar)
        positionText = findViewById(R.id.position)
        durationText = findViewById(R.id.duration)
        titleText = findViewById(R.id.title)
        indicator = findViewById(R.id.indicator)
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnAb = findViewById(R.id.btnAb)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnUnlock = findViewById(R.id.btnUnlock)
        preview = findViewById(R.id.preview)
        previewTime = findViewById(R.id.previewTime)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnRew).setOnClickListener { seekBy(-10_000) }
        findViewById<View>(R.id.btnFwd).setOnClickListener { seekBy(10_000) }
        findViewById<View>(R.id.btnPrev).setOnClickListener {
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
        }
        findViewById<View>(R.id.btnNext).setOnClickListener {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else Util.toast(this, "다음 동영상이 없습니다")
        }
        btnSpeed.setOnClickListener { showSpeedDialog() }
        btnAb.setOnClickListener { cycleAbRepeat() }
        btnRepeat.setOnClickListener { cycleRepeatMode() }
        findViewById<View>(R.id.btnTracks).setOnClickListener { showTracksMenu(it) }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleResizeMode() }
        findViewById<View>(R.id.btnLock).setOnClickListener { setLocked(true) }
        btnUnlock.setOnClickListener { setLocked(false) }
        findViewById<View>(R.id.btnCapture).setOnClickListener { captureFrame() }
        findViewById<View>(R.id.btnClip).setOnClickListener { showClipDialog() }
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
                if (!canceled) player.seekTo(position)
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

        // 마우스가 화면 위에서 움직이면 컨트롤을 보여준다.
        findViewById<View>(R.id.root).setOnGenericMotionListener { _, e -> handleGenericMotion(e) }
        findViewById<View>(R.id.gestureLayer).setOnHoverListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE && !locked) setControlsVisible(true)
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
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("재생할 수 없습니다")
                    .setMessage("${error.errorCodeName}\n${error.cause?.message ?: error.message ?: ""}")
                    .setPositiveButton("다시 시도") { _, _ -> player.prepare(); player.play() }
                    .setNegativeButton("닫기", null)
                    .show()
            }
        })
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
        val items = uris.map { uri ->
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(uri.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(Util.displayName(this, uri)).build())
                .build()
        }
        clearAb()
        orientationChosen = false
        previewPlayer?.release()
        previewPlayer = null
        player.setMediaItems(items, index, 0)
        player.prepare()
        player.play()
        onItemChanged(items[index])
    }

    private fun currentUri(): Uri? = player.currentMediaItem?.localConfiguration?.uri

    private fun currentKey(): String? = currentUri()?.let { RemoteUris.keyOf(it) }

    private fun onItemChanged(item: MediaItem?) {
        item ?: return
        titleText.text = item.mediaMetadata.title
        clearAb()
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
                        showIndicator("이어서 재생 ${Util.formatTime(recent.positionMs)}")
                    }
                }
            }
        }
    }

    private fun saveRecent() {
        val uri = currentUri() ?: return
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: Util.displayName(this, uri)
        val recent = Recent(
            videoKey = RemoteUris.keyOf(uri),
            videoUri = uri.toString(),
            title = title,
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
            hidePreview()
        }
        if (visible) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player.isPlaying && !scrubbing) handler.postDelayed(hideControls, HIDE_DELAY)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0, duration)
        player.seekTo(target)
        showIndicator((if (deltaMs > 0) "+" else "-") + "${abs(deltaMs) / 1000}초  ${Util.formatTime(target)}")
    }

    private fun showIndicator(text: String) {
        indicator.text = text
        indicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicator)
        handler.postDelayed(hideIndicator, 900)
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
        val root = findViewById<View>(R.id.controls)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        if (lock) {
            controls.visibility = View.GONE
            btnUnlock.visibility = View.VISIBLE
            handler.postDelayed(hideControls, HIDE_DELAY)
            Util.toast(this, "화면이 잠겼습니다")
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

    // ---------------------------------------------------------------- gestures (밝기 / 볼륨 / 탐색)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val layer = findViewById<View>(R.id.gestureLayer)
        var mode = 0 // 0 없음, 1 밝기, 2 볼륨, 3 탐색
        var seekStartPos = 0L
        var seekTarget = 0L
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                mode = 0
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    setControlsVisible(true)
                } else {
                    setControlsVisible(controls.visibility != View.VISIBLE)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = layer.width
                when {
                    e.x < w / 3f -> seekBy(-10_000)
                    e.x > w * 2 / 3f -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null) return false
                if (mode == 0) {
                    mode = if (abs(e2.x - e1.x) > abs(e2.y - e1.y)) 3
                    else if (e1.x < layer.width / 2f) 1 else 2
                    seekStartPos = player.currentPosition
                }
                when (mode) {
                    1 -> changeBrightness(dy / layer.height)
                    2 -> changeVolume(dy / layer.height)
                    3 -> {
                        val delta = ((e2.x - e1.x) / layer.width * 120_000).toLong()
                        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        seekTarget = (seekStartPos + delta).coerceIn(0, duration)
                        showIndicator(Util.formatTime(seekTarget) + "  (" + (if (delta >= 0) "+" else "-") + "${abs(delta) / 1000}초)")
                    }
                }
                return true
            }
        })
        layer.setOnTouchListener { _, e ->
            val handled = detector.onTouchEvent(e)
            if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && mode == 3) {
                player.seekTo(seekTarget)
                mode = 0
            }
            handled
        }
    }

    private fun changeBrightness(delta: Float) {
        val lp = window.attributes
        var current = lp.screenBrightness
        if (current < 0) {
            current = runCatching {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)
        }
        lp.screenBrightness = (current + delta * 1.5f).coerceIn(0.01f, 1f)
        window.attributes = lp
        showIndicator("밝기 ${(lp.screenBrightness * 100).toInt()}%")
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
        val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        showIndicator("볼륨 ${now * 100 / max}%")
    }

    private fun handleGenericMotion(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (v > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0
                )
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                showIndicator("볼륨 ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%")
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
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- seek preview (작은 화면 미리보기)

    private fun positionAtX(v: View, x: Float, duration: Long): Long {
        val inset = 8 * resources.displayMetrics.density
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

        val rootView = findViewById<View>(R.id.root)
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        timeBar.getLocationInWindow(loc)
        rootView.getLocationInWindow(rootLoc)
        val inset = 8 * resources.displayMetrics.density
        val barLeft = loc[0] - rootLoc[0] + timeBar.paddingLeft + inset
        val barWidth = timeBar.width - timeBar.paddingLeft - timeBar.paddingRight - 2 * inset
        val centerX = barLeft + barWidth * (position.toFloat() / duration)
        val w = preview.width.takeIf { it > 0 } ?: (196 * resources.displayMetrics.density).toInt()
        preview.translationX = (centerX - w / 2f).coerceIn(0f, (rootView.width - w).toFloat().coerceAtLeast(0f))
        val bottomOfRoot = rootView.height
        val barTop = loc[1] - rootLoc[1]
        val lp = preview.layoutParams as android.widget.FrameLayout.LayoutParams
        val margin = (bottomOfRoot - barTop + (4 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            preview.layoutParams = lp
        }
    }

    private fun hidePreview() {
        preview.visibility = View.INVISIBLE
    }

    // ---------------------------------------------------------------- 속도 / 구간반복 / 반복 / 트랙 / 비율

    private fun showSpeedDialog() {
        val labels = SPEEDS.map { "${it}x" }.toTypedArray()
        val current = SPEEDS.indexOfFirst { it == player.playbackParameters.speed }
        AlertDialog.Builder(this)
            .setTitle("재생 속도")
            .setSingleChoiceItems(labels, current) { d, which ->
                player.setPlaybackSpeed(SPEEDS[which])
                btnSpeed.text = labels[which]
                d.dismiss()
            }
            .show()
    }

    private fun cycleAbRepeat() {
        when {
            abStart == C.TIME_UNSET -> {
                abStart = player.currentPosition
                showIndicator("A 지점 ${Util.formatTime(abStart)}")
            }
            abEnd == C.TIME_UNSET -> {
                val pos = player.currentPosition
                if (pos <= abStart + 500) {
                    Util.toast(this, "B 지점은 A 지점보다 뒤여야 합니다")
                    return
                }
                abEnd = pos
                player.seekTo(abStart)
                showIndicator("구간반복 ${Util.formatTime(abStart)} ~ ${Util.formatTime(abEnd)}")
            }
            else -> {
                clearAb()
                showIndicator("구간반복 해제")
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
                Player.REPEAT_MODE_ONE -> "한 곡 반복"
                Player.REPEAT_MODE_ALL -> "전체 반복"
                else -> "반복 끔"
            }
        )
    }

    private fun showTracksMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "음성 트랙")
        menu.menu.add(0, 2, 1, "자막")
        menu.menu.add(0, 3, 2, "영상 트랙")
        menu.setOnMenuItemClickListener {
            val (type, title) = when (it.itemId) {
                1 -> C.TRACK_TYPE_AUDIO to "음성 트랙"
                2 -> C.TRACK_TYPE_TEXT to "자막"
                else -> C.TRACK_TYPE_VIDEO to "영상 트랙"
            }
            val hasTracks = player.currentTracks.groups.any { g -> g.type == type }
            if (!hasTracks) {
                Util.toast(this, "$title 없음")
            } else {
                TrackSelectionDialogBuilder(this, title, player, type)
                    .setAllowAdaptiveSelections(false)
                    .setShowDisableOption(type == C.TRACK_TYPE_TEXT)
                    .build()
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun cycleResizeMode() {
        val (next, label) = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "화면 채우기 (잘림)"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL to "늘려서 채우기"
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT to "원본 비율"
        }
        playerView.resizeMode = next
        showIndicator(label)
    }

    // ---------------------------------------------------------------- 캡쳐 / 구간 저장

    private fun captureFrame() {
        val texture = playerView.videoSurfaceView as? TextureView
        val bitmap = texture?.bitmap
        if (bitmap == null || player.videoSize.width == 0) {
            Util.toast(this, "캡쳐할 화면이 없습니다")
            return
        }
        val base = "capture_${MediaStoreSaver.stamp()}_${Util.formatTime(player.currentPosition).replace(':', '-')}"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaStoreSaver.saveImage(this@PlayerActivity, bitmap, base) }
            }
            result.onSuccess { Util.toast(this@PlayerActivity, "캡쳐 저장: 사진/${MediaStoreSaver.FOLDER}") }
                .onFailure { Util.toast(this@PlayerActivity, "캡쳐 실패: ${it.message}") }
        }
    }

    private fun showClipDialog() {
        val uri = currentUri() ?: return
        val duration = player.duration
        if (duration <= 0) {
            Util.toast(this, "길이를 알 수 없는 동영상(실시간 방송 등)은 저장할 수 없습니다")
            return
        }
        val start = if (abStart != C.TIME_UNSET) abStart else player.currentPosition
        val end = if (abEnd != C.TIME_UNSET) abEnd else (start + 30_000).coerceAtMost(duration)
        val view = layoutInflater.inflate(R.layout.dialog_range, null)
        val startEdit = view.findViewById<EditText>(R.id.start)
        val endEdit = view.findViewById<EditText>(R.id.end)
        val removeAudio = view.findViewById<android.widget.CheckBox>(R.id.removeAudio)
        startEdit.setText(Util.formatTime(start))
        endEdit.setText(Util.formatTime(end))
        player.pause()
        AlertDialog.Builder(this)
            .setTitle("구간을 동영상으로 저장")
            .setMessage("구간반복(A-B)을 지정해 두면 그 구간이 자동으로 채워집니다.")
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                val s = Util.parseTime(startEdit.text.toString())
                val e = Util.parseTime(endEdit.text.toString())
                if (s == null || e == null || e <= s || s < 0) {
                    Util.toast(this, "시간을 확인해 주세요")
                } else {
                    val base = "clip_${MediaStoreSaver.stamp()}"
                    runExport("구간 저장 중") { cb ->
                        exporter.exportClip(uri, s, e.coerceAtMost(duration), removeAudio.isChecked, base, cb)
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun runExport(title: String, start: (VideoExporter.Callback) -> Unit) {
        val bar = LinearProgressIndicator(this).apply { max = 100; isIndeterminate = false }
        val box = LinearLayout(this).apply {
            setPadding(64, 32, 64, 0)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { _, _ -> exporter.cancel() }
            .show()
        start(object : VideoExporter.Callback {
            override fun onProgress(percent: Int) {
                bar.setProgressCompat(percent, true)
            }

            override fun onDone(saved: Uri) {
                dialog.dismiss()
                Util.toast(this@PlayerActivity, "저장 완료: 동영상/${MediaStoreSaver.FOLDER}")
            }

            override fun onError(message: String) {
                dialog.dismiss()
                AlertDialog.Builder(this@PlayerActivity).setTitle("저장 실패").setMessage(message)
                    .setPositiveButton("확인", null).show()
            }
        })
    }

    // ---------------------------------------------------------------- 즐겨찾기

    private fun updateMarkers() {
        val times = bookmarks.map { it.positionMs }.toLongArray()
        timeBar.setAdGroupTimesMs(times, BooleanArray(times.size), times.size)
    }

    private fun addBookmark() {
        val uri = currentUri() ?: return
        val pos = player.currentPosition
        val edit = EditText(this).apply { hint = "메모 (선택)" }
        val box = LinearLayout(this).apply { setPadding(64, 16, 64, 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("즐겨찾기 추가 · ${Util.formatTime(pos)}")
            .setView(box)
            .setPositiveButton("추가") { _, _ ->
                val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: Util.displayName(this, uri)
                lifecycleScope.launch {
                    db.bookmarks().insert(
                        Bookmark(
                            videoKey = RemoteUris.keyOf(uri),
                            videoUri = uri.toString(),
                            videoTitle = title,
                            positionMs = pos,
                            label = edit.text.toString().trim()
                        )
                    )
                    showIndicator("즐겨찾기 추가 ${Util.formatTime(pos)}")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showBookmarks() {
        val list = bookmarks
        if (list.isEmpty()) {
            Util.toast(this, "이 동영상의 즐겨찾기가 없습니다. 북마크 버튼으로 추가하세요")
            return
        }
        val labels = list.map { b -> Util.formatTime(b.positionMs) + if (b.label.isNotEmpty()) "  ${b.label}" else "" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("즐겨찾기 (길게 눌러 삭제)")
            .setItems(labels.toTypedArray()) { _, which ->
                player.seekTo(list[which].positionMs)
                player.play()
            }
            .setNegativeButton("닫기", null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, which, _ ->
                val b = list[which]
                AlertDialog.Builder(this)
                    .setMessage("${Util.formatTime(b.positionMs)} 즐겨찾기를 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        lifecycleScope.launch { db.bookmarks().delete(b) }
                        dialog.dismiss()
                    }
                    .setNegativeButton("취소", null)
                    .show()
                true
            }
        }
        dialog.show()
    }
}
