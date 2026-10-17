package com.trevisolli.agendawidget.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Lê os eventos sincronizados no aparelho (Google, vivo etc.). Não precisa de login. */
object CalendarSource {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun fetch(context: Context, days: Int): List<AgendaItem> {
        if (!hasPermission(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        // Começa ontem para pegar eventos de dia inteiro (gravados em UTC) e eventos em andamento.
        val begin = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(days.toLong() + 1).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        val items = mutableListOf<AgendaItem>()
        context.contentResolver.query(
            uri,
            projection,
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val eventId = c.getLong(0)
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(sem título)"
                val evBegin = c.getLong(2)
                val evEnd = c.getLong(3)
                val allDay = c.getInt(4) == 1
                val color = c.getInt(5)
                val openUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId).toString()

                if (allDay) {
                    // Eventos de dia inteiro vêm em meia-noite UTC; o fim é exclusivo.
                    val startDate = Instant.ofEpochMilli(evBegin).atZone(ZoneOffset.UTC).toLocalDate()
                    val endDate = Instant.ofEpochMilli(evEnd).atZone(ZoneOffset.UTC).toLocalDate()
                    if (!endDate.isAfter(today) || startDate.isAfter(today.plusDays(days.toLong()))) continue
                    items += AgendaItem(
                        title = title,
                        source = Source.CALENDAR,
                        startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        endMillis = null,
                        dateOnly = true,
                        overdue = false,
                        color = color,
                        openUri = openUri,
                    )
                } else {
                    if (evEnd <= now) continue
                    items += AgendaItem(
                        title = title,
                        source = Source.CALENDAR,
                        startMillis = evBegin,
                        endMillis = evEnd,
                        dateOnly = false,
                        overdue = false,
                        color = color,
                        openUri = openUri,
                    )
                }
            }
        }
        return items
    }
}
