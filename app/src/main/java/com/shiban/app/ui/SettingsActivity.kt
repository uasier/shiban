package com.shiban.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.shiban.app.BuildConfig
import com.shiban.app.R
import com.shiban.app.data.Account
import com.shiban.app.data.AppUpdate
import com.shiban.app.data.openChangelog
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.Sync
import com.shiban.app.databinding.ActivitySettingsBinding
import com.shiban.app.net.ApiClient
import com.shiban.app.net.HealthReport
import com.shiban.app.util.Io
import com.shiban.app.util.toast

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { SettingsStore(this) }
    private var page = PAGE_ACCOUNT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.probeServer.setOnClickListener { probeServer() }
        page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_ACCOUNT
        applyPage(page)
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
        binding.presetGrok.setOnClickListener { applyPreset(deepseek = false) }
        binding.presetDeepseek.setOnClickListener { applyPreset(deepseek = true) }
        showPresetNote()
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
        binding.github.setOnClickListener { loginWithGithub() }
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

    private fun applyPage(page: String) {
        binding.pageServer.isVisible = page == PAGE_SERVER
        binding.pageAccount.isVisible = page == PAGE_ACCOUNT || page == PAGE_MODEL
        if (page == PAGE_MODEL) {
            for (index in 0 until binding.pageAccount.childCount) {
                val child = binding.pageAccount.getChildAt(index)
                child.isVisible = child.id == R.id.aiSection || child.id == R.id.accountStatus
            }
            binding.aiSection.isVisible = settings.isLoggedIn
            binding.accountStatus.isVisible = !settings.isLoggedIn
            if (!settings.isLoggedIn) binding.accountStatus.text = "请先登录"
        } else {
            binding.aiSection.isVisible = false
        }
        binding.pageAbout.isVisible = page == PAGE_ABOUT
        binding.accountStatus.isVisible = page == PAGE_ACCOUNT
        binding.displayName.isVisible = page == PAGE_ACCOUNT
        binding.username.isVisible = page == PAGE_ACCOUNT
        binding.password.isVisible = page == PAGE_ACCOUNT
        binding.showPassword.isVisible = page == PAGE_ACCOUNT
        binding.login.isVisible = page == PAGE_ACCOUNT
        binding.register.isVisible = page == PAGE_ACCOUNT
        binding.github.isVisible = page == PAGE_ACCOUNT
        binding.saveProfile.isVisible = page == PAGE_ACCOUNT
        binding.oldPassword.isVisible = page == PAGE_ACCOUNT
        binding.newPassword.isVisible = page == PAGE_ACCOUNT
        binding.changePassword.isVisible = page == PAGE_ACCOUNT
        binding.logout.isVisible = page == PAGE_ACCOUNT
        titleView()?.text = when (page) {
            PAGE_SERVER -> "服务"
            PAGE_MODEL -> "模型"
            PAGE_ABOUT -> "关于"
            else -> "账号"
        }
    }

    private fun titleView() = binding.title

    private fun renderAccount() {
        if (page == PAGE_MODEL) {
            applyPage(page)
            return
        }
        if (page != PAGE_ACCOUNT) return
        val loggedIn = settings.isLoggedIn
        binding.accountStatus.text = if (loggedIn) "已登录 ${settings.displayName}" else "未登录"
        binding.labelDisplayName.isVisible = true
        binding.displayName.isVisible = true
        binding.labelUsername.isVisible = !loggedIn
        binding.username.isVisible = !loggedIn
        binding.labelPassword.isVisible = !loggedIn
        binding.password.isVisible = !loggedIn
        binding.showPassword.isVisible = !loggedIn
        binding.login.isVisible = !loggedIn
        binding.register.isVisible = !loggedIn
        binding.github.isVisible = true
        binding.github.text = if (loggedIn) "绑定 GitHub" else "使用 GitHub 登录"
        binding.saveProfile.isVisible = loggedIn
        binding.labelChangePassword.isVisible = loggedIn
        binding.oldPassword.isVisible = loggedIn
        binding.newPassword.isVisible = loggedIn
        binding.changePassword.isVisible = loggedIn
        binding.logout.isVisible = loggedIn
    }

    private fun submit(register: Boolean) {
        val typedServer = binding.serverUrl.text?.toString().orEmpty().trim()
        if (typedServer.isNotBlank()) settings.serverUrl = typedServer
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
            try {
                val client = ApiClient(SettingsStore(applicationContext))
                val profile = if (register) {
                    client.register(username, password, displayName)
                } else {
                    client.login(username, password)
                }
                Account.adopt(applicationContext, previousUserId, profile.id)
                val syncError = try {
                    Sync.pull(applicationContext)
                    null
                } catch (error: Exception) {
                    error.message ?: "同步失败"
                }
                Io.ui {
                    setBusy(false)
                    renderAccount()
                    toast(if (syncError == null) "已登录" else "已登录，同步稍后再试")
                    finish()
                }
            } catch (error: Exception) {
                Io.ui {
                    setBusy(false)
                    binding.status.isVisible = true
                    binding.status.text = error.message ?: "登录失败"
                    renderAccount()
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
        showPresetNote()
    }

    private fun applyPreset(deepseek: Boolean) {
        binding.aiBaseUrl.setText(if (deepseek) SettingsStore.DEEPSEEK_AI_URL else SettingsStore.DEFAULT_AI_URL)
        binding.aiModel.setText(if (deepseek) SettingsStore.DEEPSEEK_AI_MODEL else SettingsStore.DEFAULT_AI_MODEL)
        binding.aiApiKey.setText("")
        binding.aiApiKey.hint = if (deepseek) "sk-..." else "xai-..."
        showPresetNote()
        toast(if (deepseek) "已切换到 DeepSeek，请填写它的密钥" else "已切换到 Grok，请填写它的密钥")
    }

    private fun showPresetNote() {
        val deepseek = binding.aiBaseUrl.text?.toString().orEmpty().contains("deepseek", ignoreCase = true)
        binding.aiPresetNote.isVisible = deepseek
        binding.aiApiKey.hint = if (deepseek) "sk-..." else "xai-..."
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

    private fun probeServer() {
        val url = binding.serverUrl.text?.toString().orEmpty()
        binding.serverHealth.text = "正在检查…"
        binding.serverHealth.setTextColor(ContextCompat.getColor(this, R.color.muted))
        binding.probeServer.isEnabled = false
        Io.run {
            val report = ApiClient(SettingsStore(applicationContext)).probe(url)
            Io.ui {
                if (isFinishing) return@ui
                showHealth(report)
                binding.probeServer.isEnabled = true
            }
        }
    }

    private fun showHealth(report: HealthReport) {
        binding.serverHealth.text = report.headline
        binding.serverHealth.setTextColor(ContextCompat.getColor(this, if (report.ok) R.color.moss else R.color.danger))
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

    private var githubTicket = 0

    private fun loginWithGithub() {
        val ticket = ++githubTicket
        val previousUserId = settings.userId
        val bind = settings.isLoggedIn
        setBusy(true)
        binding.status.isVisible = true
        binding.status.text = "正在打开 GitHub…"
        Io.run {
            try {
                val client = ApiClient(SettingsStore(applicationContext))
                val start = client.githubStart(bind)
                Io.ui {
                    if (isDestroyed || ticket != githubTicket) return@ui
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(start.authUrl)))
                    binding.status.text = "请在浏览器完成 GitHub 授权"
                }
                var done = false
                for (attempt in 1..90) {
                    if (ticket != githubTicket) return@run
                    Thread.sleep(2000)
                    val json = client.githubPoll(start.pollId)
                    if (json.optBoolean("pending")) continue
                    val profile = client.completeSession(json)
                    if (!bind) Account.adopt(applicationContext, previousUserId, profile.id)
                    done = true
                    break
                }
                if (!done) throw com.shiban.app.net.ApiException("GitHub 登录超时")
                val syncError = if (bind) {
                    null
                } else {
                    try {
                        Sync.pull(applicationContext)
                        null
                    } catch (error: Exception) {
                        error.message ?: "同步失败"
                    }
                }
                Io.ui {
                    if (isDestroyed) return@ui
                    setBusy(false)
                    renderAccount()
                    toast(when {
                        bind -> "已绑定 GitHub"
                        syncError == null -> "已登录"
                        else -> "已登录，同步稍后再试"
                    })
                    if (!bind) finish()
                }
            } catch (error: Exception) {
                Io.ui {
                    if (isDestroyed) return@ui
                    setBusy(false)
                    binding.status.isVisible = true
                    binding.status.text = error.message ?: "GitHub 登录失败"
                    renderAccount()
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.login.isEnabled = !busy
        binding.register.isEnabled = !busy
        binding.github.isEnabled = !busy
    }

    companion object {
        const val PAGE_ACCOUNT = "account"
        const val PAGE_SERVER = "server"
        const val PAGE_MODEL = "model"
        const val PAGE_ABOUT = "about"
        private const val EXTRA_PAGE = "page"

        fun open(context: android.content.Context, page: String) =
            android.content.Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}
