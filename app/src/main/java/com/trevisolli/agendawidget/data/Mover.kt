package com.trevisolli.agendawidget.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Muda um item de dia mantendo hora e duração (Todoist pela API, agenda direto no aparelho). */
object Mover {

    fun dateOf(item: AgendaItem, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalDate()

    /** Destino do botão » do widget: atrasada vem para hoje, o resto vai para o dia seguinte. */
    fun nextDay(item: AgendaItem, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val today = LocalDate.now(zone)
        return if (item.overdue || dateOf(item, zone).isBefore(today)) today else dateOf(item, zone).plusDays(1)
    }

    /** Lança exceção com mensagem curta quando não dá. */
    fun move(context: Context, item: AgendaItem, to: LocalDate) {
        val key = item.moveKey ?: throw IllegalStateException("esse item não pode ser movido")
        val zone = ZoneId.systemDefault()
        when {
            key.startsWith("T:") -> {
                val token = Settings(context).todoistToken
                if (token.isBlank()) throw IllegalStateException("Todoist não conectado")
                val time = if (item.dateOnly) null else Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalTime()
                TodoistSource.reschedule(token, key.removePrefix("T:"), to, time)
            }
            key.startsWith("E:") -> moveEvent(context, key.removePrefix("E:").toLong(), item, to, zone)
            else -> throw IllegalStateException("item desconhecido")
        }
    }

    private fun moveEvent(context: Context, eventId: Long, item: AgendaItem, to: LocalDate, zone: ZoneId) {
        if (!CalendarWriter.canWrite(context)) throw IllegalStateException("sem permissão para editar a agenda")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        var start = 0L
        var end = 0L
        var allDay = false
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) throw IllegalStateException("evento não encontrado")
            start = c.getLong(0)
            end = if (c.isNull(1)) 0L else c.getLong(1)
            allDay = c.getInt(2) == 1
        } ?: throw IllegalStateException("evento não encontrado")

        val values = ContentValues()
        if (allDay) {
            // Dia inteiro fica em meia-noite UTC.
            val from = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
            val days = ChronoUnit.DAYS.between(from, to)
            values.put(CalendarContract.Events.DTSTART, from.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            if (end > 0) {
                val endDate = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                values.put(CalendarContract.Events.DTEND, endDate.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            }
        } else {
            val from = Instant.ofEpochMilli(start).atZone(zone)
            val days = ChronoUnit.DAYS.between(from.toLocalDate(), to)
            values.put(CalendarContract.Events.DTSTART, from.plusDays(days).toInstant().toEpochMilli())
            if (end > 0) values.put(CalendarContract.Events.DTEND, Instant.ofEpochMilli(end).atZone(zone).plusDays(days).toInstant().toEpochMilli())
        }
        if (context.contentResolver.update(uri, values, null, null) < 1) throw IllegalStateException("a agenda não aceitou a mudança")
    }

    /** Cópia local do item já no novo dia, para a tela mudar antes da próxima atualização. */
    fun shifted(item: AgendaItem, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): AgendaItem {
        val delta = ChronoUnit.DAYS.between(dateOf(item, zone), to)
        val start = Instant.ofEpochMilli(item.startMillis).atZone(zone).plusDays(delta).toInstant().toEpochMilli()
        val end = item.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone).plusDays(delta).toInstant().toEpochMilli() }
        return item.copy(startMillis = start, endMillis = end, overdue = false)
    }
}
