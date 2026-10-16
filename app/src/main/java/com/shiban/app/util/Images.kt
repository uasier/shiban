package com.shiban.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

object Images {
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_SIDE = 1920

    fun importUri(context: Context, uri: Uri): String {
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val temp = File(dir, "tmp_${System.nanoTime()}")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) error("无法读取图片")
                copyLimited(input, temp)
            }
            val dest = File(dir, "img_${System.currentTimeMillis()}_${temp.name.hashCode()}.jpg")
            return compress(temp, dest)
        } finally {
            temp.delete()
        }
    }

    fun thumbnail(path: String, targetPx: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        })
    }

    private fun copyLimited(input: InputStream, dest: File) {
        dest.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) error("图片超过 20MB，请换一张小一点的")
                output.write(buffer, 0, read)
            }
        }
    }

    private fun compress(source: File, dest: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("这不是可识别的图片")
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("图片解码失败")
        val rotated = rotate(source, decoded)
        val scaled = scale(rotated, MAX_SIDE)
        try {
            dest.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } finally {
            if (scaled !== rotated) scaled.recycle()
            if (rotated !== decoded) rotated.recycle()
            if (!decoded.isRecycled) decoded.recycle()
        }
        return dest.absolutePath
    }

    private fun rotate(source: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(source.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longSide
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
