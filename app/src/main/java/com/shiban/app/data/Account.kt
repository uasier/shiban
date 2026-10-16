package com.shiban.app.data

import android.content.Context
import com.shiban.app.reminder.Reminders

object Account {
    fun signOut(context: Context) {
        val store = TodoStore.get(context)
        store.all().forEach { Reminders.cancel(context, it.id) }
        Reminders.clearLocalState(context)
        store.clearAll()
        SettingsStore(context).logout()
    }

    fun adopt(context: Context, previousUserId: String, newUserId: String) {
        if (previousUserId.isNotBlank() && previousUserId != newUserId) {
            val store = TodoStore.get(context)
            store.all().forEach { Reminders.cancel(context, it.id) }
            Reminders.clearLocalState(context)
            store.clearAll()
        }
        SettingsStore(context).userId = newUserId
    }
}
