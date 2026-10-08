package com.everyvideo.player.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 오른쪽 손잡이를 눌러 끌면 순서가 바뀌는 목록 도우미.
 * onMove 는 끄는 동안 데이터 순서를 바꾸고, onDrop 은 손을 뗐을 때 처음/마지막 위치로 한 번 불린다.
 */
class DragReorder(
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: (from: Int, to: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    private val helper = ItemTouchHelper(this)
    private var dragFrom = -1
    private var dragTo = -1

    /** false 면 끌기를 막는다 (검색 중 등). */
    var enabled = true

    fun attach(rv: RecyclerView) = helper.attachToRecyclerView(rv)

    @SuppressLint("ClickableViewAccessibility")
    fun bindHandle(handle: View, holder: RecyclerView.ViewHolder) {
        handle.setOnTouchListener { _, e ->
            if (enabled && e.actionMasked == MotionEvent.ACTION_DOWN) helper.startDrag(holder)
            false
        }
    }

    override fun isLongPressDragEnabled() = false

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        val f = vh.bindingAdapterPosition
        val t = target.bindingAdapterPosition
        if (f < 0 || t < 0) return false
        if (dragFrom < 0) dragFrom = f
        dragTo = t
        onMove(f, t)
        rv.adapter?.notifyItemMoved(f, t)
        return true
    }

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(vh, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.let { it.alpha = 0.85f; it.scaleX = 1.02f; it.scaleY = 1.02f }
    }

    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        super.clearView(rv, vh)
        vh.itemView.alpha = 1f
        vh.itemView.scaleX = 1f
        vh.itemView.scaleY = 1f
        val f = dragFrom
        val t = dragTo
        dragFrom = -1
        dragTo = -1
        if (f >= 0 && t >= 0 && f != t) onDrop(f, t)
    }
}
