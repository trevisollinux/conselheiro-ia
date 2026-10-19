package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.Source
import com.trevisolli.agendawidget.data.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A linha de resumo no topo do widget. */
object Summary {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    private fun dateOf(item: AgendaItem, zone: ZoneId) = Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalDate()

    fun rules(items: List<AgendaItem>): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val todays = items.filter { !it.overdue && dateOf(it, zone) == today }
        val events = todays.count { it.source == Source.CALENDAR }
        val tasks = todays.count { it.source != Source.CALENDAR }
        val overdue = items.count { it.overdue }

        val parts = mutableListOf<String>()
        if (events > 0) parts += plural(events, "compromisso", "compromissos")
        if (tasks > 0) parts += plural(tasks, "tarefa", "tarefas")
        if (overdue > 0) parts += plural(overdue, "atrasada", "atrasadas")

        val next = items.firstOrNull { !it.dateOnly && it.source == Source.CALENDAR && (it.endMillis ?: it.startMillis) > now }
        val nextText = next?.let {
            val start = Instant.ofEpochMilli(it.startMillis).atZone(zone)
            val whenText = if (start.toLocalDate() == today) start.format(hhmm) else Format.subtitle(it)
            "Próximo: ${it.title} · $whenText"
        }

        return when {
            parts.isEmpty() && nextText == null -> "Dia livre hoje ✨"
            parts.isEmpty() -> "Nada hoje. $nextText"
            nextText == null -> "Hoje: ${parts.joinToString(" · ")}"
            else -> "Hoje: ${parts.joinToString(" · ")}. $nextText"
        }
    }

    private const val SUMMARY_SYSTEM =
        "Você é o ConsigliereIA, um conselheiro pessoal direto. Recebe a agenda e as tarefas do usuário e escreve UMA frase " +
            "em português do Brasil, com no máximo 150 caracteres, dizendo como está o dia e o que priorizar. " +
            "Se o tempo pedir (chuva no horário de um compromisso, calor forte), inclua o conselho prático, ex.: leve guarda-chuva. " +
            "Sem saudação, sem emojis em excesso (no máximo 1), sem markdown."

    /** Resumo por IA, com cache: só chama de novo quando a lista ou o dia muda. Cai para as regras em caso de erro. */
    fun build(context: Context, items: List<AgendaItem>, errors: MutableList<String>): String {
        val settings = Settings(context)
        val weather = WeatherSource.load(context)
        val advice = weather?.let { WeatherSource.advice(it, items) }
        val ruleText = rules(items) + (advice?.let { " $it" } ?: "")
        LightModel.refresh(settings)
        if (!settings.aiLightReady) return ruleText

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val relevant = items.filter { it.overdue || !dateOf(it, zone).isAfter(today.plusDays(1)) }.take(25)
        // A IA só refaz o resumo quando a lista muda; tempo/conselho novo, no máximo 1x por hora.
        val itemsKey = settings.aiProvider.name + today + relevant.joinToString("|") { it.title + it.startMillis + it.overdue }.hashCode()
        val key = itemsKey + "#" + (advice ?: "") + weather?.mood
        val cached = settings.summaryCacheText.isNotBlank()
        val sameItems = settings.summaryCacheKey.substringBefore("#") == itemsKey
        val fresh = System.currentTimeMillis() - settings.summaryCacheAt < 60 * 60_000L
        if (cached && (key == settings.summaryCacheKey || sameItems && fresh)) return settings.summaryCacheText

        val list = if (relevant.isEmpty()) "(nada agendado para hoje e amanhã)" else relevant.joinToString("\n") {
            "- ${it.title} (${Format.subtitle(it)})"
        }
        val prompt = "Agora: ${java.time.LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("EEEE dd/MM HH:mm", java.util.Locale("pt", "BR")))}\n" +
            "Itens (atrasadas, hoje e amanhã):\n$list" +
            (weather?.let { "\n" + WeatherSource.forecastLine(it) } ?: "") +
            (advice?.let { "\nConselho de tempo já calculado: $it" } ?: "")
        return try {
            val text = AiClient.complete(settings.aiProvider, settings.aiKey, settings.lightModel, SUMMARY_SYSTEM, prompt)
                .lines().firstOrNull { it.isNotBlank() }?.trim()?.take(200)
            if (text.isNullOrBlank()) return ruleText
            settings.summaryCacheKey = key
            settings.summaryCacheText = text
            settings.summaryCacheAt = System.currentTimeMillis()
            text
        } catch (e: AiClient.RateLimited) {
            // Pausa avisada só em Configurações → Inteligência.
            ruleText
        } catch (e: Exception) {
            errors += "IA: ${e.message?.take(120)}"
            ruleText
        }
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
