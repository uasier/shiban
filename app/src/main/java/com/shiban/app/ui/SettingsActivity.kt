package com.shiban.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.shiban.app.BuildConfig
import com.shiban.app.data.Account
import com.shiban.app.data.AppUpdate
import com.shiban.app.data.openChangelog
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.Sync
import com.shiban.app.databinding.ActivitySettingsBinding
import com.shiban.app.net.ApiClient
import com.shiban.app.util.Io
import com.shiban.app.util.toast

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { SettingsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.serverUrl.setText(settings.serverUrl)
        binding.username.setText(settings.username)
        binding.displayName.setText(settings.displayName)
        binding.aiBaseUrl.setText(settings.aiBaseUrl)
        binding.aiModel.setText(settings.aiModel)
        binding.aiApiKey.setText(settings.aiApiKey)
        binding.showAiKey.setOnCheckedChangeListener { _, checked ->
            binding.aiApiKey.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            binding.aiApiKey.typeface = Typeface.DEFAULT
            binding.aiApiKey.setSelection(binding.aiApiKey.text?.length ?: 0)
        }
        binding.saveAi.setOnClickListener { saveAi() }
        binding.version.text = "识办 ${BuildConfig.VERSION_NAME}"
        binding.changelog.setOnClickListener { openChangelog() }
        binding.checkUpdate.setOnClickListener { checkUpdate() }
        binding.showPassword.setOnCheckedChangeListener { _, checked ->
            binding.password.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            binding.password.typeface = Typeface.DEFAULT
            binding.password.setSelection(binding.password.text?.length ?: 0)
        }
        binding.login.setOnClickListener { submit(register = false) }
        binding.register.setOnClickListener { submit(register = true) }
        binding.logout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("退出登录？")
                .setMessage("本机缓存会清空。账号里的待办还在，下次登录会再同步下来。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出") { _, _ ->
                    Account.signOut(this)
                    toast("已退出")
                    renderAccount()
                }
                .show()
        }
        binding.saveProfile.setOnClickListener { saveProfile() }
        binding.changePassword.setOnClickListener { changePassword() }
        renderAccount()
    }

    private fun renderAccount() {
        val loggedIn = settings.isLoggedIn
        binding.accountStatus.text = if (loggedIn) {
            "当前账号：${settings.displayName}（${settings.username}）\n待办只属于这个账号，换人登录会清空本机缓存。"
        } else {
            "还没登录。注册后就是你自己的空间，看不到别人的待办。"
        }
        binding.logout.isVisible = loggedIn
        binding.saveProfile.isVisible = loggedIn
        binding.changePassword.isVisible = loggedIn
        binding.oldPassword.isVisible = loggedIn
        binding.newPassword.isVisible = loggedIn
        binding.login.isVisible = !loggedIn
        binding.register.isVisible = !loggedIn
        binding.password.isVisible = !loggedIn
        binding.aiSection.isVisible = loggedIn
    }

    private fun submit(register: Boolean) {
        settings.serverUrl = binding.serverUrl.text?.toString().orEmpty()
        val username = binding.username.text?.toString().orEmpty().trim()
        val password = binding.password.text?.toString().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            toast("请填写用户名和密码")
            return
        }
        val previousUserId = settings.userId
        val displayName = binding.displayName.text?.toString().orEmpty()
        setBusy(true)
        binding.status.isVisible = true
        binding.status.text = if (register) "正在注册…" else "正在登录…"
        Io.run {
            val message = try {
                val client = ApiClient(SettingsStore(applicationContext))
                val profile = if (register) {
                    client.register(username, password, displayName)
                } else {
                    client.login(username, password)
                }
                Account.adopt(applicationContext, previousUserId, profile.id)
                Sync.pull(applicationContext)
                val health = client.health()
                "已登录 ${profile.displayName}。$health"
            } catch (error: Exception) {
                error.message ?: "登录失败"
            }
            Io.ui {
                setBusy(false)
                binding.status.text = message
                renderAccount()
                if (settings.isLoggedIn) {
                    toast("登录成功")
                    finish()
                }
            }
        }
    }

    private fun saveAi() {
        Io.run {
            try {
                val profile = ApiClient(SettingsStore(applicationContext)).saveAi(
                    binding.aiBaseUrl.text?.toString().orEmpty(),
                    binding.aiModel.text?.toString().orEmpty(),
                    binding.aiApiKey.text?.toString().orEmpty()
                )
                Io.ui {
                    fillAi(profile.ai)
                    toast(if (profile.ai.configured) "识别配置已同步到账号" else "已清除这个账号的密钥")
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "保存失败") }
            }
        }
    }

    private fun fillAi(ai: com.shiban.app.net.AiConfig) {
        binding.aiBaseUrl.setText(ai.baseUrl)
        binding.aiModel.setText(ai.model)
        binding.aiApiKey.setText(ai.apiKey)
    }

    private fun saveProfile() {
        val name = binding.displayName.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            toast("显示名不能为空")
            return
        }
        Io.run {
            try {
                val profile = ApiClient(SettingsStore(applicationContext)).updateProfile(name)
                Io.ui {
                    binding.displayName.setText(profile.displayName)
                    renderAccount()
                    toast("显示名已保存")
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "保存失败") }
            }
        }
    }

    private fun changePassword() {
        val oldPassword = binding.oldPassword.text?.toString().orEmpty()
        val newPassword = binding.newPassword.text?.toString().orEmpty()
        if (oldPassword.isBlank() || newPassword.length < 6) {
            toast("请填写当前密码，新密码至少 6 位")
            return
        }
        Io.run {
            try {
                ApiClient(SettingsStore(applicationContext)).changePassword(oldPassword, newPassword)
                Io.ui {
                    binding.oldPassword.setText("")
                    binding.newPassword.setText("")
                    toast("密码已修改")
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "修改失败") }
            }
        }
    }

    private fun checkUpdate() {
        binding.checkUpdate.isEnabled = false
        binding.updateStatus.text = "正在检查…"
        Io.run {
            try {
                val release = AppUpdate.check(applicationContext)
                Io.ui {
                    binding.checkUpdate.isEnabled = true
                    if (!AppUpdate.isNewer(release)) {
                        binding.updateStatus.text = "已是最新版本"
                        return@ui
                    }
                    binding.updateStatus.text = "发现 ${release.version}"
                    AppUpdate.offer(this, release) { binding.updateStatus.text = it }
                }
            } catch (error: Exception) {
                Io.ui {
                    binding.checkUpdate.isEnabled = true
                    binding.updateStatus.text = error.message ?: "检查失败"
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.login.isEnabled = !busy
        binding.register.isEnabled = !busy
    }
}
