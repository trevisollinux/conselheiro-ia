package com.trevisolli.agendawidget.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Tempo agora e nas próximas horas (Open-Meteo: grátis, sem chave). */
object WeatherSource {

    enum class Mood { CLEAR, HOT, CLOUDY, FOG, RAIN, STORM }

    data class Hour(val time: LocalDateTime, val rainProb: Int, val code: Int, val temp: Double)

    data class Weather(val code: Int, val temp: Double, val isDay: Boolean, val hours: List<Hour>, val fetchedAt: Long) {
        val mood: Mood
            get() = when (code) {
                in 95..99 -> Mood.STORM
                in 51..67, in 80..82 -> Mood.RAIN
                45, 48 -> Mood.FOG
                in 2..3 -> Mood.CLOUDY
                else -> if (temp >= 30) Mood.HOT else Mood.CLEAR
            }

        val label: String
            get() = when (mood) {
                Mood.STORM -> "Tempestade"
                Mood.RAIN -> "Chuva"
                Mood.FOG -> "Neblina"
                Mood.CLOUDY -> "Nublado"
                Mood.HOT -> "Calor"
                Mood.CLEAR -> "Céu limpo"
            } + " · ${temp.toInt()}°C"
    }

    private fun cache(context: Context) = File(context.filesDir, "weather.json")

    /** Procura a cidade e guarda a coordenada. Retorna o nome encontrado, ou null. */
    fun setCity(context: Context, name: String): String? {
        val s = Settings(context)
        if (name.isBlank()) {
            s.city = ""
            cache(context).delete()
            return null
        }
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&name=" + URLEncoder.encode(name, "UTF-8")
        val r = Http.getPublic(url).optJSONArray("results")?.optJSONObject(0) ?: return null
        val found = listOf(r.optString("name"), r.optString("admin1")).filter { it.isNotBlank() }.joinToString(", ")
        s.city = found
        s.cityLat = r.getDouble("latitude")
        s.cityLon = r.getDouble("longitude")
        cache(context).delete()
        return found
    }

    /** Atualiza no máximo a cada 30 min. */
    fun refresh(context: Context): Weather? {
        val s = Settings(context)
        if (s.city.isBlank()) return null
        load(context)?.let { if (System.currentTimeMillis() - it.fetchedAt < 30 * 60_000L) return it }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${s.cityLat}&longitude=${s.cityLon}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=precipitation_probability,weather_code,temperature_2m&forecast_days=2&timezone=auto"
        val json = Http.getPublic(url)
        json.put("fetchedAt", System.currentTimeMillis())
        cache(context).writeText(json.toString())
        return parse(json)
    }

    fun load(context: Context): Weather? {
        val f = cache(context)
        if (!f.exists() || Settings(context).city.isBlank()) return null
        return runCatching { parse(JSONObject(f.readText())) }.getOrNull()
    }

    private fun parse(json: JSONObject): Weather {
        val cur = json.getJSONObject("current")
        val h = json.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val probs = h.optJSONArray("precipitation_probability") ?: JSONArray()
        val codes = h.optJSONArray("weather_code") ?: JSONArray()
        val temps = h.optJSONArray("temperature_2m") ?: JSONArray()
        val hours = List(times.length()) { i ->
            Hour(LocalDateTime.parse(times.getString(i)), probs.optInt(i), codes.optInt(i), temps.optDouble(i))
        }
        return Weather(
            code = cur.optInt("weather_code"),
            temp = cur.optDouble("temperature_2m"),
            isDay = cur.optInt("is_day", 1) == 1,
            hours = hours,
            fetchedAt = json.optLong("fetchedAt"),
        )
    }

    /** Uma linha com o tempo das próximas horas, para a IA. */
    fun forecastLine(w: Weather): String {
        val now = LocalDateTime.now()
        val next = w.hours.filter { it.time.isAfter(now.minusHours(1)) && it.time.isBefore(now.plusHours(14)) }
        val rainy = next.filter { it.rainProb >= 50 }.joinToString(", ") { "${it.time.hour}h ${it.rainProb}%" }
        return "Tempo agora: ${w.label}. " +
            (if (rainy.isNotEmpty()) "Chance de chuva: $rainy." else "Sem chuva prevista nas próximas horas.")
    }

    /** Conselho prático: guarda-chuva para um compromisso, calor, etc. Null se nada a dizer. */
    fun advice(w: Weather, items: List<AgendaItem>): String? {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        fun probAt(t: LocalDateTime) = w.hours.firstOrNull { it.time.toLocalDate() == t.toLocalDate() && it.time.hour == t.hour }?.rainProb ?: 0

        val wetEvent = items.filter { !it.dateOnly && it.source == Source.CALENDAR }
            .map { it to LocalDateTime.ofInstant(Instant.ofEpochMilli(it.startMillis), zone) }
            .firstOrNull { (_, t) -> t.isAfter(now) && t.toLocalDate() == today && probAt(t) >= 60 }
        if (wetEvent != null) {
            val (item, t) = wetEvent
            return "☔ Chuva prevista às ${t.hour}h (${item.title}): leve guarda-chuva."
        }
        val rainSoon = w.hours.firstOrNull { it.time.isAfter(now) && it.time.isBefore(now.plusHours(10)) && it.rainProb >= 70 }
        if (rainSoon != null) return "☔ Chance de chuva às ${rainSoon.time.hour}h: leve guarda-chuva."
        val maxTemp = w.hours.filter { it.time.toLocalDate() == today }.maxOfOrNull { it.temp } ?: w.temp
        if (maxTemp >= 32) return "🥵 Dia quente (${maxTemp.toInt()}°C): beba água."
        return null
    }
}
