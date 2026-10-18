package com.trevisolli.agendawidget.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Cria e apaga eventos direto na agenda do aparelho (sincroniza com o Google sozinho). */
object CalendarWriter {

    data class Cal(val id: Long, val name: String, val account: String, val primary: Boolean)

    fun canWrite(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun writableCalendars(context: Context): List<Cal> {
        if (!CalendarSource.hasPermission(context)) return emptyList()
        val out = mutableListOf<Cal>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val google = c.getString(4) == "com.google"
                out += Cal(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), google && c.getInt(3) == 1)
            }
        }
        // Agenda principal do Google primeiro.
        return out.sortedByDescending { it.primary }
    }

    fun targetCalendar(context: Context): Cal? {
        val all = writableCalendars(context)
        val chosen = Settings(context).targetCalendarId
        return all.firstOrNull { it.id == chosen } ?: all.firstOrNull()
    }

    /** Retorna o id do evento, ou null sem permissão/agenda. */
    fun insert(context: Context, title: String, date: LocalDate, time: LocalTime?, durationMin: Int, description: String?): Long? {
        if (!canWrite(context)) return null
        val cal = targetCalendar(context) ?: return null
        val zone = ZoneId.systemDefault()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title)
            if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description.take(2000))
            if (time != null) {
                val start = date.atTime(time).atZone(zone)
                put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, start.plusMinutes(durationMin.toLong()).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            } else {
                // Dia inteiro: meia-noite UTC, como o Android exige.
                put(CalendarContract.Events.DTSTART, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(uri)
    }

    fun delete(context: Context, eventId: Long) {
        if (!canWrite(context)) return
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }
}
