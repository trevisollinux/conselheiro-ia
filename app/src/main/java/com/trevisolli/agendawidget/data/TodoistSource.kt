package com.trevisolli.agendawidget.data

import org.json.JSONObject
import java.time.ZonedDateTime
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Tarefas do Todoist com data: atrasadas e dos próximos dias (API v1, token pessoal). */
object TodoistSource {
    private const val COLOR = 0xFFE44332.toInt()

    fun fetch(token: String, days: Int): List<AgendaItem> {
        val query = URLEncoder.encode("overdue | next $days days", "UTF-8")
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val items = mutableListOf<AgendaItem>()
        var cursor: String? = null
        do {
            var url = "https://api.todoist.com/api/v1/tasks/filter?query=$query&limit=200"
            if (cursor != null) url += "&cursor=" + URLEncoder.encode(cursor, "UTF-8")
            val page = Http.getJson(url, token)
            val results = page.getJSONArray("results")
            for (i in 0 until results.length()) {
                val task = results.getJSONObject(i)
                val due = task.optJSONObject("due") ?: continue
                val parsed = parseDue(due, zone) ?: continue
                val (start, dateOnly) = parsed
                val end = if (dateOnly) null else durationEnd(task.optJSONObject("duration"), start)
                val overdue = if (dateOnly) {
                    start < today.atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    (end ?: start) < now
                }
                items += AgendaItem(
                    title = task.optString("content").ifBlank { "(sem título)" },
                    source = Source.TODOIST,
                    startMillis = start,
                    endMillis = end,
                    dateOnly = dateOnly,
                    overdue = overdue,
                    color = COLOR,
                    openUri = "https://app.todoist.com/app/task/" + task.optString("id"),
                )
            }
            cursor = page.optString("next_cursor").takeIf { !page.isNull("next_cursor") && it.isNotBlank() }
        } while (cursor != null)
        return items
    }

    /** Cria uma tarefa; com hora vira due_datetime (UTC), sem hora vira due_date. */
    fun create(token: String, title: String, date: LocalDate?, time: LocalTime?, description: String?) {
        val body = JSONObject().put("content", title)
        if (date != null) {
            if (time != null) {
                body.put("due_datetime", ZonedDateTime.of(date, time, ZoneId.systemDefault()).toInstant().toString())
            } else {
                body.put("due_date", date.toString())
            }
        }
        if (!description.isNullOrBlank()) body.put("description", description.take(1000))
        Http.postJson("https://api.todoist.com/api/v1/tasks", mapOf("Authorization" to "Bearer $token"), body)
    }

    /** Aceita "2026-10-21", "2026-10-21T09:00:00" (hora local) e "2026-10-21T12:00:00Z". */
    internal fun parseDue(due: JSONObject, zone: ZoneId): Pair<Long, Boolean>? {
        val raw = due.optString("datetime").takeIf { !due.isNull("datetime") && it.isNotBlank() }
            ?: due.optString("date").takeIf { it.isNotBlank() }
            ?: return null
        return try {
            if (raw.length == 10) {
                LocalDate.parse(raw).atStartOfDay(zone).toInstant().toEpochMilli() to true
            } else {
                val millis = try {
                    OffsetDateTime.parse(raw).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
                }
                millis to false
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun durationEnd(duration: JSONObject?, start: Long): Long? {
        if (duration == null) return null
        val amount = duration.optLong("amount", 0)
        if (amount <= 0) return null
        return when (duration.optString("unit")) {
            "minute" -> start + amount * 60_000
            else -> null
        }
    }
}
