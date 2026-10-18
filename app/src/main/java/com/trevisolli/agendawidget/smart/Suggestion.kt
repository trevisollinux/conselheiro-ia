package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class Kind { EVENT, TASK }

data class Suggestion(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: LocalDate?,
    val time: LocalTime?,
    val durationMin: Int = 60,
    val kind: Kind,
    /** De onde veio: "Compartilhado", "WhatsApp"… */
    val origin: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("date", date?.toString() ?: JSONObject.NULL)
        .put("time", time?.toString() ?: JSONObject.NULL)
        .put("duration", durationMin)
        .put("kind", kind.name)
        .put("origin", origin)
        .put("text", text)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Suggestion(
            id = o.getString("id"),
            title = o.getString("title"),
            date = if (o.isNull("date")) null else LocalDate.parse(o.getString("date")),
            time = if (o.isNull("time")) null else LocalTime.parse(o.getString("time")),
            durationMin = o.optInt("duration", 60),
            kind = Kind.valueOf(o.getString("kind")),
            origin = o.optString("origin"),
            text = o.optString("text"),
            createdAt = o.optLong("createdAt"),
        )
    }
}

/** Sugestões pendentes de revisão (detectadas nas notificações). */
object SuggestionStore {
    private const val MAX = 30
    private fun file(context: Context) = File(context.filesDir, "suggestions.json")

    @Synchronized
    fun all(context: Context): List<Suggestion> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { Suggestion.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: String): Suggestion? = all(context).firstOrNull { it.id == id }

    /** Retorna false se já existia uma sugestão com o mesmo texto. */
    @Synchronized
    fun add(context: Context, s: Suggestion): Boolean {
        val current = all(context)
        if (current.any { it.text == s.text }) return false
        save(context, (listOf(s) + current).take(MAX))
        return true
    }

    @Synchronized
    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.id == id })

    private fun save(context: Context, list: List<Suggestion>) {
        file(context).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }
}
