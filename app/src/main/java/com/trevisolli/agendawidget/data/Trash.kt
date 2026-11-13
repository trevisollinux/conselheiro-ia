package com.trevisolli.agendawidget.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Apagar com volta: antes de apagar, guarda uma cópia do evento (agenda) ou da tarefa (Todoist).
 * A cópia vai no "Desfazer" da conversa; restaurar cria o item de novo (com outro id).
 */
object Trash {
    /** Apaga "E:<id>" ou "T:<id>" e devolve a cópia (JSON) para restaurar. Lança exceção se falhar. */
    fun delete(context: Context, key: String): String {
        val id = key.substringAfter(':')
        return if (key.startsWith("T:")) {
            val token = Settings(context).todoistToken
            val copy = taskCopy(token, id)
            TodoistSource.delete(token, id)
            copy.toString()
        } else {
            check(CalendarWriter.canWrite(context)) { "sem permissão de criar eventos" }
            val copy = eventCopy(context, id.toLong()) ?: error("evento não encontrado")
            val n = context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong()), null, null)
            check(n > 0) { "a agenda recusou apagar" }
            copy.toString()
        }
    }

    /** Recria o item a partir da cópia guardada. */
    fun restore(context: Context, copy: String) {
        val o = JSONObject(copy)
        if (o.optString("kind") == "task") restoreTask(Settings(context).todoistToken, o) else restoreEvent(context, o)
    }

    /** Nome do item guardado, para mostrar na conversa. */
    fun title(copy: String): String = runCatching { JSONObject(copy).optString("title") }.getOrDefault("")

    // ---------- Agenda ----------

    private val EVENT_COLUMNS = arrayOf(
        CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION,
        CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
        CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.HAS_ALARM,
    )

    private fun eventCopy(context: Context, id: Long): JSONObject? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val o = context.contentResolver.query(uri, EVENT_COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            JSONObject()
                .put("kind", "event")
                .put("calendar", c.getLong(0))
                .put("title", c.getString(1).orEmpty())
                .put("description", c.getString(2).orEmpty())
                .put("location", c.getString(3).orEmpty())
                .put("start", c.getLong(4))
                .put("end", if (c.isNull(5)) c.getLong(4) + 3_600_000 else c.getLong(5))
                .put("allDay", c.getInt(6))
                .put("tz", c.getString(7) ?: "UTC")
        } ?: return null
        // Lembretes (minutos antes) voltam junto.
        val reminders = JSONArray()
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(id.toString()), null,
        )?.use { c -> while (c.moveToNext()) reminders.put(JSONObject().put("m", c.getInt(0)).put("method", c.getInt(1))) }
        return o.put("reminders", reminders)
    }

    private fun restoreEvent(context: Context, o: JSONObject) {
        check(CalendarWriter.canWrite(context)) { "sem permissão de criar eventos" }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, o.getLong("calendar"))
            put(CalendarContract.Events.TITLE, o.optString("title"))
            o.optString("description").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            o.optString("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.DTSTART, o.getLong("start"))
            put(CalendarContract.Events.DTEND, o.getLong("end"))
            put(CalendarContract.Events.ALL_DAY, o.optInt("allDay"))
            put(CalendarContract.Events.EVENT_TIMEZONE, o.optString("tz", "UTC"))
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("a agenda recusou recriar")
        val newId = ContentUris.parseId(uri)
        val reminders = o.optJSONArray("reminders") ?: JSONArray()
        for (i in 0 until reminders.length()) {
            val r = reminders.getJSONObject(i)
            runCatching {
                context.contentResolver.insert(
                    CalendarContract.Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, newId)
                        put(CalendarContract.Reminders.MINUTES, r.getInt("m"))
                        put(CalendarContract.Reminders.METHOD, r.getInt("method"))
                    },
                )
            }
        }
    }

    // ---------- Todoist ----------

    private fun taskUrl(id: String) = "https://api.todoist.com/api/v1/tasks/" + URLEncoder.encode(id, "UTF-8")

    private fun taskCopy(token: String, id: String): JSONObject {
        val t = Http.getJson(taskUrl(id), token)
        val subtasks = JSONArray().apply { TodoistSource.subtasks(token, id).forEach { put(it.second) } }
        return JSONObject()
            .put("kind", "task")
            .put("title", t.optString("content"))
            .put("description", t.optString("description"))
            .put("project", t.optString("project_id"))
            .put("priority", t.optInt("priority", 1))
            .put("labels", t.optJSONArray("labels") ?: JSONArray())
            .put("due", t.optJSONObject("due") ?: JSONObject.NULL)
            .put("subtasks", subtasks)
    }

    private fun restoreTask(token: String, o: JSONObject) {
        val body = JSONObject()
            .put("content", o.optString("title"))
            .put("priority", o.optInt("priority", 1))
            .put("labels", o.optJSONArray("labels") ?: JSONArray())
        o.optString("description").takeIf { it.isNotBlank() }?.let { body.put("description", it) }
        o.optString("project").takeIf { it.isNotBlank() }?.let { body.put("project_id", it) }
        o.optJSONObject("due")?.let { due ->
            val recurring = due.optBoolean("is_recurring")
            val datetime = due.optString("datetime").takeIf { !due.isNull("datetime") && it.isNotBlank() }
            when {
                // Recorrente: a regra em texto traz a repetição de volta.
                recurring && due.optString("string").isNotBlank() ->
                    body.put("due_string", due.optString("string")).put("due_lang", due.optString("lang", "en"))
                // Hora "flutuante" (sem fuso) vira UTC, como a API pede.
                datetime != null -> body.put(
                    "due_datetime",
                    TodoistSource.parseDue(due, java.time.ZoneId.systemDefault())
                        ?.let { java.time.Instant.ofEpochMilli(it.first).toString() } ?: datetime,
                )
                due.optString("date").isNotBlank() -> body.put("due_date", due.optString("date").take(10))
            }
        }
        val res = Http.postJson("https://api.todoist.com/api/v1/tasks", mapOf("Authorization" to "Bearer $token"), body)
        val newId = res.optString("id").ifBlank { error("o Todoist recusou recriar") }
        val subtasks = o.optJSONArray("subtasks") ?: JSONArray()
        for (i in 0 until subtasks.length()) {
            runCatching { TodoistSource.create(token, subtasks.getString(i), null, null, null, parentId = newId) }
        }
    }
}
