package com.everyvideo.player.ui.pages

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import android.widget.ImageView
import android.widget.TextView
import com.everyvideo.player.R
import com.everyvideo.player.ui.MainActivity
import com.everyvideo.player.ui.VideoPicker
import com.google.android.material.button.MaterialButton

/** 시작 화면: 가운데 안내만 보여준다. */
class HomePage : Fragment(R.layout.view_empty) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_movie)
        view.findViewById<TextView>(R.id.emptyTitle).text = "동영상 열기"
        view.findViewById<TextView>(R.id.emptyMessage).text =
            "메뉴의 '파일 열기'에서 내 파일이나 Google Drive의 동영상을 고르세요.\n재생목록과 즐겨찾기도 메뉴에서 볼 수 있습니다."
        view.findViewById<MaterialButton>(R.id.emptyAction).apply {
            visibility = View.VISIBLE
            text = "내 파일에서 열기"
            setIconResource(R.drawable.ic_phone)
            setOnClickListener {
                val main = requireActivity() as MainActivity
                main.pickVideos(VideoPicker.Source.MY_FILES) { main.play(it) }
            }
        }
    }
}
