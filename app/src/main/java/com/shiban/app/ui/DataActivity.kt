package com.shiban.app.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shiban.app.data.Backup
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.Sync
import com.shiban.app.data.TodoStore
import com.shiban.app.databinding.ActivityDataBinding
import com.shiban.app.reminder.Reminders
import com.shiban.app.util.Io
import com.shiban.app.util.toast

class DataActivity : AppCompatActivity() {
    private val pickBackup = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = applicationContext
        Io.run {
            try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
                val count = Backup.importJson(app, text)
                Io.ui {
                    val synced = SettingsStore(this).isLoggedIn
                    toast(if (synced) "已导入 $count 条" else "已导入 $count 条")
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "导入失败") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.exportTodos.setOnClickListener {
            if (TodoStore.get(this).all().isEmpty()) toast("没有可导出的待办") else Backup.share(this)
        }
        binding.importTodos.setOnClickListener { pickBackup.launch("*/*") }
        binding.clearDone.setOnClickListener { clearCompleted() }
    }

    private fun clearCompleted() {
        val done = TodoStore.get(this).all().filter { it.completedAt != null }
        if (done.isEmpty()) {
            toast("没有已完成的待办")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清理已完成？")
            .setMessage("将删除 ${done.size} 条。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                Io.run {
                    var ok = 0
                    done.forEach { todo ->
                        try {
                            Sync.delete(applicationContext, todo)
                            Reminders.forget(applicationContext, todo.id)
                            ok++
                        } catch (_: Exception) {
                        }
                    }
                    Io.ui { toast("已清理 $ok 条") }
                }
            }
            .show()
    }
}
