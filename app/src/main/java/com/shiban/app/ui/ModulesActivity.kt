package com.shiban.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.shiban.app.BuildConfig
import com.shiban.app.data.SettingsStore
import com.shiban.app.databinding.ActivityModulesBinding

class ModulesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityModulesBinding
    private val settings by lazy { SettingsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.profileCard.setOnClickListener {
            startActivity(SettingsActivity.open(this, SettingsActivity.PAGE_ACCOUNT))
        }
        binding.entryAlbum.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
        binding.entryCreate.setOnClickListener {
            startActivity(ReviewActivity.manual(this))
        }
        binding.entryAccount.setOnClickListener {
            startActivity(SettingsActivity.open(this, SettingsActivity.PAGE_ACCOUNT))
        }
        binding.entryModel.setOnClickListener {
            startActivity(SettingsActivity.open(this, SettingsActivity.PAGE_MODEL))
        }
        binding.entryServer.setOnClickListener {
            startActivity(SettingsActivity.open(this, SettingsActivity.PAGE_SERVER))
        }
        binding.entryData.setOnClickListener {
            startActivity(Intent(this, DataActivity::class.java))
        }
        binding.entryAbout.setOnClickListener {
            startActivity(SettingsActivity.open(this, SettingsActivity.PAGE_ABOUT))
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun renderState() {
        val loggedIn = settings.isLoggedIn
        if (loggedIn) {
            val name = settings.displayName.ifBlank { settings.username }
            binding.profileName.text = name
            binding.profileStatus.text = "已登录 · 云端实时同步"
            binding.avatarInitial.text = name.trim().take(1).uppercase()
            binding.accountSubtitle.text = "当前登录：$name"
        } else {
            binding.profileName.text = "未登录"
            binding.profileStatus.text = "点击登录以开启全设备同步"
            binding.avatarInitial.text = "识"
            binding.accountSubtitle.text = "登录、注册与绑定 GitHub"
        }

        binding.modelSubtitle.text = if (settings.aiApiKey.isNotBlank()) {
            "已配置：${settings.aiModel}"
        } else {
            "未配置模型密钥（仅记录纯文字）"
        }

        binding.serverSubtitle.text = settings.serverUrl.removePrefix("https://").removePrefix("http://")
        binding.aboutSubtitle.text = "版本 ${BuildConfig.VERSION_NAME} · 检查更新"
    }
}
