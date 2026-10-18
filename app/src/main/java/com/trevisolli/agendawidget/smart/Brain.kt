package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.Settings
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Decide entre o modo automático (regras) e a IA do usuário. */
object Brain {
    private val locale = Locale("pt", "BR")

    private fun todayContext(): String {
        val today = LocalDate.now()
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return "Hoje é $weekday, ${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}. Fuso: ${ZoneId.systemDefault().id}."
    }

    private const val EXTRACT_SYSTEM =
        "Você extrai compromissos e tarefas de mensagens em português. Responda APENAS com um objeto JSON, sem texto extra, " +
            "no formato {\"found\": bool, \"title\": string, \"date\": \"YYYY-MM-DD\" ou null, " +
            "\"time\": \"HH:MM\" ou null, \"duration_min\": número ou null, \"kind\": \"event\" ou \"task\"}. " +
            "title: curto (até 50 caracteres), no infinitivo ou substantivo, sem a data. " +
            "kind = event quando tem hora marcada ou é algo para comparecer; task quando é algo a fazer. " +
            "found = false se a mensagem não contém nenhum compromisso, prazo ou tarefa concreta, " +
            "se for aviso automático (login, segurança, propaganda, recibo, código) ou se a data/hora já passou."

    fun rules(text: String, origin: String): Suggestion {
        val r = RuleParser.parse(text)
        return Suggestion(
            title = r.title,
            date = r.date ?: if (r.time != null) LocalDate.now() else null,
            time = r.time,
            kind = if (r.time != null) Kind.EVENT else Kind.TASK,
            origin = origin,
            text = text,
        )
    }

    /** Usa a IA; null se ela disser que não há compromisso. Lança exceção em erro de rede/chave. */
    fun ai(context: Context, text: String, origin: String): Suggestion? {
        val s = Settings(context)
        val raw = AiClient.complete(s.aiProvider, s.aiKey, s.effectiveModel, EXTRACT_SYSTEM, todayContext() + "\n\nMensagem:\n" + text)
        val json = AiClient.extractJson(raw) ?: throw IllegalStateException("resposta da IA sem JSON")
        return fromJson(json, text, origin)
    }

    /** Print com IA: a imagem vai direto para o modelo (lê texto, conversas, cartazes, convites). */
    fun aiImage(context: Context, jpeg: ByteArray, ocrText: String, origin: String): Suggestion? {
        val s = Settings(context)
        val raw = AiClient.complete(
            s.aiProvider, s.aiKey, s.effectiveModel, EXTRACT_SYSTEM,
            todayContext() + "\n\nA imagem anexada é um print. Extraia o compromisso ou tarefa principal dela.",
            jpeg,
        )
        val json = AiClient.extractJson(raw) ?: throw IllegalStateException("resposta da IA sem JSON")
        return fromJson(json, ocrText.ifBlank { "Print compartilhado" }, origin)
    }

    private fun fromJson(json: org.json.JSONObject, text: String, origin: String): Suggestion? {
        if (!json.optBoolean("found", false)) return null
        val date = json.optString("date").takeIf { !json.isNull("date") && it.length == 10 }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = json.optString("time").takeIf { !json.isNull("time") && it.length >= 4 }
            ?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
        return Suggestion(
            title = json.optString("title").ifBlank { "Compromisso" }.take(60),
            date = date,
            time = time,
            durationMin = json.optInt("duration_min", 60).takeIf { it in 5..1440 } ?: 60,
            kind = if (json.optString("kind") == "event" || time != null) Kind.EVENT else Kind.TASK,
            origin = origin,
            text = text,
        )
    }

    /**
     * Mensagem de notificação: as regras filtram primeiro (barato e local);
     * só o que passa no filtro vai para a IA, se ela estiver ligada.
     */
    fun fromNotification(context: Context, text: String, origin: String, strict: Boolean = false): Suggestion? {
        val r = RuleParser.parse(text)
        if (!RuleParser.isStrong(r)) return null
        // E-mail: exige palavra de compromisso ("consulta", "reunião"…), não só data e hora.
        if (strict && !r.hasKeyword) return null
        // Data/hora que já passou não é compromisso (ex.: "você entrou às 12:36").
        val today = LocalDate.now()
        if (r.date != null && r.date.isBefore(today)) return null
        if ((r.date == null || r.date == today) && r.time != null && r.time.isBefore(LocalTime.now())) return null
        if (!Settings(context).aiReady) return rules(text, origin)
        return try {
            ai(context, text, origin)
        } catch (e: Exception) {
            rules(text, origin)
        }
    }
}
