package com.trevisolli.agendawidget.smart

import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.Source
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Horários livres na agenda (8h–20h), para "quando tenho uma tarde livre?" e "marca no primeiro horário livre". */
object Slots {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun freeBlock(items: List<AgendaItem>, days: Int = 7): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowTime = LocalTime.now(zone)
        val lines = (0 until days).map { offset ->
            val day = today.plusDays(offset.toLong())
            val busy = items.filter { it.source == Source.CALENDAR && !it.dateOnly }
                .mapNotNull { e ->
                    val s = Instant.ofEpochMilli(e.startMillis).atZone(zone)
                    if (s.toLocalDate() != day) return@mapNotNull null
                    val end = Instant.ofEpochMilli(e.endMillis ?: (e.startMillis + 3600_000)).atZone(zone).toLocalTime()
                    s.toLocalTime() to end
                }.sortedBy { it.first }
            var cursor = if (offset == 0) maxOf(LocalTime.of(8, 0), nowTime.withSecond(0).withNano(0)) else LocalTime.of(8, 0)
            val end = LocalTime.of(20, 0)
            val free = mutableListOf<String>()
            for ((s, e) in busy) {
                if (s.isAfter(cursor) && java.time.Duration.between(cursor, s).toMinutes() >= 30) free += "${cursor.format(hhmm)}-${s.format(hhmm)}"
                if (e.isAfter(cursor)) cursor = e
            }
            if (end.isAfter(cursor) && java.time.Duration.between(cursor, end).toMinutes() >= 30) free += "${cursor.format(hhmm)}-${end.format(hhmm)}"
            val name = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("pt", "BR")).removeSuffix(".") + " " + day.format(DateTimeFormatter.ofPattern("dd/MM"))
            "- $name: " + if (free.isEmpty()) "sem horário livre" else free.joinToString(", ")
        }
        return "Horários livres na agenda (8h–20h):\n" + lines.joinToString("\n") + "\n"
    }
}
