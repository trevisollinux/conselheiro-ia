package com.trevisolli.agendawidget.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Format {
    private val locale = Locale("pt", "BR")
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("dd/MM", locale)

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Hoje"
        today.plusDays(1) -> "Amanhã"
        today.minusDays(1) -> "Ontem"
        else -> {
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).removeSuffix("-feira")
            "${weekday.replaceFirstChar { it.uppercase(locale) }}, ${date.format(dayMonth)}"
        }
    }

    /** Ex.: "Hoje · 09:00-10:00", "Todoist · Quarta, 21/10", "Atrasada · Google Tasks · Ontem". */
    fun subtitle(item: AgendaItem): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = Instant.ofEpochMilli(item.startMillis).atZone(zone)
        val parts = mutableListOf<String>()
        if (item.overdue) parts += "Atrasada"
        if (item.source != Source.CALENDAR) parts += item.source.label
        parts += dayLabel(start.toLocalDate(), today)
        if (!item.dateOnly) {
            val end = item.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
            parts += if (end != null && end.toLocalDate() == start.toLocalDate()) {
                "${start.format(time)}-${end.format(time)}"
            } else {
                start.format(time)
            }
        } else if (item.source == Source.CALENDAR) {
            parts += "dia inteiro"
        }
        return parts.joinToString(" · ")
    }

    fun updatedAt(millis: Long): String {
        if (millis == 0L) return "nunca"
        val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return "${dt.format(dayMonth)} ${dt.format(time)}"
    }
}
