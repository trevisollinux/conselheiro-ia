package com.trevisolli.agendawidget.smart

import com.trevisolli.agendawidget.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import android.util.Base64

enum class AiProvider(val label: String, val defaultModel: String, val keyHint: String) {
    CLAUDE("Claude", "claude-opus-5-5", "console.anthropic.com → API Keys"),
    OPENAI("ChatGPT", "gpt-5-mini", "platform.openai.com → API keys"),
    GEMINI("Gemini", "gemini-3.8-flash", "aistudio.google.com → Get API key"),
}

/** Chamada única de texto para a IA escolhida pelo usuário (chave dele, sem servidor no meio). */
object AiClient {
    /**
     * Modelo indicado pelo provedor quando o configurado saiu de linha
     * (ex.: "no longer available… use models/gemini-3.8-flash"). Vale até o app fechar.
     */
    val redirects = java.util.concurrent.ConcurrentHashMap<AiProvider, String>()

    /** Limite da cota atingido: a IA fica pausada até [until] e o app usa as regras. */
    class RateLimited(val until: Long) : IOException(
        "limite grátis atingido; IA pausada até " +
            java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
    )

    private var prefs: android.content.SharedPreferences? = null

    fun attach(prefs: android.content.SharedPreferences) {
        this.prefs = prefs
    }

    val pausedUntil: Long
        get() = prefs?.getLong("ai_paused_until", 0L)?.takeIf { it > System.currentTimeMillis() } ?: 0L

    fun clearPause() {
        prefs?.edit()?.remove("ai_paused_until")?.apply()
    }

    private val RETRY = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)")

    /** Por minuto: espera o que o provedor pediu (mín. 5 min). Por dia: 3 h. Sem detalhe: 30 min. */
    private fun pauseFor(message: String): Long {
        val retry = RETRY.find(message)?.groupValues?.get(1)?.toLongOrNull()
        val minutes = when {
            message.contains("PerDay", ignoreCase = true) || message.contains("per day", ignoreCase = true) -> 180L
            retry != null -> maxOf(5L, retry / 60 + 1)
            else -> 30L
        }
        val until = System.currentTimeMillis() + minutes * 60_000
        prefs?.edit()?.putLong("ai_paused_until", until)?.apply()
        return until
    }

    private val SUGGESTED = Regex("use (?:models/)?([A-Za-z0-9][A-Za-z0-9._-]+)")

    /** [jpeg]: imagem opcional (print), já reduzida. */
    /** [audio]: áudio opcional (bytes, mime), só no Gemini. */
    fun complete(
        provider: AiProvider, key: String, model: String, system: String, user: String,
        jpeg: ByteArray? = null, audio: Pair<ByteArray, String>? = null,
    ): String {
        pausedUntil.takeIf { it > 0 }?.let { throw RateLimited(it) }
        return try {
            completeOnce(provider, key, model, system, user, jpeg, audio)
        } catch (e: IOException) {
            if (e !is RateLimited && e.message.orEmpty().startsWith("HTTP 429")) throw RateLimited(pauseFor(e.message.orEmpty()))
            throw e
        }
    }

    private fun completeOnce(
        provider: AiProvider, key: String, model: String, system: String, user: String,
        jpeg: ByteArray?, audio: Pair<ByteArray, String>?,
    ): String {
        val image = jpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val sound = audio?.let { Base64.encodeToString(it.first, Base64.NO_WRAP) to it.second }
        val first = redirects[provider] ?: model
        return try {
            withRetryOnTimeout(provider) { call(provider, key, first, system, user, image, sound) }
        } catch (e: IOException) {
            // Modelo aposentado: tenta uma vez com o que o próprio provedor sugeriu.
            val msg = e.message.orEmpty()
            val suggested = SUGGESTED.find(msg)?.groupValues?.get(1)?.trimEnd('.')
            if (!msg.startsWith("HTTP 404") || suggested == null || suggested == first) throw e
            redirects[provider] = suggested
            withRetryOnTimeout(provider) { call(provider, key, suggested, system, user, image, sound) }
        }
    }

    /** Uma segunda tentativa se o provedor demorar demais; depois, mensagem clara. */
    private fun <T> withRetryOnTimeout(provider: AiProvider, block: () -> T): T {
        try {
            return block()
        } catch (e: java.net.SocketTimeoutException) {
            try {
                return block()
            } catch (e2: java.net.SocketTimeoutException) {
                throw IOException("a ${provider.label} demorou demais para responder. Tente de novo em instantes ou use um modelo mais leve (flash-lite).")
            }
        }
    }

    private fun call(provider: AiProvider, key: String, model: String, system: String, user: String, image: String?, sound: Pair<String, String>?): String =
        when (provider) {
            AiProvider.CLAUDE -> claude(key, model, system, user, image)
            AiProvider.OPENAI -> openAi(key, model, system, user, image)
            AiProvider.GEMINI -> gemini(key, model, system, user, image, sound)
        }.trim()

    /** Transcrição pela OpenAI (o Gemini ouve o áudio direto no pedido). */
    fun transcribeOpenAi(key: String, audio: ByteArray, fileName: String, mime: String): String {
        pausedUntil.takeIf { it > 0 }?.let { throw RateLimited(it) }
        val res = try {
            Http.postMultipart(
                "https://api.openai.com/v1/audio/transcriptions",
                mapOf("Authorization" to "Bearer $key"),
                mapOf("model" to "gpt-4o-mini-transcribe", "language" to "pt"),
                "file", fileName, mime, audio,
            )
        } catch (e: IOException) {
            if (e.message.orEmpty().startsWith("HTTP 429")) throw RateLimited(pauseFor(e.message.orEmpty()))
            throw e
        }
        return res.optString("text").trim()
    }

    private fun claude(key: String, model: String, system: String, user: String, image: String?): String {
        val content = JSONArray()
        if (image != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", image),
                ),
            )
        }
        content.put(JSONObject().put("type", "text").put("text", user))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4000)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val headers = mutableMapOf(
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01",
        )
        // Modelos 5.x: esforço baixo (tarefa curta) e fallback do servidor em caso de recusa.
        if (Regex("^claude-(opus|sonnet|haiku|fable)-5").containsMatchIn(model)) {
            body.put("output_config", JSONObject().put("effort", "low"))
        }
        if (Regex("^claude-(opus-5|sonnet-5-5|fable-5-1)").containsMatchIn(model)) {
            body.put("fallbacks", "default")
            headers["anthropic-beta"] = "server-side-fallback-2026-07-01"
        }
        val res = Http.postJson("https://api.anthropic.com/v1/messages", headers, body)
        if (res.optString("stop_reason") == "refusal") throw IOException("a IA recusou o pedido")
        val blocks = res.getJSONArray("content")
        return buildString {
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
    }

    private fun openAi(key: String, model: String, system: String, user: String, image: String?): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", user))
        if (image != null) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")),
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", content)),
            )
        val res = Http.postJson(
            "https://api.openai.com/v1/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body,
        )
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun gemini(key: String, model: String, system: String, user: String, image: String?, sound: Pair<String, String>? = null): String {
        val parts = JSONArray()
        if (image != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image)))
        }
        if (sound != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", sound.second).put("data", sound.first)))
        }
        parts.put(JSONObject().put("text", user))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val headers = mapOf("x-goog-api-key" to key)
        // Gemini 3+ pensa bastante por padrão; para tarefas curtas, pede raciocínio baixo (resposta bem mais rápida).
        val thinkLow = Regex("^gemini-([3-9]|\\d{2,})").containsMatchIn(model)
        val res = if (thinkLow) {
            try {
                Http.postJson(
                    url, headers,
                    JSONObject(body.toString()).put(
                        "generationConfig",
                        JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")),
                    ),
                )
            } catch (e: IOException) {
                // Modelo que não aceita o ajuste: manda de novo sem ele.
                if (e.message?.startsWith("HTTP 400") == true) Http.postJson(url, headers, body) else throw e
            }
        } else {
            Http.postJson(url, headers, body)
        }
        val out = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString {
            for (i in 0 until out.length()) {
                val part = out.getJSONObject(i)
                if (!part.optBoolean("thought")) append(part.optString("text"))
            }
        }
    }

    /** Extrai o primeiro objeto JSON da resposta (as IAs às vezes cercam com ```json). */
    fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }
}
