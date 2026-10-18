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

    /** Linha curta para listas já agrupadas por dia: "Todoist · 20:00", "09:00-10:00", "desde qua 14/10". */
    fun shortSubtitle(item: AgendaItem): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(item.startMillis).atZone(zone)
        val parts = mutableListOf<String>()
        if (item.source != Source.CALENDAR) parts += item.source.label
        if (item.overdue) {
            parts += "desde " + start.toLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".") +
                " " + start.format(dayMonth)
        }
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

    /** Cabeçalho de grupo no widget: "HOJE · DOM 18/10", "AMANHÃ", "QUARTA 21/10". */
    fun dayHeader(date: LocalDate): String {
        val today = LocalDate.now(ZoneId.systemDefault())
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).removeSuffix("-feira")
        return when (date) {
            today -> "HOJE · " + date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".") + " " + date.format(dayMonth)
            today.plusDays(1) -> "AMANHÃ"
            else -> "$weekday ${date.format(dayMonth)}"
        }.uppercase(locale)
    }

    /** Ex.: "Amanhã 09:00", "Quarta, 21/10". */
    fun suggestionWhen(date: LocalDate?, time: java.time.LocalTime?): String? {
        if (date == null && time == null) return null
        val today = LocalDate.now(ZoneId.systemDefault())
        return listOfNotNull(date?.let { dayLabel(it, today) }, time?.format(this.time)).joinToString(" ")
    }

    fun updatedAt(millis: Long): String {
        if (millis == 0L) return "nunca"
        val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return "${dt.format(dayMonth)} ${dt.format(time)}"
    }
}
