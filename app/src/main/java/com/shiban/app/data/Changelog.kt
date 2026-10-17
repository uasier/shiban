package com.shiban.app.data

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shiban.app.BuildConfig
import com.shiban.app.ui.ChangelogActivity

object Changelog {
    data class Release(val name: String, val code: Int, val notes: List<String>)

    val releases = listOf(
        Release(
            "1.5.0",
            19,
            listOf(
                "移动端视觉与交互全面升级，页面更美观大方。",
                "主页支持 1 键快捷相册识图，录入更流畅。",
                "全新分组式功能与设置中心，直观管理账号、模型与数据。",
                "支持 GitHub 登录与账号绑定。",
                "模型页支持切换 Grok 与 DeepSeek 预设。"
            )
        ),
        Release(
            "1.4.13",
            18,
            listOf(
                "模型页可以切换到 DeepSeek。它只识别文字，图片仍用 Grok。"
            )
        ),
        Release(
            "1.4.12",
            17,
            listOf(
                "检查更新改为读取识办服务上的版本。"
            )
        ),
        Release(
            "1.4.11",
            16,
            listOf(
                "账号页可以登录或绑定 GitHub。"
            )
        ),
        Release(
            "1.4.10",
            15,
            listOf(
                "未登录只显示登录，已登录只显示账号操作。登录失效会退出。"
            )
        ),
        Release(
            "1.4.9",
            14,
            listOf(
                "公网改为 https://shiban.uasier.com:40400。"
            )
        ),
        Release(
            "1.4.8",
            13,
            listOf(
                "公网地址不再带端口。官网可以下载安装包。"
            )
        ),
        Release(
            "1.4.7",
            12,
            listOf(
                "公网服务改为 40400 端口。"
            )
        ),
        Release(
            "1.4.6",
            11,
            listOf(
                "账号、服务、模型、数据和关于分成独立页面。"
            )
        ),
        Release(
            "1.4.5",
            10,
            listOf(
                "手机和桌面都可以探活，并明确显示服务端是否工作正常。"
            )
        ),
        Release(
            "1.4.4",
            9,
            listOf(
                "修复安装后打开闪退。"
            )
        ),
        Release(
            "1.4.3",
            8,
            listOf(
                "可以检查是否有新版本，并直接下载安装。"
            )
        ),
        Release(
            "1.4.2",
            7,
            listOf(
                "设置底部可以查看更新记录。",
                "升级后第一次打开，会提示这一版改了什么。"
            )
        ),
        Release(
            "1.4.1",
            6,
            listOf(
                "默认连接公网服务，不用再填电脑的局域网地址。",
                "以前保存的本机或局域网地址会自动换成公网。"
            )
        ),
        Release(
            "1.4.0",
            5,
            listOf(
                "桌面小组件可以看到未完成待办，点圆圈即可完成。",
                "识别错了可以重新识别。",
                "图片识别会多等一会儿，不再那么容易只记下「查看导入的图片」。",
                "10 分钟内同一句不会再记一条。底部输入可以一次粘贴多行。"
            )
        ),
        Release(
            "1.3.0",
            4,
            listOf(
                "没登录或网络失败时，文字和图片会先记在本机，下次自动补传。",
                "提醒可以稍后 10 分钟或 1 小时。错过的到点提醒，打开应用会补一次。",
                "多张图片都会留下。",
                "可以导出、导入待办。"
            )
        ),
        Release(
            "1.2.0",
            3,
            listOf(
                "「明天下午 3 点」「今晚」「下周五」会落到具体提醒时间。",
                "列表先出文字，图片在后台补上。",
                "记下失败会先存在本机。重复的一句会提示已经在待办里。",
                "登录失效会带去重新登录。可以清理已完成。"
            )
        ),
        Release(
            "1.1.0",
            2,
            listOf(
                "勾选和删除会立刻生效，同步失败会留在本机稍后补传。",
                "下拉可以手动同步，滑动更顺。",
                "剪贴板不会每次回到前台都弹出。",
                "通知里点完成会同步到账号。详情里的图片可以点开看大图。"
            )
        )
    )

    fun latest(): Release = releases.first()

    fun notes(release: Release): String = release.notes.joinToString("\n") { "· $it" }

    fun promptIfNeeded(activity: AppCompatActivity): Boolean {
        val settings = SettingsStore(activity)
        if (settings.seenVersionCode >= BuildConfig.VERSION_CODE) return false
        val release = releases.firstOrNull { it.code == BuildConfig.VERSION_CODE } ?: latest()
        settings.seenVersionCode = BuildConfig.VERSION_CODE
        AlertDialog.Builder(activity)
            .setTitle("识办 ${release.name}")
            .setMessage(notes(release))
            .setNegativeButton("更新记录") { _, _ -> activity.startActivity(Intent(activity, ChangelogActivity::class.java)) }
            .setPositiveButton("知道了", null)
            .show()
        return true
    }
}

fun Context.openChangelog() {
    startActivity(Intent(this, ChangelogActivity::class.java))
}
