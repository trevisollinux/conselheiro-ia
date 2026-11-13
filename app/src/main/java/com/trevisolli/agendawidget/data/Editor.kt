package com.trevisolli.agendawidget.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Edita evento da agenda ou tarefa do Todoist (título, dia, hora, duração).
 * Cada edição devolve uma cópia do estado anterior (JSON) para o "Desfazer".
 */
object Editor {
    data class Change(val title: String? = null, val date: LocalDate? = null, val time: LocalTime? = null, val minutes: Int? = null)

    /** Aplica a mudança em "E:<id>" ou "T:<id>". Devolve o estado anterior. Lança exceção com mensagem curta. */
    fun edit(context: Context, key: String, change: Change): String {
        val id = key.substringAfter(':')
        return if (key.startsWith("T:")) editTask(Settings(context).todoistToken, id, change) else editEvent(context, id.toLong(), change)
    }

    /** Volta ao estado guardado por [edit]. */
    fun revert(context: Context, before: String) {
        val o = JSONObject(before)
        if (o.optString("kind") == "task") {
            val token = Settings(context).todoistToken
            val body = JSONObject().put("content", o.optString("title"))
            val due = o.optJSONObject("due")
            when {
                due == null -> body.put("due_string", "no date")
                due.optString("datetime").isNotBlank() && !due.isNull("datetime") ->
                    body.put("due_datetime", TodoistSource.parseDue(due, ZoneId.systemDefault())?.let { Instant.ofEpochMilli(it.first).toString() })
                else -> body.put("due_date", due.optString("date").take(10))
            }
            Http.postJson(taskUrl(o.getString("id")), mapOf("Authorization" to "Bearer $token"), body)
        } else {
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, o.optString("title"))
                put(CalendarContract.Events.DTSTART, o.getLong("start"))
                put(CalendarContract.Events.DTEND, o.getLong("end"))
                put(CalendarContract.Events.ALL_DAY, o.optInt("allDay"))
                put(CalendarContract.Events.EVENT_TIMEZONE, o.optString("tz", "UTC"))
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, o.getLong("id"))
            check(context.contentResolver.update(uri, values, null, null) > 0) { "a agenda não aceitou desfazer" }
        }
    }

    private fun taskUrl(id: String) = "https://api.todoist.com/api/v1/tasks/" + URLEncoder.encode(id, "UTF-8")

    private fun editEvent(context: Context, id: Long, change: Change): String {
        check(CalendarWriter.canWrite(context)) { "sem permissão para editar a agenda (libere \"Criar eventos\")" }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val before = context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_TIMEZONE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else JSONObject()
                .put("kind", "event").put("id", id)
                .put("title", c.getString(0).orEmpty())
                .put("start", c.getLong(1))
                .put("end", if (c.isNull(2)) c.getLong(1) + 3_600_000 else c.getLong(2))
                .put("allDay", c.getInt(3))
                .put("tz", c.getString(4) ?: "UTC")
        } ?: error("evento não encontrado")

        val values = ContentValues()
        change.title?.takeIf { it.isNotBlank() }?.let { values.put(CalendarContract.Events.TITLE, it) }
        if (change.date != null || change.time != null || change.minutes != null) {
            val times = newTimes(
                before.getLong("start"), before.getLong("end"), before.getInt("allDay") == 1,
                change, ZoneId.systemDefault(),
            )
            values.put(CalendarContract.Events.DTSTART, times.start)
            values.put(CalendarContract.Events.DTEND, times.end)
            values.put(CalendarContract.Events.ALL_DAY, if (times.allDay) 1 else 0)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, if (times.allDay) "UTC" else ZoneId.systemDefault().id)
        }
        check(values.size() > 0) { "nada para mudar" }
        check(context.contentResolver.update(uri, values, null, null) > 0) { "a agenda não aceitou a mudança" }
        return before.toString()
    }

    data class Times(val start: Long, val end: Long, val allDay: Boolean)

    /**
     * Novo início/fim: mantém o que não mudou (dia, hora, duração).
     * Dia inteiro que ganha hora vira evento com hora (1 h, se a duração não vier).
     */
    internal fun newTimes(start: Long, end: Long, allDay: Boolean, change: Change, zone: ZoneId): Times {
        if (allDay && change.time == null) {
            val from = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
            val days = maxOf(1L, java.time.temporal.ChronoUnit.DAYS.between(from, Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()))
            val to = change.date ?: from
            return Times(
                to.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                to.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                true,
            )
        }
        val old = if (allDay) {
            Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone)
        } else {
            Instant.ofEpochMilli(start).atZone(zone)
        }
        val minutes = change.minutes?.toLong() ?: if (allDay) 60L else maxOf(5L, (end - start) / 60_000)
        val newStart = ZonedDateTime.of(change.date ?: old.toLocalDate(), change.time ?: old.toLocalTime(), zone)
        return Times(newStart.toInstant().toEpochMilli(), newStart.plusMinutes(minutes).toInstant().toEpochMilli(), false)
    }

    private fun editTask(token: String, id: String, change: Change): String {
        check(token.isNotBlank()) { "Todoist não conectado" }
        val t = Http.getJson(taskUrl(id), token)
        val due = t.optJSONObject("due")
        val before = JSONObject().put("kind", "task").put("id", id).put("title", t.optString("content")).put("due", due ?: JSONObject.NULL)
        val body = JSONObject()
        change.title?.takeIf { it.isNotBlank() }?.let { body.put("content", it) }
        if (change.date != null || change.time != null) {
            check(due?.optBoolean("is_recurring") != true) { "tarefa recorrente: mude o dia ou a hora no Todoist para não perder a repetição" }
            val zone = ZoneId.systemDefault()
            val current = due?.let { TodoistSource.parseDue(it, zone) }
            val oldDate = current?.let { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() } ?: LocalDate.now(zone)
            val oldTime = current?.takeIf { !it.second }?.let { Instant.ofEpochMilli(it.first).atZone(zone).toLocalTime() }
            val date = change.date ?: oldDate
            val time = change.time ?: oldTime
            if (time != null) body.put("due_datetime", ZonedDateTime.of(date, time, zone).toInstant().toString())
            else body.put("due_date", date.toString())
        }
        check(body.length() > 0) { "nada para mudar" }
        Http.postJson(taskUrl(id), mapOf("Authorization" to "Bearer $token"), body)
        return before.toString()
    }
}
