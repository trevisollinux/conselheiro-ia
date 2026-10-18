package com.trevisolli.agendawidget.smart

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Modo automático: encontra datas e horas em português sem IA.
 * Ex.: "consulta amanhã às 9h", "reunião sexta 14:30", "prova dia 21/10", "exame quinta de manhã".
 */
object RuleParser {

    data class Result(val title: String, val date: LocalDate?, val time: LocalTime?, val hasKeyword: Boolean)

    private val WEEKDAYS = mapOf(
        "segunda" to DayOfWeek.MONDAY, "terca" to DayOfWeek.TUESDAY, "quarta" to DayOfWeek.WEDNESDAY,
        "quinta" to DayOfWeek.THURSDAY, "sexta" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY,
    )
    private val MONTHS = listOf(
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )
    private val KEYWORDS = Regex(
        "\\b(consulta|reuniao|exame|prova|entrega|vencimento|vence|pagar|pagamento|aniversario|encontro|" +
            "marcad[oa]|agendad[oa]|agendar|horario|compromisso|evento|aula|festa|almoco|jantar|viagem|voo|" +
            "dentista|medic[oa]|call|meet|entrevista|prazo|lembrar|lembrete|buscar|levar)\\b",
    )
    private val FILLER = setOf(
        "as", "a", "na", "no", "nesta", "neste", "nessa", "nesse", "de", "da", "do", "para", "pra", "em",
        "e", "dia", "que", "vem", "proxima", "proximo", "ate", "pelas", "por", "volta", "hoje", "amanha",
        "ok", "blz", "beleza", "ta", "viu", "ne", "certo", "combinado",
    )
    /** Palavras de ligação logo antes de uma data/hora ("para quinta às 9h") também saem do título. */
    private val CONNECTORS = setOf(
        "as", "a", "na", "no", "nesta", "neste", "nessa", "nesse", "para", "pra", "ate", "pelas", "em", "dia", "de", "do", "da",
    )

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").lowercase()

    fun parse(original: String, today: LocalDate = LocalDate.now()): Result {
        val text = Normalizer.normalize(original, Normalizer.Form.NFC)
        val n = normalize(text)
        val spans = mutableListOf<IntRange>()

        var date: LocalDate? = null
        fun dateMatch(regex: Regex, f: (MatchResult) -> LocalDate?) {
            if (date != null) return
            val m = regex.find(n) ?: return
            val d = f(m) ?: return
            date = d
            spans += m.range
        }

        dateMatch(Regex("\\bdepois de amanha\\b")) { today.plusDays(2) }
        dateMatch(Regex("\\bamanha\\b")) { today.plusDays(1) }
        dateMatch(Regex("\\b(hoje|hj)\\b")) { today }
        dateMatch(Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")) { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearRaw = m.groupValues[3]
            val year = when {
                yearRaw.isEmpty() -> today.year
                yearRaw.length == 2 -> 2000 + yearRaw.toInt()
                else -> yearRaw.toInt()
            }
            safeDate(year, month, day)?.let { d -> if (yearRaw.isEmpty() && d.isBefore(today)) d.plusYears(1) else d }
        }
        dateMatch(Regex("\\b(?:dia )?(\\d{1,2}) de (${MONTHS.joinToString("|")})\\b")) { m ->
            val d = safeDate(today.year, MONTHS.indexOf(m.groupValues[2]) + 1, m.groupValues[1].toInt())
            if (d != null && d.isBefore(today)) d.plusYears(1) else d
        }
        dateMatch(Regex("\\bdia (\\d{1,2})\\b")) { m ->
            val day = m.groupValues[1].toInt()
            val d = safeDate(today.year, today.monthValue, day)
            if (d != null && d.isBefore(today)) safeDate(today.plusMonths(1).year, today.plusMonths(1).monthValue, day) else d
        }
        dateMatch(
            Regex(
                "\\b(?:(proxim[ao]|nest[ae]|ness[ae]|na|no)\\s+)?(segunda|terca|quarta|quinta|sexta|sabado|domingo)" +
                    "(?:[- ]feira)?(\\s+que\\s+vem)?\\b",
            ),
        ) { m ->
            val dow = WEEKDAYS.getValue(m.groupValues[2])
            val next = m.groupValues[1].startsWith("proxim") || m.groupValues[3].isNotEmpty()
            var d = today.with(TemporalAdjusters.nextOrSame(dow))
            if (next && d == today) d = d.plusWeeks(1)
            d
        }

        var time: LocalTime? = null
        fun timeMatch(regex: Regex, f: (MatchResult) -> LocalTime?) {
            if (time != null) return
            val m = regex.findAll(n).firstNotNullOfOrNull { mr -> f(mr)?.let { mr to it } } ?: return
            time = m.second
            spans += m.first.range
        }
        timeMatch(Regex("\\bmeio[- ]dia\\b")) { LocalTime.NOON }
        timeMatch(Regex("\\b(\\d{1,2})(?::|h)(\\d{2})\\b")) { m -> safeTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        timeMatch(Regex("\\b(\\d{1,2})\\s?(?:h|hs|hrs|horas)\\b")) { m -> safeTime(m.groupValues[1].toInt(), 0) }
        timeMatch(Regex("\\b(?:as|a partir das|pelas)\\s+(\\d{1,2})\\b(?!/)")) { m -> safeTime(m.groupValues[1].toInt(), 0) }

        // "3 da tarde", "8 da noite"
        val t = time
        if (t != null && t.hour < 12) {
            val pm = Regex("\\b(da|de) (tarde|noite)\\b").find(n)
            if (pm != null) {
                time = t.plusHours(12)
                spans += pm.range
            }
        }
        Regex("\\b(da|de|pela) manha\\b").find(n)?.let { spans += it.range }

        return Result(
            title = buildTitle(text, spans),
            date = date,
            time = time,
            hasKeyword = KEYWORDS.containsMatchIn(n),
        )
    }

    /** Para notificações: só aceita quando há sinal forte, para não encher de falsos positivos. */
    fun isStrong(r: Result): Boolean =
        (r.time != null && (r.date != null || r.hasKeyword)) || (r.date != null && r.hasKeyword)

    private fun buildTitle(text: String, spans: List<IntRange>): String {
        // Usa a frase que contém a primeira data/hora encontrada.
        val anchor = spans.minOfOrNull { it.first } ?: 0
        val sentenceBreaks = Regex("[.!?\\n]")
        val start = sentenceBreaks.findAll(text).map { it.range.last + 1 }.filter { it <= anchor }.maxOrNull() ?: 0
        val end = sentenceBreaks.findAll(text).map { it.range.first }.firstOrNull { it >= anchor } ?: text.length

        val chars = text.toCharArray()
        val n = normalize(text)
        spans.forEach { r ->
            for (i in r) if (i in chars.indices) chars[i] = ' '
            // Volta engolindo conectores ("para", "às", "dia"…) imediatamente antes do trecho removido.
            var i = r.first - 1
            while (n.length == chars.size) {
                while (i >= 0 && chars[i] == ' ') i--
                if (i < 0 || !chars[i].isLetter()) break
                var j = i
                while (j > 0 && chars[j - 1].isLetter()) j--
                if (n.substring(j, i + 1) !in CONNECTORS) break
                for (k in j..i) chars[k] = ' '
                i = j - 1
            }
        }
        val sentence = String(chars).substring(start, end)

        val words = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        fun isFiller(w: String) = normalize(w.trim(',', ';', ':', '-', '(', ')')) in FILLER || w.all { !it.isLetterOrDigit() }
        while (words.isNotEmpty() && isFiller(words.first())) words.removeAt(0)
        while (words.isNotEmpty() && isFiller(words.last())) words.removeAt(words.lastIndex)

        val title = words.joinToString(" ").trim(',', ';', ':', '-', ' ')
        if (title.isBlank()) return "Compromisso"
        val capped = if (title.length > 60) title.take(57).trimEnd() + "…" else title
        return capped.replaceFirstChar { it.uppercase() }
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (e: Exception) {
        null
    }

    private fun safeTime(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
}
