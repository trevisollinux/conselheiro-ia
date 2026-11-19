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

    private fun todayContext(today: LocalDate = LocalDate.now()): String {
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return "Hoje é $weekday, ${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}. Fuso: ${ZoneId.systemDefault().id}."
    }

    private const val EXTRACT_SYSTEM =
        "Você extrai compromissos e tarefas de mensagens em português. Responda APENAS com um objeto JSON, sem texto extra, " +
            "no formato {\"found\": bool, \"title\": string, \"date\": \"YYYY-MM-DD\" ou null, " +
            "\"time\": \"HH:MM\" ou null, \"duration_min\": número ou null, \"kind\": \"event\" ou \"task\"}. " +
            "title: curto (até 50 caracteres), no infinitivo ou substantivo, sem a data. " +
            "kind = event quando tem hora marcada ou é algo para comparecer; task quando é algo a fazer. " +
            "Seja MUITO seletivo: found = true só para compromisso do PRÓPRIO usuário (consulta, reunião ou encontro " +
            "marcado com ele, convite direto, prazo que ele precisa cumprir, algo que pediram para ele fazer). " +
            "found = false para: conversa de grupo sobre planos de outras pessoas, divulgação de eventos, avisos de " +
            "serviços e empresas de caráter geral, mudança de termos ou condições, newsletters, propaganda, recibos, códigos, login, " +
            "e para qualquer data ou hora que já passou. Na dúvida, found = false. " +
            "Inclua \"confidence\": número de 0 a 1 indicando a certeza de que é um compromisso real do usuário. " +
            "Se a mensagem pede confirmação ou resposta (\"confirma terça?\", \"pode ser às 10h?\"), inclua \"reply\": " +
            "uma resposta curta e educada em 1ª pessoa confirmando (ex.: \"Confirmado, terça às 10h estarei aí.\"); senão null."

    fun rules(text: String, origin: String, dateReference: LocalDate = LocalDate.now()): Suggestion {
        val r = RuleParser.parse(text, dateReference)
        return Suggestion(
            title = r.title,
            date = r.date ?: if (r.time != null) dateReference else null,
            time = r.time,
            kind = if (r.time != null) Kind.EVENT else Kind.TASK,
            origin = origin,
            text = text,
        )
    }

    /** Usa a IA; null se ela disser que não há compromisso. Lança exceção em erro de rede/chave. */
    /** [light]: usa o modelo leve (leitura automática de notificações e notas). */
    fun ai(context: Context, text: String, origin: String, light: Boolean = false, receivedOn: LocalDate = LocalDate.now()): Suggestion? {
        val s = Settings(context)
        val provider = s.aiProvider
        val model = if (light) s.effectiveLightModelFor(provider) else s.effectiveModelFor(provider)
        val raw = AiClient.complete(
            provider, s.keyFor(provider), model, EXTRACT_SYSTEM,
            todayContext(receivedOn) + "\n\nMensagem:\n" + text,
            thinking = if (light) AiThinking.LOW else s.thinkingFor(provider),
        )
        val json = AiClient.extractJson(raw) ?: throw IllegalStateException("resposta da IA sem JSON")
        // Leitura automática (light) só aceita o que a IA tem bastante certeza.
        return fromJson(json, text, origin, minConfidence = if (light) 0.75 else 0.0)
    }

    /** Print com IA: a imagem vai direto para o modelo (lê texto, conversas, cartazes, convites). */
    fun aiImage(context: Context, jpeg: ByteArray, ocrText: String, origin: String): Suggestion? {
        val s = Settings(context)
        val provider = s.aiProvider
        val raw = AiClient.complete(
            provider, s.keyFor(provider), s.effectiveModelFor(provider), EXTRACT_SYSTEM,
            todayContext() + "\n\nA imagem anexada é um print. Extraia o compromisso ou tarefa principal dela.",
            jpeg, thinking = s.thinkingFor(provider),
        )
        val json = AiClient.extractJson(raw) ?: throw IllegalStateException("resposta da IA sem JSON")
        return fromJson(json, ocrText.ifBlank { "Print compartilhado" }, origin)
    }

    private fun fromJson(json: org.json.JSONObject, text: String, origin: String, minConfidence: Double = 0.0): Suggestion? {
        if (!json.optBoolean("found", false)) return null
        if (json.optDouble("confidence", 1.0) < minConfidence) return null
        val date = json.optString("date").takeIf { !json.isNull("date") && it.length == 10 }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = json.optString("time").takeIf { !json.isNull("time") && it.length >= 4 }
            ?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
        // Nada no passado.
        val today = LocalDate.now()
        if (date != null && date.isBefore(today)) return null
        if ((date == null || date == today) && time != null && time.isBefore(LocalTime.now())) return null
        return Suggestion(
            title = json.optString("title").ifBlank { "Compromisso" }.take(60),
            date = date,
            time = time,
            durationMin = json.optInt("duration_min", 60).takeIf { it in 5..1440 } ?: 60,
            kind = if (json.optString("kind") == "event" || time != null) Kind.EVENT else Kind.TASK,
            origin = origin,
            text = text,
            reply = json.optString("reply").takeIf { !json.isNull("reply") && it.isNotBlank() }?.take(200),
        )
    }

    /**
     * Mensagem de notificação: as regras filtram primeiro (barato e local);
     * só o que passa no filtro vai para a IA, se ela estiver ligada.
     */
    /** [why] recebe o motivo quando não vira sugestão (para o diagnóstico em Configurações). */
    private val REQUEST = Regex(
        "\\b(?:consegue|poderia|pode|preciso que|por favor|favor|você precisa)\\s+(?:me\\s+)?" +
            "(?:buscar|levar|enviar|mandar|entregar|confirmar|ligar|responder|agendar|marcar|assinar)\\b",
        RegexOption.IGNORE_CASE,
    )

    internal fun hasDirectRequest(text: String) = REQUEST.containsMatchIn(text)

    private val IN_PERSON = Regex("\\b(?:buscar|levar|agendar|marcar)\\b", RegexOption.IGNORE_CASE)

    fun fromNotification(
        context: Context, text: String, origin: String, strict: Boolean = false,
        receivedOn: LocalDate = LocalDate.now(), why: (String) -> Unit = {},
    ): Suggestion? {
        val r = RuleParser.parse(text, receivedOn)
        val request = hasDirectRequest(text)
        val datedRequest = request && (r.date != null || r.time != null)
        if (request && !datedRequest && !strict && IN_PERSON.containsMatchIn(text)) {
            return null.also { why("pedido aguardando data ou horário") }
        }
        if (!RuleParser.isStrong(r) && !request) return null.also { why("sem data/hora ou pedido direto") }
        if (strict && !r.hasKeyword && !request && (r.date == null || r.time == null)) return null.also { why("e-mail sem compromisso ou pedido") }
        val today = LocalDate.now()
        val effectiveDate = r.date ?: receivedOn
        if ((r.date != null || r.time != null) && effectiveDate.isBefore(today)) {
            return null.also { why("data já passou") }
        }
        if (effectiveDate == today && r.time != null && r.time.isBefore(LocalTime.now())) {
            return null.also { why("horário já passou") }
        }
        val canUseRules = (r.hasKeyword && RuleParser.isStrong(r)) || datedRequest
        val s = Settings(context)
        if (!s.aiLightReady) {
            return if (canUseRules) rules(text, origin, receivedOn)
            else null.also { why("sem regra local conclusiva") }
        }
        return try {
            ai(context, text, origin, light = true, receivedOn = receivedOn)
                .also { if (it == null) why("IA: não parece compromisso seu") }
        } catch (e: Exception) {
            FailLog.add(context, "Leitura de mensagens (IA)", AiClient.friendly(e))
            if (canUseRules) rules(text, origin, receivedOn) else null.also { why("IA indisponível: ${AiClient.friendly(e)}") }
        }
    }
}
