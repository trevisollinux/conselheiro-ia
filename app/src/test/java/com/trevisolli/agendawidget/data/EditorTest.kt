package com.trevisolli.agendawidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class EditorTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d, LocalTime.of(h, m), zone).toInstant().toEpochMilli()
    private val day = LocalDate.of(2026, 11, 12)

    @Test fun mudaSoAHoraMantemDiaEDuracao() {
        val t = Editor.newTimes(at(day, 14), at(day, 15, 30), false, Editor.Change(time = LocalTime.of(16, 0)), zone)
        assertEquals(at(day, 16), t.start)
        assertEquals(at(day, 17, 30), t.end)
        assertFalse(t.allDay)
    }

    @Test fun mudaSoODiaMantemHora() {
        val t = Editor.newTimes(at(day, 9), at(day, 10), false, Editor.Change(date = day.plusDays(2)), zone)
        assertEquals(at(day.plusDays(2), 9), t.start)
        assertEquals(at(day.plusDays(2), 10), t.end)
    }

    @Test fun diaInteiroGanhaHora() {
        val s = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val t = Editor.newTimes(s, e, true, Editor.Change(time = LocalTime.of(10, 0)), zone)
        assertFalse(t.allDay)
        assertEquals(at(day, 10), t.start)
        assertEquals(at(day, 11), t.end)
    }

    @Test fun diaInteiroMudaDeDia() {
        val s = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val t = Editor.newTimes(s, e, true, Editor.Change(date = day.plusDays(1)), zone)
        assertTrue(t.allDay)
        assertEquals(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), t.start)
    }

    @Test fun novaDuracao() {
        val t = Editor.newTimes(at(day, 9), at(day, 10), false, Editor.Change(minutes = 30), zone)
        assertEquals(at(day, 9, 30), t.end)
    }
}
