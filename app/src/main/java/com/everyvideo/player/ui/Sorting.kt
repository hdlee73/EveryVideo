package com.everyvideo.player.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.everyvideo.player.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

/** 재생목록·이어붙이기 목록 정렬. */
object Sorting {
    enum class Order(val label: String, val sub: String) {
        NAME("이름순", "가나다 · 숫자는 크기대로 (1, 2, 10)"),
        NAME_DESC("이름 역순", "하가나 순"),
        NEWEST("저장 시간 · 최신 먼저", "파일이 만들어지거나 바뀐 시간"),
        OLDEST("저장 시간 · 오래된 먼저", "파일이 만들어지거나 바뀐 시간")
    }

    private val collator: Collator = Collator.getInstance(Locale.KOREAN).apply { strength = Collator.SECONDARY }

    /** "영상2" 가 "영상10" 보다 앞에 오는 비교. */
    val natural = Comparator<String> { a, b ->
        val ca = chunks(a)
        val cb = chunks(b)
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else collator.compare(x, y)
            if (r != 0) return@Comparator r
        }
        ca.size - cb.size
    }

    private fun chunks(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var digit = false
        for (c in s) {
            if (sb.isNotEmpty() && c.isDigit() != digit) {
                out += sb.toString(); sb.clear()
            }
            digit = c.isDigit()
            sb.append(c)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.ifEmpty { listOf("") }
    }

    /** 파일의 마지막 수정(저장) 시간. 알 수 없으면 0. 입출력이 있으니 백그라운드에서 부른다. */
    fun lastModified(context: Context, uri: Uri): Long {
        return runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).lastModified() } ?: 0L
                "content" -> context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use 0L
                    val doc = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val media = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    when {
                        doc >= 0 && !c.isNull(doc) -> c.getLong(doc)
                        media >= 0 && !c.isNull(media) -> c.getLong(media) * 1000
                        else -> 0L
                    }
                } ?: 0L
                else -> 0L
            }
        }.getOrDefault(0L)
    }

    fun choose(context: Context, onPick: (Order) -> Unit) {
        AppDialog.choice(
            context, "정렬",
            Order.entries.map { AppDialog.Companion.Item(it.label, it.sub, R.drawable.ic_sort) }
        ) { which -> onPick(Order.entries[which]) }
    }

    suspend fun <T> sort(context: Context, items: List<T>, order: Order, uriOf: (T) -> Uri, nameOf: (T) -> String): List<T> =
        when (order) {
            Order.NAME -> items.sortedWith { a, b -> natural.compare(nameOf(a), nameOf(b)) }
            Order.NAME_DESC -> items.sortedWith { a, b -> natural.compare(nameOf(b), nameOf(a)) }
            Order.NEWEST, Order.OLDEST -> {
                val times = withContext(Dispatchers.IO) { items.map { lastModified(context, uriOf(it)) } }
                val paired = items.zip(times)
                val sorted = if (order == Order.NEWEST) paired.sortedByDescending { it.second } else paired.sortedBy { it.second }
                sorted.map { it.first }
            }
        }
}
