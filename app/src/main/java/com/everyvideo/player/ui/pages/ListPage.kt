package com.everyvideo.player.ui.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import com.everyvideo.player.R
import com.everyvideo.player.ui.MainActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/** 목록 화면 한 줄. */
data class Row(
    val title: String,
    val subtitle: String = "",
    @DrawableRes val icon: Int = R.drawable.ic_movie,
    val badge: String? = null,
    val showMore: Boolean = true,
    val onMore: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null
)

interface Searchable {
    fun onSearch(query: String)
}

/** 뒤로 가기를 직접 처리하고 싶은 화면. true 를 돌려주면 처리한 것. */
interface BackHandler {
    fun onBack(): Boolean
}

/** 제목, 오른쪽 위 버튼들, 카드형 목록, 빈 화면 안내를 가진 공통 화면. */
abstract class ListPage : Fragment(R.layout.fragment_list), Searchable {
    protected lateinit var listView: ListView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var actions: LinearLayout
    private lateinit var emptyView: View
    private lateinit var progress: LinearProgressIndicator
    protected lateinit var upButton: ImageButton
    private val adapter = RowAdapter()
    private var allRows: List<Row> = emptyList()
    private var query = ""

    protected val main: MainActivity get() = requireActivity() as MainActivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listView = view.findViewById(R.id.list)
        titleView = view.findViewById(R.id.pageTitle)
        subtitleView = view.findViewById(R.id.pageSubtitle)
        actions = view.findViewById(R.id.actions)
        emptyView = view.findViewById(R.id.emptyView)
        progress = view.findViewById(R.id.progress)
        upButton = view.findViewById(R.id.btnUp)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> adapter.getItem(pos).onClick?.invoke() }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val r = adapter.getItem(pos)
            r.onMore?.invoke()
            r.onMore != null
        }
        setup()
    }

    abstract fun setup()

    protected fun setTitle(title: String, subtitle: String? = null) {
        titleView.text = title
        subtitleView.text = subtitle ?: ""
        subtitleView.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    protected fun clearActions() = actions.removeAllViews()

    /** 오른쪽 위 둥근 버튼. text 가 있으면 글자 버튼, 없으면 아이콘 버튼. */
    protected fun addAction(@DrawableRes icon: Int, label: String, primary: Boolean = false, onClick: () -> Unit) {
        val style = if (primary) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle
        val b = MaterialButton(requireContext(), null, style)
        b.text = label
        b.setIconResource(icon)
        b.iconPadding = (6 * resources.displayMetrics.density).toInt()
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = (8 * resources.displayMetrics.density).toInt()
        actions.addView(b, lp)
    }

    protected fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    protected fun setEmpty(@DrawableRes icon: Int, title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        emptyView.findViewById<ImageView>(R.id.emptyIcon).setImageResource(icon)
        emptyView.findViewById<TextView>(R.id.emptyTitle).text = title
        emptyView.findViewById<TextView>(R.id.emptyMessage).text = message
        emptyView.findViewById<MaterialButton>(R.id.emptyAction).apply {
            if (action != null) {
                text = action
                visibility = View.VISIBLE
                setOnClickListener { onAction?.invoke() }
            } else visibility = View.GONE
        }
    }

    protected fun setRows(rows: List<Row>) {
        allRows = rows
        applyFilter()
    }

    override fun onSearch(query: String) {
        this.query = query.trim()
        if (::listView.isInitialized) applyFilter()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        val rows = if (q.isEmpty()) allRows else allRows.filter {
            it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (rows.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
            val r = rows[position]
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.subtitle).apply {
                text = r.subtitle
                visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
            }
            val icon = v.findViewById<ImageView>(R.id.icon)
            val badge = v.findViewById<TextView>(R.id.badge)
            if (r.badge != null) {
                icon.visibility = View.GONE
                badge.visibility = View.VISIBLE
                badge.text = r.badge
            } else {
                badge.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageResource(r.icon)
            }
            v.findViewById<ImageButton>(R.id.more).apply {
                visibility = if (r.showMore && r.onMore != null) View.VISIBLE else View.GONE
                setOnClickListener { r.onMore?.invoke() }
            }
            return v
        }
    }
}
