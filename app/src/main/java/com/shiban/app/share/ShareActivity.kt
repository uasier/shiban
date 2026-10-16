package com.shiban.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.shiban.app.data.Sync
import com.shiban.app.databinding.ActivityShareBinding
import com.shiban.app.ui.MainActivity
import com.shiban.app.ui.SettingsActivity
import com.shiban.app.util.Images
import com.shiban.app.util.Io
import com.shiban.app.util.toast

class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val text = readText(intent)
        val uris = collectUris(intent)
        if (text.isNullOrBlank() && uris.isEmpty()) {
            toast("没有收到文字或图片")
            finish()
            return
        }
        val app = applicationContext
        Io.run {
            val paths = mutableListOf<String>()
            var imageError: String? = null
            uris.forEach { uri ->
                try {
                    paths += Images.importUri(app, uri)
                } catch (error: Exception) {
                    if (imageError == null) imageError = error.message
                }
            }
            if (text.isNullOrBlank() && paths.isEmpty()) {
                Io.ui {
                    toast(imageError ?: "没有收到文字或图片")
                    finish()
                }
                return@run
            }
            try {
                Io.ui { if (!isDestroyed) binding.status.text = "正在整理成待办" }
                val result = Sync.capture(app, text, paths)
                Io.ui {
                    if (isDestroyed) return@ui
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    if (result.needLogin) startActivity(Intent(this, SettingsActivity::class.java))
                    toast(result.message)
                    finish()
                }
            } catch (error: Exception) {
                Io.ui {
                    toast(error.message ?: "接收分享失败")
                    finish()
                }
            }
        }
    }

    private fun readText(intent: Intent): String? {
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return raw?.trim()?.ifBlank { null }
    }

    private fun collectUris(intent: Intent): List<Uri> {
        val found = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            found += intent.uriList()
        } else {
            intent.uriExtra()?.let { found += it }
        }
        if (found.isEmpty()) {
            val clip = intent.clipData ?: return emptyList()
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { found += it }
            }
        }
        return found.distinct()
    }

    @Suppress("DEPRECATION")
    private fun Intent.uriExtra(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.uriList(): List<Uri> {
        return getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
}
