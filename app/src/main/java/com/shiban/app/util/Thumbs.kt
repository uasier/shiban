package com.shiban.app.util

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

object Thumbs {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    fun load(path: String, targetPx: Int, onReady: (Bitmap?) -> Unit) {
        val key = "$targetPx:$path"
        cache.get(key)?.let { cached ->
            if (Looper.myLooper() == Looper.getMainLooper()) onReady(cached) else main.post { onReady(cached) }
            return
        }
        pool.execute {
            val bitmap = Images.thumbnail(path, targetPx, Bitmap.Config.RGB_565)
            if (bitmap != null) cache.put(key, bitmap)
            main.post { onReady(bitmap) }
        }
    }

    fun decode(path: String, targetPx: Int, onReady: (Bitmap?) -> Unit) {
        pool.execute {
            val bitmap = Images.thumbnail(path, targetPx)
            main.post { onReady(bitmap) }
        }
    }
}
