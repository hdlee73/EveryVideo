package com.everyvideo.player.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.everyvideo.player.data.Prefs
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 캡쳐 이미지와 동영상을 저장한다.
 * 사용자가 고른 폴더(SAF 트리)가 있으면 거기에, 없으면 사진/EveryVideo, 동영상/EveryVideo 에 저장한다.
 */
object MediaStoreSaver {
    const val FOLDER = "EveryVideo"
    const val DEFAULT_IMAGE_LABEL = "사진/EveryVideo"
    const val DEFAULT_VIDEO_LABEL = "동영상/EveryVideo"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun cleanName(name: String, ext: String): String {
        val base = name.trim().removeSuffix(".$ext").replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { stamp() }
        return "$base.$ext"
    }

    /** 쓰기용으로 만든 파일. 다 쓰고 나서 [finish] 를 불러야 갤러리에 나타난다. */
    class Target(val uri: Uri, private val pendingMediaStore: Boolean) {
        fun finish(context: Context) {
            if (pendingMediaStore && Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }

        fun discard(context: Context) {
            runCatching {
                if (pendingMediaStore) context.contentResolver.delete(uri, null, null)
                else DocumentFile.fromSingleUri(context, uri)?.delete()
            }
        }
    }

    private fun create(context: Context, folder: Uri?, fileName: String, mime: String, image: Boolean): Target {
        if (folder != null) {
            val dir = DocumentFile.fromTreeUri(context, folder)
            val file = dir?.takeIf { it.canWrite() }?.createFile(mime, fileName)
            if (file != null) return Target(file.uri, false)
            // 폴더 권한이 사라졌으면 기본 위치로 저장
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            val dirName = if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirName/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(dirName), FOLDER)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, fileName).absolutePath)
            }
        }
        val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: throw IOException("파일을 만들 수 없습니다")
        return Target(uri, true)
    }

    fun createVideo(context: Context, name: String): Target =
        create(context, Prefs(context).videoFolder, cleanName(name, "mp4"), "video/mp4", false)

    fun saveImage(context: Context, bitmap: Bitmap, name: String, folder: Uri? = Prefs(context).imageFolder, jpeg: Boolean = false): Uri {
        val t = if (jpeg) create(context, folder, cleanName(name, "jpg"), "image/jpeg", true)
        else create(context, folder, cleanName(name, "png"), "image/png", true)
        try {
            context.contentResolver.openOutputStream(t.uri)!!.use {
                if (jpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) else bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            t.finish(context)
        } catch (e: Exception) {
            t.discard(context)
            throw e
        }
        return t.uri
    }

    /** 만들어 둔 GIF 파일을 사진 폴더(또는 고른 폴더)로 옮겨 담는다. */
    fun saveGifFile(context: Context, file: File, name: String, folder: Uri?): Uri {
        val t = create(context, folder, cleanName(name, "gif"), "image/gif", true)
        try {
            context.contentResolver.openOutputStream(t.uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            t.finish(context)
        } catch (e: Exception) {
            t.discard(context)
            throw e
        }
        return t.uri
    }

    fun saveVideoFile(context: Context, file: File, name: String, folder: Uri? = Prefs(context).videoFolder): Uri {
        val t = create(context, folder, cleanName(name, "mp4"), "video/mp4", false)
        try {
            context.contentResolver.openOutputStream(t.uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            t.finish(context)
        } catch (e: Exception) {
            t.discard(context)
            throw e
        }
        return t.uri
    }
}
