package com.trevisolli.agendawidget.data

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var todoistToken: String
        get() = prefs.getString("todoist_token", "").orEmpty()
        set(value) = prefs.edit().putString("todoist_token", value.trim()).apply()

    var googleTasksEnabled: Boolean
        get() = prefs.getBoolean("google_tasks", false)
        set(value) = prefs.edit().putBoolean("google_tasks", value).apply()

    /** Quantos dias à frente mostrar. */
    var days: Int
        get() = prefs.getInt("days", 7)
        set(value) = prefs.edit().putInt("days", value).apply()
}
