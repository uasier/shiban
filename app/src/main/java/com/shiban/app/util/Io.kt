package com.shiban.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

object Io {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) {
        pool.execute(block)
    }

    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

fun Context.toast(message: String) {
    Io.ui { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
}
