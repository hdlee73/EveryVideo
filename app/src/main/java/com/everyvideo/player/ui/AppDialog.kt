package com.everyvideo.player.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.everyvideo.player.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 앱 전체에서 쓰는 둥근 카드형 대화상자.
 * 아이콘, 제목, 설명, 임의의 내용, 버튼 세 개(주/보조/왼쪽 텍스트)를 가진다.
 */
class AppDialog(private val context: Context) {
    private val dialog = Dialog(context)
    private val root: View = LayoutInflater.from(context).inflate(R.layout.dialog_app, null)
    private val content: ViewGroup = root.findViewById(R.id.content)
    private var dismissOnClick = true

    init {
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun icon(@DrawableRes res: Int) = apply {
        root.findViewById<View>(R.id.iconBox).visibility = View.VISIBLE
        root.findViewById<ImageView>(R.id.icon).setImageResource(res)
    }

    fun title(text: CharSequence) = apply { root.findViewById<TextView>(R.id.title).text = text }

    fun message(text: CharSequence) = apply {
        root.findViewById<TextView>(R.id.message).apply { this.text = text; visibility = View.VISIBLE }
    }

    fun content(view: View) = apply {
        content.visibility = View.VISIBLE
        content.addView(view)
    }

    fun primary(text: CharSequence, action: (() -> Unit)? = null) = button(R.id.btnPrimary, text, action)
    fun secondary(text: CharSequence, action: (() -> Unit)? = null) = button(R.id.btnSecondary, text, action)
    fun tertiary(text: CharSequence, action: (() -> Unit)? = null) = button(R.id.btnTertiary, text, action)

    /** false 면 주 버튼을 눌러도 닫히지 않는다 (입력 검증 실패 시 직접 닫지 않음). */
    fun keepOpenOnPrimary() = apply { dismissOnClick = false }

    fun cancelable(value: Boolean) = apply { dialog.setCancelable(value) }

    fun onDismiss(action: () -> Unit) = apply { dialog.setOnDismissListener { action() } }

    private fun button(id: Int, text: CharSequence, action: (() -> Unit)?) = apply {
        root.findViewById<MaterialButton>(id).apply {
            this.text = text
            visibility = View.VISIBLE
            setOnClickListener {
                if (id != R.id.btnPrimary || dismissOnClick) dialog.dismiss()
                action?.invoke()
            }
        }
    }

    fun dismiss() = dialog.dismiss()

    fun show(): AppDialog {
        dialog.show()
        val dm = context.resources.displayMetrics
        val maxWidth = (440 * dm.density).toInt()
        val width = minOf((dm.widthPixels * 0.9f).toInt(), maxWidth)
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        return this
    }

    companion object {
        fun confirm(
            context: Context, title: String, message: String, ok: String = "확인",
            @DrawableRes icon: Int? = null, danger: Boolean = false, onOk: () -> Unit
        ): AppDialog {
            val d = AppDialog(context).title(title).message(message).secondary("취소").primary(ok, onOk)
            icon?.let { d.icon(it) }
            if (danger) {
                d.root.findViewById<MaterialButton>(R.id.btnPrimary)
                    .setBackgroundColor(context.getColor(R.color.danger))
            }
            return d.show()
        }

        fun info(context: Context, title: String, message: String, @DrawableRes icon: Int = R.drawable.ic_info): AppDialog =
            AppDialog(context).icon(icon).title(title).message(message).primary("확인").show()

        fun input(
            context: Context, title: String, hint: String, prefill: String = "",
            ok: String = "확인", @DrawableRes icon: Int? = null, onOk: (String) -> Unit
        ): AppDialog {
            val v = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
            v.findViewById<TextInputLayout>(R.id.layout).hint = hint
            val edit = v.findViewById<TextInputEditText>(R.id.edit)
            edit.setText(prefill)
            edit.setSelection(prefill.length)
            val d = AppDialog(context).title(title).content(v).secondary("취소").primary(ok) {
                onOk(edit.text?.toString()?.trim() ?: "")
            }
            icon?.let { d.icon(it) }
            d.show()
            edit.requestFocus()
            d.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            return d
        }

        data class Item(val text: String, val sub: String? = null, @DrawableRes val icon: Int? = null)

        /**
         * 항목 목록. checked 가 0 이상이면 그 항목에 체크 표시.
         * onLongPick 이 있으면 길게 눌렀을 때 호출된다.
         */
        fun choice(
            context: Context, title: String, items: List<Item>, checked: Int = -1,
            message: String? = null, onLongPick: ((Int) -> Unit)? = null, onPick: (Int) -> Unit
        ): AppDialog {
            val inflater = LayoutInflater.from(context)
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val d = AppDialog(context).title(title)
            message?.let { d.message(it) }
            items.forEachIndexed { i, item ->
                val row = inflater.inflate(R.layout.item_choice, list, false)
                row.findViewById<TextView>(R.id.text).text = item.text
                item.sub?.let { s -> row.findViewById<TextView>(R.id.sub).apply { text = s; visibility = View.VISIBLE } }
                item.icon?.let { ic -> row.findViewById<ImageView>(R.id.icon).apply { setImageResource(ic); visibility = View.VISIBLE } }
                if (i == checked) {
                    row.isSelected = true
                    row.findViewById<View>(R.id.check).visibility = View.VISIBLE
                }
                row.setOnClickListener { d.dismiss(); onPick(i) }
                if (onLongPick != null) row.setOnLongClickListener { d.dismiss(); onLongPick(i); true }
                list.addView(row)
            }
            val scroll = ScrollView(context).apply {
                addView(list)
                isVerticalScrollBarEnabled = false
            }
            val maxH = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
            scroll.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            d.content(scroll)
            d.secondary("닫기")
            d.show()
            scroll.post {
                if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
            }
            return d
        }

        class Progress(val dialog: AppDialog, val bar: LinearProgressIndicator, val label: TextView) {
            fun set(percent: Int) {
                bar.setProgressCompat(percent, true)
                label.text = "$percent%"
            }
        }

        fun progress(context: Context, title: String, message: String?, onCancel: () -> Unit): Progress {
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val bar = LinearProgressIndicator(context).apply {
                max = 100
                trackCornerRadius = (4 * context.resources.displayMetrics.density).toInt()
                trackThickness = (8 * context.resources.displayMetrics.density).toInt()
            }
            val label = TextView(context).apply {
                text = "0%"
                setPadding(0, (8 * context.resources.displayMetrics.density).toInt(), 0, 0)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }
            box.addView(bar)
            box.addView(label)
            val d = AppDialog(context).icon(R.drawable.ic_save).title(title).content(box).cancelable(false)
                .secondary("취소", onCancel)
            message?.let { d.message(it) }
            d.show()
            return Progress(d, bar, label)
        }
    }
}
