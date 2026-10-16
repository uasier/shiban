package com.shiban.app

import android.app.Application
import com.shiban.app.data.TodoStore
import com.shiban.app.reminder.Reminders
import com.shiban.app.ui.TodoWidget

class ShibanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Reminders.ensureChannel(this)
        TodoStore.onChange = { TodoWidget.updateAll(it) }
    }
}
