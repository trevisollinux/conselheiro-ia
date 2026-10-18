package com.trevisolli.agendawidget.data

import android.content.Context
import android.util.Base64
import com.trevisolli.agendawidget.smart.AiProvider
import org.json.JSONObject

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

    /** false = modo automático (regras no aparelho); true = IA do usuário. */
    var useAi: Boolean
        get() = prefs.getBoolean("use_ai", false)
        set(value) = prefs.edit().putBoolean("use_ai", value).apply()

    var aiProvider: AiProvider
        get() = AiProvider.entries.firstOrNull { it.name == prefs.getString("ai_provider", null) } ?: AiProvider.CLAUDE
        set(value) = prefs.edit().putString("ai_provider", value.name).apply()

    var aiKey: String
        get() = prefs.getString("ai_key", "").orEmpty()
        set(value) = prefs.edit().putString("ai_key", value.trim()).apply()

    /** Vazio = modelo padrão do provedor. */
    var aiModel: String
        get() = prefs.getString("ai_model", "").orEmpty()
        set(value) = prefs.edit().putString("ai_model", value.trim()).apply()

    val effectiveModel: String
        get() = aiModel.ifBlank { aiProvider.defaultModel }

    val aiReady: Boolean
        get() = useAi && aiKey.isNotBlank()

    /** Token de integração interna do Notion ("Internal Integration Secret"). */
    var notionToken: String
        get() = prefs.getString("notion_token", "").orEmpty()
        set(value) = prefs.edit().putString("notion_token", value.trim()).apply()

    var notionLastScan: Long
        get() = prefs.getLong("notion_last_scan", 0)
        set(value) = prefs.edit().putLong("notion_last_scan", value).apply()

    /** Trechos de notas já analisados, para não sugerir de novo o que foi descartado. */
    fun markSeen(key: String): Boolean {
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (key in seen) return false
        val next = if (seen.size > 2000) setOf(key) else seen + key
        prefs.edit().putStringSet("seen", next).apply()
        return true
    }

    /** true = paisagem que muda sozinha; false = escuro clássico. */
    var artBackground: Boolean
        get() = prefs.getBoolean("art_bg", true)
        set(value) = prefs.edit().putBoolean("art_bg", value).apply()

    /** Opacidade do fundo do widget, em %. */
    var widgetOpacity: Int
        get() = prefs.getInt("widget_opacity", 70)
        set(value) = prefs.edit().putInt("widget_opacity", value).apply()

    /** Além de aparecer no app, o resumo também chega como notificação. */
    var briefNotify: Boolean
        get() = prefs.getBoolean("brief_notify", true)
        set(value) = prefs.edit().putBoolean("brief_notify", value).apply()

    var briefMorningOn: Boolean
        get() = prefs.getBoolean("brief_morning", true)
        set(value) = prefs.edit().putBoolean("brief_morning", value).apply()

    var briefMorningHour: Int
        get() = prefs.getInt("brief_morning_hour", 8)
        set(value) = prefs.edit().putInt("brief_morning_hour", value).apply()

    var briefEveningOn: Boolean
        get() = prefs.getBoolean("brief_evening", true)
        set(value) = prefs.edit().putBoolean("brief_evening", value).apply()

    var briefEveningHour: Int
        get() = prefs.getInt("brief_evening_hour", 18)
        set(value) = prefs.edit().putInt("brief_evening_hour", value).apply()

    /** Tarefas concluídas pelo widget hoje (reserva caso a API de concluídas falhe). */
    fun logCompletion(title: String) {
        val today = java.time.LocalDate.now().toString()
        val kept = prefs.getStringSet("completions", emptySet()).orEmpty().filter { it.startsWith(today) }.toSet()
        prefs.edit().putStringSet("completions", kept + "$today|$title").apply()
    }

    fun completionsToday(): List<String> {
        val today = java.time.LocalDate.now().toString()
        return prefs.getStringSet("completions", emptySet()).orEmpty()
            .filter { it.startsWith("$today|") }.map { it.substringAfter('|') }
    }

    var detectNotifications: Boolean
        get() = prefs.getBoolean("detect_notifications", false)
        set(value) = prefs.edit().putBoolean("detect_notifications", value).apply()

    var summaryCacheKey: String
        get() = prefs.getString("summary_key", "").orEmpty()
        set(value) = prefs.edit().putString("summary_key", value).apply()

    var summaryCacheText: String
        get() = prefs.getString("summary_text", "").orEmpty()
        set(value) = prefs.edit().putString("summary_text", value).apply()

    /** Conteúdo do QR de backup. Contém token e chave: tratar como senha. */
    fun exportPayload(): String {
        val json = JSONObject()
            .put("todoist", todoistToken)
            .put("days", days)
            .put("useAi", useAi)
            .put("provider", aiProvider.name)
            .put("aiKey", aiKey)
            .put("aiModel", aiModel)
            .put("detect", detectNotifications)
            .put("notion", notionToken)
        return PREFIX + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }

    /** Retorna false se o QR não for do app. */
    fun importPayload(payload: String): Boolean {
        if (!payload.startsWith(PREFIX)) return false
        val json = try {
            JSONObject(String(Base64.decode(payload.removePrefix(PREFIX), Base64.NO_WRAP)))
        } catch (e: Exception) {
            return false
        }
        todoistToken = json.optString("todoist")
        days = json.optInt("days", 7)
        useAi = json.optBoolean("useAi")
        aiProvider = AiProvider.entries.firstOrNull { it.name == json.optString("provider") } ?: AiProvider.CLAUDE
        aiKey = json.optString("aiKey")
        aiModel = json.optString("aiModel")
        detectNotifications = json.optBoolean("detect")
        notionToken = json.optString("notion")
        notionLastScan = 0
        summaryCacheKey = ""
        return true
    }

    private companion object {
        const val PREFIX = "consigliere1:"
    }
}
