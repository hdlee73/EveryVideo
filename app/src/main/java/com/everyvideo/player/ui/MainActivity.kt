package com.everyvideo.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.everyvideo.player.R
import com.everyvideo.player.net.DriveLinks
import com.everyvideo.player.record.ScreenRecordActivity
import com.everyvideo.player.record.ScreenRecordService
import com.everyvideo.player.ui.pages.BackHandler
import com.everyvideo.player.ui.pages.BookmarksPage
import com.everyvideo.player.ui.pages.ConcatPage
import com.everyvideo.player.ui.pages.HomePage
import com.everyvideo.player.ui.pages.PlaylistsPage
import com.everyvideo.player.ui.pages.Searchable
import com.everyvideo.player.ui.pages.ServersPage
import com.everyvideo.player.ui.pages.SettingsPage
import com.google.android.material.materialswitch.MaterialSwitch

typealias Source = VideoPicker.Source

class MainActivity : AppCompatActivity() {

    enum class Page(val label: String, @DrawableRes val icon: Int) {
        HOME("홈", R.drawable.ic_home),
        PLAYLISTS("재생목록", R.drawable.ic_playlist),
        BOOKMARKS("즐겨찾기", R.drawable.ic_bookmarks),
        SERVERS("FTP / SMB 서버", R.drawable.ic_server),
        CONCAT("동영상 이어붙이기", R.drawable.ic_merge),
        SETTINGS("설정", R.drawable.ic_settings)
    }


    private var drawer: DrawerLayout? = null
    private lateinit var navItems: LinearLayout
    private lateinit var navBottom: LinearLayout
    private val navRows = mutableMapOf<Any, View>()
    private var fileGroupOpen = true
    private var current: Page = Page.HOME
    private var searchQuery = ""

    // ---------------------------------------------------------------- 파일 선택

    private val picker = VideoPicker(this)
    private val folderPicker = FolderPicker(this)

    /** 동영상 파일을 골라 콜백으로 돌려준다. 내 파일(삼성) / 구글 드라이브 / 다른 앱. */
    fun pickVideos(source: Source, onPicked: (List<Uri>) -> Unit) = picker.pick(source, onPicked)

    fun chooseSourceAndPick(title: String, onPicked: (List<Uri>) -> Unit) = picker.chooseAndPick(title, onPicked)

    fun play(uris: List<Uri>, index: Int = 0, startMs: Long = -1L, playlistId: Long = -1L) {
        if (uris.isEmpty()) return
        startActivity(Util.playIntent(this, uris, index, startMs).putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, playlistId))
    }

    fun pickFolder(onPicked: (Uri) -> Unit) = folderPicker.pick(onPicked)

    private var imageCallback: ((Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = imageCallback
        imageCallback = null
        if (uri != null && cb != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            cb(uri)
        }
    }

    /** 이미지 한 장 고르기 (이어붙이기 썸네일). 취소하면 아무 일도 없다. */
    fun pickImage(onPicked: (Uri) -> Unit) {
        imageCallback = onPicked
        runCatching { imagePicker.launch(arrayOf("image/*")) }
    }

    // ---------------------------------------------------------------- 화면 구성

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawer)
        navItems = findViewById(R.id.navItems)
        navBottom = findViewById(R.id.navBottom)
        findViewById<View?>(R.id.btnMenu)?.setOnClickListener { drawer?.openDrawer(GravityCompat.START) }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                (supportFragmentManager.findFragmentById(R.id.content) as? Searchable)?.onSearch(searchQuery)
            }
        })

        current = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME
        buildNav()
        if (savedInstanceState == null) show(current) else selectNav(current)
        if (savedInstanceState == null) AboutDialog.checkOnLaunch(this, lifecycleScope)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val d = drawer
                if (d != null && d.isDrawerOpen(GravityCompat.START)) {
                    d.closeDrawer(GravityCompat.START); return
                }
                val f = supportFragmentManager.findFragmentById(R.id.content)
                if (f is BackHandler && f.onBack()) return
                if (current != Page.HOME) show(Page.HOME) else finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", current.name)
    }

    override fun onResume() {
        super.onResume()
        // 재생 화면에서 정한 밝기를 앱 화면에도 적용 (최대로 했으면 기기 최대 밝기)
        com.everyvideo.player.data.Prefs(this).brightnessLevel.let { level ->
            val lp = window.attributes
            lp.screenBrightness = when {
                level == null -> android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                level >= 100 -> android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                level <= 0 -> 0.02f
                else -> Math.pow(level / 100.0, 2.2).toFloat().coerceAtLeast(0.02f)
            }
            window.attributes = lp
        }
        navRows["record"]?.findViewById<TextView>(R.id.label)?.text =
            if (ScreenRecordService.isRecording) "녹화 중지" else "화면 녹화"
    }

    private fun navRow(parent: ViewGroup, key: Any, label: String, @DrawableRes icon: Int, indent: Boolean = false, onClick: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_nav, parent, false)
        v.findViewById<TextView>(R.id.label).text = label
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        if (indent) {
            (v.findViewById<View>(R.id.icon).layoutParams as LinearLayout.LayoutParams).marginStart =
                (39 * resources.displayMetrics.density).toInt()
        }
        v.setOnClickListener { onClick() }
        parent.addView(v)
        navRows[key] = v
        return v
    }

    private fun buildNav() {
        navItems.removeAllViews()
        navBottom.removeAllViews()
        navRows.clear()
        navRow(navItems, Page.HOME, Page.HOME.label, Page.HOME.icon) { show(Page.HOME) }

        val group = navRow(navItems, "files", "파일 열기", R.drawable.ic_open) {
            fileGroupOpen = !fileGroupOpen
            buildNav()
            selectNav(current)
        }
        group.findViewById<ImageView>(R.id.trailing).apply {
            visibility = View.VISIBLE
            setImageResource(if (fileGroupOpen) R.drawable.ic_collapse else R.drawable.ic_expand)
        }
        if (fileGroupOpen) {
            navRow(navItems, Source.MY_FILES, "내 파일", R.drawable.ic_phone, indent = true) { openFrom(Source.MY_FILES) }
            navRow(navItems, Source.DRIVE, "Google Drive", R.drawable.ic_drive, indent = true) { openFrom(Source.DRIVE) }
            navRow(navItems, Source.OTHER, "다른 앱에서 찾기", R.drawable.ic_apps, indent = true) { openFrom(Source.OTHER) }
        }
        navRow(navItems, "url", "주소로 열기", R.drawable.ic_link) { closeDrawer(); showUrlDialog() }
        for (p in listOf(Page.PLAYLISTS, Page.BOOKMARKS, Page.SERVERS, Page.CONCAT)) {
            navRow(navItems, p, p.label, p.icon) { show(p) }
        }
        navRow(navItems, "record", if (ScreenRecordService.isRecording) "녹화 중지" else "화면 녹화", R.drawable.ic_record) {
            closeDrawer(); onRecordClicked()
        }
        navRow(navBottom, Page.SETTINGS, Page.SETTINGS.label, Page.SETTINGS.icon) { show(Page.SETTINGS) }
    }

    private fun selectNav(page: Page) {
        navRows.forEach { (key, v) ->
            val sel = key == page
            v.isSelected = sel
            v.findViewById<View>(R.id.indicator).visibility = if (sel) View.VISIBLE else View.INVISIBLE
        }
        findViewById<TextView?>(R.id.topTitle)?.text = if (page == Page.HOME) getString(R.string.app_name) else page.label
    }

    private fun closeDrawer() {
        drawer?.closeDrawer(GravityCompat.START)
    }

    fun show(page: Page) {
        current = page
        val f: Fragment = when (page) {
            Page.HOME -> HomePage()
            Page.PLAYLISTS -> PlaylistsPage()
            Page.BOOKMARKS -> BookmarksPage()
            Page.SERVERS -> ServersPage()
            Page.CONCAT -> ConcatPage()
            Page.SETTINGS -> SettingsPage()
        }
        supportFragmentManager.beginTransaction().replace(R.id.content, f).commitNow()
        if (searchQuery.isNotEmpty()) (f as? Searchable)?.onSearch(searchQuery)
        selectNav(page)
        closeDrawer()
    }

    /** 다른 화면(서버 폴더 탐색 등)을 내용 영역에 띄운다. 메뉴 선택 상태는 유지. */
    fun showFragment(f: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.content, f).commitNow()
        closeDrawer()
    }

    private fun openFrom(source: Source) {
        closeDrawer()
        pickVideos(source) { uris -> play(uris) }
    }

    fun showUrlDialog() {
        AppDialog.input(
            this, "주소로 열기", "https://…, rtsp://…, 구글 드라이브 공유 링크",
            ok = "재생", icon = R.drawable.ic_link
        ) { text ->
            if (text.isEmpty()) return@input
            val url = if (DriveLinks.isDriveLink(text)) DriveLinks.streamUrl(text) ?: text else text
            play(listOf(Uri.parse(url)))
        }
    }

    // ---------------------------------------------------------------- 화면 녹화

    private var pendingMic = false
    private val recordPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        if (pendingMic && !mic) Util.toast(this, "마이크 권한이 없어 소리 없이 녹화합니다")
        startRecording(pendingMic && mic)
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun onRecordClicked() {
        if (ScreenRecordService.isRecording) {
            startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
            navRows["record"]?.findViewById<TextView>(R.id.label)?.text = "화면 녹화"
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val steps = TextView(this).apply {
            text = "1. '녹화 시작'을 누르면 안드로이드 화면 공유 창이 열립니다. 이 창은 시스템이 띄우는 것이라 영어로 나올 수 있어요.\n" +
                "2. 위쪽 목록에서 '전체 화면 공유(Share entire screen)'를 고르세요. 한 앱만 고르면 그 앱 화면만 녹화됩니다.\n" +
                "3. '다음(Next)' 또는 '시작(Start)'을 누르면 녹화가 시작됩니다.\n" +
                "4. 끝낼 때는 알림창의 '중지'나 이 메뉴의 '녹화 중지'를 누르세요."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        }
        val mic = MaterialSwitch(this).apply {
            text = "마이크 소리도 함께 녹음"
            textSize = 15f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        box.addView(steps)
        box.addView(mic)
        AppDialog(this)
            .icon(R.drawable.ic_record)
            .title("화면 녹화")
            .message("저장 위치: " + com.everyvideo.player.data.Prefs(this)
                .folderLabel(com.everyvideo.player.data.Prefs(this).videoFolder, com.everyvideo.player.media.MediaStoreSaver.DEFAULT_VIDEO_LABEL) +
                "\n보안이 걸린 화면(일부 OTT·금융 앱)은 안드로이드 정책상 검게 녹화됩니다.")
            .content(box)
            .secondary("취소")
            .primary("녹화 시작") { askPermsAndRecord(mic.isChecked) }
            .show()
    }

    private fun askPermsAndRecord(withMic: Boolean) {
        pendingMic = withMic
        val perms = mutableListOf<String>()
        if (withMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) perms += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) perms += Manifest.permission.POST_NOTIFICATIONS
        if (perms.isEmpty()) startRecording(withMic) else recordPerms.launch(perms.toTypedArray())
    }

    private fun startRecording(withMic: Boolean) {
        startActivity(Intent(this, ScreenRecordActivity::class.java).putExtra(ScreenRecordService.EXTRA_MIC, withMic))
    }

    companion object {
        val VIDEO_MIME_TYPES = VideoPicker.VIDEO_MIME_TYPES
    }
}
