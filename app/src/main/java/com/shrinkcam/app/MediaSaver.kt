package com.shrinkcam.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaSaver {

    /** 把文件写入系统相册 Movies/压缩相机，返回可播放的 Uri */
    fun saveToGallery(ctx: Context, file: File, name: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/压缩相机")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out, 1 shl 16) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "压缩相机")
                if (!dir.exists()) dir.mkdirs()
                val dst = File(dir, name)
                file.copyTo(dst, overwrite = true)
                Uri.fromFile(dst)
            }
        } catch (t: Throwable) {
            null
        }
    }
}
