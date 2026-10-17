package com.shiban.app.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.Sync
import com.shiban.app.databinding.ActivityCaptureBinding
import com.shiban.app.util.Images
import com.shiban.app.util.Io
import com.shiban.app.util.toast

class CaptureActivity : AppCompatActivity() {
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = applicationContext
        Io.run {
            try {
                val path = Images.importUri(app, uri)
                val result = Sync.capture(app, null, listOf(path))
                Io.ui {
                    toast(result.message)
                    if (result.needLogin) startActivity(SettingsActivity.open(this, SettingsActivity.PAGE_ACCOUNT))
                    else finish()
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "读取图片失败") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.fromAlbum.setOnClickListener { pickImage.launch("image/*") }
        binding.createDetail.setOnClickListener { startActivity(ReviewActivity.manual(this)) }
    }
}
