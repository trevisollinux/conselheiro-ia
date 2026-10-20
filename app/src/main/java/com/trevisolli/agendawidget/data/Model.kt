package com.trevisolli.agendawidget.data

import org.json.JSONArray
import org.json.JSONObject

enum class Source(val label: String) {
    CALENDAR("Agenda"),
    TODOIST("Todoist"),
}

data class AgendaItem(
    val title: String,
    val source: Source,
    /** Início em millis. Para itens sem hora (dia inteiro), meia-noite local do dia. */
    val startMillis: Long,
    val endMillis: Long?,
    val dateOnly: Boolean,
    val overdue: Boolean,
    val color: Int,
    val openUri: String?,
    /** Id da tarefa no Todoist, para concluir pelo widget. */
    val taskId: String? = null,
    /** "T:<id>" (Todoist) ou "E:<id>" (agenda) quando dá para mudar de dia; null em recorrentes e convites. */
    val moveKey: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("source", source.name)
        .put("start", startMillis)
        .put("end", endMillis ?: JSONObject.NULL)
        .put("dateOnly", dateOnly)
        .put("overdue", overdue)
        .put("color", color)
        .put("uri", openUri ?: JSONObject.NULL)
        .put("taskId", taskId ?: JSONObject.NULL)
        .put("moveKey", moveKey ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = AgendaItem(
            title = o.getString("title"),
            source = Source.valueOf(o.getString("source")),
            startMillis = o.getLong("start"),
            endMillis = if (o.isNull("end")) null else o.getLong("end"),
            dateOnly = o.getBoolean("dateOnly"),
            overdue = o.optBoolean("overdue"),
            color = o.getInt("color"),
            openUri = if (o.isNull("uri")) null else o.getString("uri"),
            taskId = if (o.isNull("taskId")) null else o.optString("taskId").ifBlank { null },
            moveKey = if (o.isNull("moveKey")) null else o.optString("moveKey").ifBlank { null },
        )
    }
}

data class Snapshot(
    val items: List<AgendaItem>,
    val errors: List<String>,
    val updatedAt: Long,
    val summary: String = "",
    val pendingSuggestions: Int = 0,
) {
    fun toJson(): String = JSONObject()
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("errors", JSONArray(errors))
        .put("updatedAt", updatedAt)
        .put("summary", summary)
        .put("pending", pendingSuggestions)
        .toString()

    companion object {
        val EMPTY = Snapshot(emptyList(), emptyList(), 0)

        fun fromJson(json: String?): Snapshot {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                val o = JSONObject(json)
                val items = o.getJSONArray("items")
                val errors = o.getJSONArray("errors")
                Snapshot(
                    items = List(items.length()) { AgendaItem.fromJson(items.getJSONObject(it)) },
                    errors = List(errors.length()) { errors.getString(it) },
                    updatedAt = o.getLong("updatedAt"),
                    summary = o.optString("summary"),
                    pendingSuggestions = o.optInt("pending"),
                )
            } catch (e: Exception) {
                EMPTY
            }
        }
    }
}
