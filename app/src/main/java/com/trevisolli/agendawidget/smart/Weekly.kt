package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.Source
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Domingo à noite: a semana que vem num resumo (compromissos, tarefas, aniversários e contas). */
object Weekly {
    fun build(context: Context): String {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).plusDays(1)
        val endDay = start.plusDays(6)
        val items = AgendaRepository.load(context).items.filter {
            val d = Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate()
            it.overdue || (!d.isBefore(start) && !d.isAfter(endDay))
        }
        val events = items.filter { it.source == Source.CALENDAR }
        val tasks = items.filter { it.source != Source.CALENDAR }
        val birthdays = (0L..6L).flatMap { o -> Birthdays.on(context, start.plusDays(o)).map { it.name to start.plusDays(o) } }
        val bills = Bills.all(context).filter { b -> b.due != null && !b.due.isAfter(endDay) }
        val fmt = DateTimeFormatter.ofPattern("EEE dd/MM", java.util.Locale("pt", "BR"))
        val rules = buildString {
            append("📅 ${events.size} compromissos · ✅ ${tasks.size} tarefas")
            if (tasks.any { it.overdue }) append(" (${tasks.count { it.overdue }} atrasadas)")
            append("\n")
            events.take(8).forEach { append("• ${it.title} · ${Format.subtitle(it)}\n") }
            birthdays.forEach { (n, d) -> append("🎂 $n · ${d.format(fmt)}\n") }
            bills.forEach { append("💸 ${Bills.label(it)}\n") }
        }.trim()
        val s = Settings(context)
        if (!s.aiLightReady) return rules
        return runCatching {
            AiClient.complete(
                s.aiProvider, s.aiKey, s.lightModel,
                "Você é o Consigliere, conselheiro pessoal. Escreva em português do Brasil uma revisão da semana que vem: " +
                    "até 6 linhas curtas, começando pelo que pede preparo (prazos, contas, aniversários). Sem markdown.",
                rules,
            ).trim().ifBlank { rules }
        }.onFailure { FailLog.add(context, "Revisão da semana (IA)", it) }.getOrDefault(rules)
    }

    /** Uma vez por semana, domingo a partir das 19h. */
    fun maybeNotify(context: Context) {
        val now = LocalDate.now()
        if (now.dayOfWeek != DayOfWeek.SUNDAY || LocalTime.now().hour < 19) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("weekly_on", true) || prefs.getString("weekly_done", null) == now.toString()) return
        prefs.edit().putString("weekly_done", now.toString()).apply()
        val text = build(context)
        ChatStore.add(context, ChatMessage(fromUser = false, text = "🗓️ Sua semana:\n$text"))
        Notify.post(context, "weekly", "Revisão da semana", 4242, "🗓️ Sua semana", text)
    }
}
