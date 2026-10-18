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

    /** Fundo do widget. Migra o antigo liga/desliga da paisagem. */
    var widgetTheme: WidgetTheme
        get() = WidgetTheme.entries.firstOrNull { it.name == prefs.getString("widget_theme", null) }
            ?: if (artBackground) WidgetTheme.LANDSCAPE else WidgetTheme.DARK
        set(value) = prefs.edit().putString("widget_theme", value.name).apply()

    /** Cena fixa da paisagem; vazio = muda sozinha. */
    var sceneChoice: String
        get() = prefs.getString("scene_choice", "").orEmpty()
        set(value) = prefs.edit().putString("scene_choice", value).apply()

    var artStyle: ArtStyle
        get() = ArtStyle.entries.firstOrNull { it.name == prefs.getString("art_style", null) } ?: ArtStyle.GENERAL
        set(value) = prefs.edit().putString("art_style", value.name).apply()

    /** De quanto em quanto tempo a paisagem/obra muda. */
    var rotateMinutes: Int
        get() = prefs.getInt("rotate_minutes", 60)
        set(value) = prefs.edit().putInt("rotate_minutes", value).apply()

    /** Cidade para a previsão do tempo (vazio = sem clima). */
    var city: String
        get() = prefs.getString("city", "").orEmpty()
        set(value) = prefs.edit().putString("city", value.trim()).apply()

    /** true = acompanha a localização do aparelho em vez da cidade digitada. */
    var useLocation: Boolean
        get() = prefs.getBoolean("use_location", false)
        set(value) = prefs.edit().putBoolean("use_location", value).apply()

    var cityLat: Double
        get() = prefs.getFloat("city_lat", 0f).toDouble()
        set(value) = prefs.edit().putFloat("city_lat", value.toFloat()).apply()

    var cityLon: Double
        get() = prefs.getFloat("city_lon", 0f).toDouble()
        set(value) = prefs.edit().putFloat("city_lon", value.toFloat()).apply()

    /** Estado da obra de arte atual. */
    var artFetchedAt: Long
        get() = prefs.getLong("art_fetched_at", 0)
        set(value) = prefs.edit().putLong("art_fetched_at", value).apply()

    var artFetchedStyle: String
        get() = prefs.getString("art_fetched_style", "").orEmpty()
        set(value) = prefs.edit().putString("art_fetched_style", value).apply()

    /** Último problema ao buscar obra (mostrado só nas Configurações, nunca no widget). */
    var artError: String
        get() = prefs.getString("art_error", "").orEmpty()
        set(value) = prefs.edit().putString("art_error", value).apply()

    var artCaption: String
        get() = prefs.getString("art_caption", "").orEmpty()
        set(value) = prefs.edit().putString("art_caption", value).apply()

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

    /** Como o assistente te chama ("Bom dia, Gustavo"). */
    var userName: String
        get() = prefs.getString("user_name", "").orEmpty()
        set(value) = prefs.edit().putString("user_name", value.trim()).apply()

    /** Período da lista no widget e na aba Agenda. */
    var widgetRange: Range
        get() = Range.entries.firstOrNull { it.name == prefs.getString("widget_range", null) } ?: Range.WEEK
        set(value) = prefs.edit().putString("widget_range", value.name).apply()

    /** Agenda onde a conversa cria eventos; -1 = escolher automaticamente. */
    var targetCalendarId: Long
        get() = prefs.getLong("target_calendar", -1L)
        set(value) = prefs.edit().putLong("target_calendar", value).apply()

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
            .put("name", userName)
            .put("city", city)
            .put("lat", cityLat)
            .put("lon", cityLon)
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
        userName = json.optString("name")
        city = json.optString("city")
        cityLat = json.optDouble("lat", 0.0)
        cityLon = json.optDouble("lon", 0.0)
        notionLastScan = 0
        summaryCacheKey = ""
        return true
    }

    private companion object {
        const val PREFIX = "consigliere1:"
    }
}

enum class Range(val label: String, val days: Long) {
    DAY("Hoje", 0),
    WEEK("Semana", 7),
    MONTH("Mês", 31),
    ;

    /** Itens atrasados sempre entram; o resto até o fim do período. */
    fun includes(item: AgendaItem, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean {
        if (item.overdue) return true
        val date = java.time.Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalDate()
        return !date.isAfter(java.time.LocalDate.now(zone).plusDays(days))
    }
}

enum class WidgetTheme(val label: String) {
    LANDSCAPE("Paisagem"),
    ART("Obras de arte"),
    LIGHT("Claro"),
    DARK("Escuro"),
    NOTEBOOK("Caderno"),
    POSTIT("Post-it"),
}

/**
 * Estilos de obra do Met Museum (domínio público, sem chave). Cada estilo junta algumas buscas;
 * "isHighlight" = só os destaques escolhidos pelos curadores do museu.
 */
enum class ArtStyle(val label: String, val queries: List<String>) {
    GENERAL(
        "Geral",
        listOf("isHighlight=true&hasImages=true&departmentId=11&q=painting"),
    ),
    JAPANESE(
        "Japonesa",
        listOf(
            "isHighlight=true&hasImages=true&departmentId=6&artistOrCulture=true&q=Japan",
            "hasImages=true&artistOrCulture=true&q=Hokusai",
            "hasImages=true&artistOrCulture=true&q=Hiroshige",
        ),
    ),
    RENAISSANCE(
        "Renascença",
        listOf("isHighlight=true&hasImages=true&departmentId=11&dateBegin=1400&dateEnd=1600&q=painting"),
    ),
    IMPRESSIONISM(
        "Impressionismo",
        listOf(
            "isHighlight=true&hasImages=true&departmentId=11&dateBegin=1860&dateEnd=1910&q=landscape",
            "isHighlight=true&hasImages=true&artistOrCulture=true&q=Monet",
        ),
    ),
    SOCIAL_REALISM(
        "Realismo social",
        listOf(
            "hasImages=true&departmentId=11&artistOrCulture=true&q=Millet",
            "hasImages=true&departmentId=11&artistOrCulture=true&q=Courbet",
            "hasImages=true&departmentId=11&artistOrCulture=true&q=Daumier",
            "hasImages=true&departmentId=1&artistOrCulture=true&q=Bellows",
            "hasImages=true&departmentId=1&artistOrCulture=true&q=Sloan",
        ),
    ),
}
