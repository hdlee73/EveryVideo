package com.everyvideo.player.record

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** 시스템 화면 녹화 동의 창을 띄우고, 허용되면 녹화 서비스를 시작하는 투명 화면. */
class ScreenRecordActivity : AppCompatActivity() {

    private val request = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                .putExtra(ScreenRecordService.EXTRA_MIC, getIntent().getBooleanExtra(ScreenRecordService.EXTRA_MIC, false))
            ContextCompat.startForegroundService(this, intent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            request.launch(mpm.createScreenCaptureIntent())
        }
    }
}
