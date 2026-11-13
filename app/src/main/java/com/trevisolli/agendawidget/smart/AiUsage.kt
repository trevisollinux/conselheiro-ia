package com.trevisolli.agendawidget.smart

import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate

/**
 * Quanto a IA foi usada: chamadas e tokens por dia (últimos 31 dias), com custo ESTIMADO.
 * Os preços são aproximados por família de modelo; a conta real é a do painel do provedor.
 */
object AiUsage {
    data class Day(val calls: Int, val input: Long, val output: Long, val usd: Double)

    private const val KEY = "ai_usage"
    @Volatile internal var prefs: SharedPreferences? = null

    /** US$ por 1 milhão de tokens (entrada, saída). Aproximado; atualize se o provedor mudar a tabela. */
    internal fun price(model: String): Pair<Double, Double> {
        val m = model.lowercase()
        return when {
            "flash-lite" in m || "flash-8b" in m -> 0.10 to 0.40
            "flash" in m -> 0.30 to 2.50
            "gemini" in m && "pro" in m -> 1.25 to 10.0
            "haiku" in m -> 1.0 to 5.0
            "sonnet" in m -> 3.0 to 15.0
            "opus" in m -> 5.0 to 25.0
            "mini" in m || "nano" in m -> 0.25 to 2.0
            else -> 1.25 to 10.0
        }
    }

    @Synchronized
    fun record(model: String, input: Long, output: Long) {
        val p = prefs ?: return
        val all = runCatching { JSONObject(p.getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
        val today = LocalDate.now().toString()
        val d = all.optJSONObject(today) ?: JSONObject()
        val (pin, pout) = price(model)
        d.put("c", d.optInt("c") + 1)
            .put("i", d.optLong("i") + input)
            .put("o", d.optLong("o") + output)
            .put("u", d.optDouble("u", 0.0) + (input * pin + output * pout) / 1_000_000.0)
        all.put(today, d)
        // Guarda só os últimos 31 dias.
        val cutoff = LocalDate.now().minusDays(31).toString()
        all.keys().asSequence().toList().filter { it < cutoff }.forEach { all.remove(it) }
        p.edit().putString(KEY, all.toString()).apply()
    }

    private fun days(): Map<String, Day> {
        val all = runCatching { JSONObject(prefs?.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        return all.keys().asSequence().associateWith { k ->
            val d = all.getJSONObject(k)
            Day(d.optInt("c"), d.optLong("i"), d.optLong("o"), d.optDouble("u", 0.0))
        }
    }

    fun today(): Day = days()[LocalDate.now().toString()] ?: Day(0, 0, 0, 0.0)

    fun last30(): Day {
        val since = LocalDate.now().minusDays(29).toString()
        return days().filterKeys { it >= since }.values.fold(Day(0, 0, 0, 0.0)) { a, b ->
            Day(a.calls + b.calls, a.input + b.input, a.output + b.output, a.usd + b.usd)
        }
    }

    /** "12 chamadas · 48 mil tokens · ~US$ 0,02" */
    fun line(d: Day): String {
        val tokens = d.input + d.output
        val tk = if (tokens >= 1_000_000) String.format(java.util.Locale("pt", "BR"), "%.1f mi", tokens / 1_000_000.0)
        else "${tokens / 1000} mil"
        return "${d.calls} chamada${if (d.calls == 1) "" else "s"} · $tk tokens · ~US$ " +
            String.format(java.util.Locale("pt", "BR"), "%.2f", d.usd)
    }
}
