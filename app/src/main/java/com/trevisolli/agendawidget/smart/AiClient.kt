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

enum class AiThinking(val label: String, val apiValue: String?) {
    AUTO("Automático", null), LOW("Baixo", "low"), MEDIUM("Médio", "medium"), HIGH("Alto", "high");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Chamada única de texto para a IA escolhida pelo usuário (chave dele, sem servidor no meio). */
object AiClient {
    /**
     * Modelo indicado pelo provedor quando o configurado saiu de linha
     * (ex.: "no longer available… use models/gemini-3.8-flash"). Vale até o app fechar.
     */
    val redirects = java.util.concurrent.ConcurrentHashMap<Pair<AiProvider, String>, String>()

    /** Limite da cota atingido: a IA fica pausada até [until] e o app usa as regras. */
    class RateLimited(val until: Long) : IOException(
        "limite grátis atingido; IA pausada até " +
            java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
    )

    private var prefs: android.content.SharedPreferences? = null

    fun attach(prefs: android.content.SharedPreferences) {
        this.prefs = prefs
        AiUsage.prefs = prefs
    }

    /** A cota é por modelo: o leve pode estar pausado e o principal não (e vice-versa). */
    fun pausedUntil(model: String): Long =
        prefs?.getLong("ai_paused_until:$model", 0L)?.takeIf { it > System.currentTimeMillis() } ?: 0L

    fun clearPause() {
        val p = prefs ?: return
        p.edit().apply { p.all.keys.filter { it.startsWith("ai_paused_until") }.forEach { remove(it) } }.apply()
    }

    /** A lista vem da API com a chave escolhida pelo usuário. */
    fun geminiModels(key: String): List<String> {
        val response = Http.get(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
            mapOf("x-goog-api-key" to key),
        )
        val models = response.optJSONArray("models") ?: return emptyList()
        val excluded = Regex("tts|image|live|audio|thinking|transcribe|deep-research|embedding|computer-use")
        return List(models.length()) { models.getJSONObject(it) }
            .filter { entry ->
                val methods = entry.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            }
            .map { it.optString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini-") && !excluded.containsMatchIn(it) }
            .distinct()
            .sortedDescending()
    }

    fun availableModels(provider: AiProvider, key: String): List<String> {
        if (provider == AiProvider.GEMINI) return geminiModels(key)
        val response = when (provider) {
            AiProvider.CLAUDE -> Http.get("https://api.anthropic.com/v1/models?limit=100", mapOf(
                "x-api-key" to key, "anthropic-version" to "2023-06-01",
            ))
            else -> Http.getJson("https://api.openai.com/v1/models", key)
        }
        val data = response.optJSONArray("data") ?: return emptyList()
        val excluded = Regex("audio|realtime|tts|transcribe|image|search|instruct|codex|deep-research|pro")
        return List(data.length()) { data.getJSONObject(it).optString("id") }
            .filter { model ->
                when (provider) {
                    AiProvider.CLAUDE -> model.startsWith("claude-")
                    else -> (model.startsWith("gpt-") || Regex("^o[0-9]").containsMatchIn(model)) && !excluded.containsMatchIn(model)
                }
            }
            .distinct().sortedDescending()
    }

    fun recommendMain(provider: AiProvider, models: List<String>): String? =
        if (provider == AiProvider.GEMINI) recommendGeminiMain(models)
        else provider.defaultModel.takeIf { it in models }
            ?: models.firstOrNull { !Regex("-[0-9]{4}(-[0-9]{2}-[0-9]{2}|[0-9]{4})$").containsMatchIn(it) }
            ?: models.firstOrNull()

    fun recommendLight(provider: AiProvider, models: List<String>): String? = when (provider) {
        AiProvider.GEMINI -> recommendGeminiLite(models)
        AiProvider.CLAUDE -> "claude-haiku-5-5".takeIf { it in models } ?: models.firstOrNull { "haiku" in it }
        AiProvider.OPENAI -> "gpt-5-mini".takeIf { it in models } ?: models.firstOrNull { "mini" in it }
    }

    fun supportsThinking(provider: AiProvider, model: String): Boolean = when (provider) {
        AiProvider.GEMINI -> Regex("^gemini-([3-9]|[0-9]{2,})").containsMatchIn(model)
        AiProvider.CLAUDE -> Regex("^claude-(opus|sonnet|haiku|fable)-[5-9]").containsMatchIn(model)
        AiProvider.OPENAI -> Regex("^(gpt-[5-9]|o[0-9]+)").containsMatchIn(model)
    }

    fun discoverGeminiLite(key: String): String? = recommendGeminiLite(geminiModels(key))

    internal fun recommendGeminiLite(models: List<String>): String? {
        val stable = models.filter { "flash-lite" in it && "preview" !in it && "-exp" !in it }
        val versions = Regex("gemini-(\\d+)(?:\\.(\\d+))?")
        return stable.maxWithOrNull(compareBy(
            { versions.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            { versions.find(it)?.groupValues?.get(2)?.toIntOrNull() ?: 0 },
        ))
    }

    fun recommendGeminiMain(models: List<String>): String? =
        listOf(AiProvider.GEMINI.defaultModel, "gemini-4-hydrogen", "gemini-4-argon", "gemini-4-carbon")
            .firstOrNull { it in models }
            ?: models.firstOrNull { "flash" in it && "lite" !in it && "preview" !in it && "-exp" !in it }
            ?: recommendGeminiLite(models)
            ?: models.firstOrNull()

    private val RETRY = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)")

    /** Por minuto: espera o que o provedor pediu (mín. 5 min). Por dia: 3 h. Sem detalhe: 30 min. */
    private fun pauseFor(model: String, message: String): Long {
        val retry = RETRY.find(message)?.groupValues?.get(1)?.toLongOrNull()
        val minutes = when {
            message.contains("PerDay", ignoreCase = true) || message.contains("per day", ignoreCase = true) -> 180L
            retry != null -> maxOf(5L, retry / 60 + 1)
            else -> 30L
        }
        val until = System.currentTimeMillis() + minutes * 60_000
        prefs?.edit()?.putLong("ai_paused_until:$model", until)?.apply()
        return until
    }

    private val SUGGESTED = Regex("use (?:models/)?([A-Za-z0-9][A-Za-z0-9._-]+)")

    /** [jpeg]: imagem opcional (print), já reduzida. */
    /** [audio]: áudio opcional (bytes, mime), só no Gemini. */
    fun complete(
        provider: AiProvider, key: String, model: String, system: String, user: String,
        jpeg: ByteArray? = null, audio: Pair<ByteArray, String>? = null,
        /** Pedido com alguém esperando na tela: [fastTimeoutMs] de espera, sem segunda tentativa por demora. */
        fastTimeoutMs: Int? = null,
        thinking: AiThinking = AiThinking.LOW,
    ): String {
        if (fastTimeoutMs != null) Http.readTimeoutOverride.set(fastTimeoutMs)
        try {
            return completeGuarded(provider, key, model, system, user, jpeg, audio, thinking)
        } finally {
            Http.readTimeoutOverride.remove()
        }
    }

    private fun completeGuarded(
        provider: AiProvider, key: String, model: String, system: String, user: String,
        jpeg: ByteArray?, audio: Pair<ByteArray, String>?, thinking: AiThinking,
    ): String {
        pausedUntil(model).takeIf { it > 0 }?.let { throw RateLimited(it) }
        return try {
            completeOnce(provider, key, model, system, user, jpeg, audio, thinking)
        } catch (e: IOException) {
            if (e !is RateLimited && e.message.orEmpty().startsWith("HTTP 429")) throw RateLimited(pauseFor(model, e.message.orEmpty()))
            throw e
        }
    }

    private fun completeOnce(
        provider: AiProvider, key: String, model: String, system: String, user: String,
        jpeg: ByteArray?, audio: Pair<ByteArray, String>?, thinking: AiThinking,
    ): String {
        val image = jpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val sound = audio?.let { Base64.encodeToString(it.first, Base64.NO_WRAP) to it.second }
        val modelKey = provider to model
        val first = redirects[modelKey] ?: model
        return try {
            withRetryOnTimeout(provider) { call(provider, key, first, system, user, image, sound, thinking) }
        } catch (e: IOException) {
            // Modelo aposentado: tenta uma vez com o que o próprio provedor sugeriu.
            val msg = e.message.orEmpty()
            val suggested = SUGGESTED.find(msg)?.groupValues?.get(1)?.trimEnd('.')
            if (!msg.startsWith("HTTP 404") || suggested == null || suggested == first) throw e
            redirects[modelKey] = suggested
            withRetryOnTimeout(provider) { call(provider, key, suggested, system, user, image, sound, thinking) }
        }
    }

    /** Uma segunda tentativa se o provedor demorar demais; depois, mensagem clara. */
    private fun <T> withRetryOnTimeout(provider: AiProvider, block: () -> T): T {
        try {
            return try {
                block()
            } catch (e: IOException) {
                // 503/500 = provedor sobrecarregado por instantes: uma nova tentativa costuma passar.
                val m = e.message.orEmpty()
                if (!m.startsWith("HTTP 503") && !m.startsWith("HTTP 500")) throw e
                Thread.sleep(2000)
                block()
            }
        } catch (e: java.net.SocketTimeoutException) {
            // Interativo: não faz a pessoa esperar duas vezes.
            if (Http.readTimeoutOverride.get() != null) {
                throw IOException("a ${provider.label} demorou demais para responder")
            }
            try {
                return block()
            } catch (e2: java.net.SocketTimeoutException) {
                throw IOException("a ${provider.label} demorou demais para responder. Tente de novo em instantes ou use um modelo mais leve (flash-lite).")
            }
        }
    }

    private fun call(provider: AiProvider, key: String, model: String, system: String, user: String, image: String?, sound: Pair<String, String>?, thinking: AiThinking): String =
        when (provider) {
            AiProvider.CLAUDE -> claude(key, model, system, user, image, thinking)
            AiProvider.OPENAI -> openAi(key, model, system, user, image, thinking)
            AiProvider.GEMINI -> gemini(key, model, system, user, image, sound, thinking)
        }.trim()

    /** Transcrição pela OpenAI (o Gemini ouve o áudio direto no pedido). */
    fun transcribeOpenAi(key: String, audio: ByteArray, fileName: String, mime: String): String {
        val model = "gpt-4o-mini-transcribe"
        pausedUntil(model).takeIf { it > 0 }?.let { throw RateLimited(it) }
        val res = try {
            Http.postMultipart(
                "https://api.openai.com/v1/audio/transcriptions",
                mapOf("Authorization" to "Bearer $key"),
                mapOf("model" to model, "language" to "pt"),
                "file", fileName, mime, audio,
            )
        } catch (e: IOException) {
            if (e.message.orEmpty().startsWith("HTTP 429")) throw RateLimited(pauseFor(model, e.message.orEmpty()))
            throw e
        }
        return res.optString("text").trim()
    }

    private fun claude(key: String, model: String, system: String, user: String, image: String?, thinking: AiThinking): String {
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
        if (supportsThinking(AiProvider.CLAUDE, model) && thinking.apiValue != null) {
            body.put("thinking", JSONObject().put("type", "adaptive"))
                .put("output_config", JSONObject().put("effort", thinking.apiValue))
        }
        if (Regex("^claude-(opus-5|sonnet-5-5|fable-5-1)").containsMatchIn(model)) {
            body.put("fallbacks", "default")
            headers["anthropic-beta"] = "server-side-fallback-2026-07-01"
        }
        val res = Http.postJson("https://api.anthropic.com/v1/messages", headers, body)
        res.optJSONObject("usage")?.let { AiUsage.record(model, it.optLong("input_tokens"), it.optLong("output_tokens")) }
        if (res.optString("stop_reason") == "refusal") throw IOException("a IA recusou o pedido")
        val blocks = res.getJSONArray("content")
        return buildString {
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
    }

    private fun openAi(key: String, model: String, system: String, user: String, image: String?, thinking: AiThinking): String {
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
                    .put(JSONObject().put("role", if (Regex("^o[0-9]+").containsMatchIn(model)) "developer" else "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", content)),
            )
        if (supportsThinking(AiProvider.OPENAI, model) && thinking.apiValue != null) {
            body.put("reasoning_effort", thinking.apiValue)
        }
        val res = Http.postJson(
            "https://api.openai.com/v1/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body,
        )
        res.optJSONObject("usage")?.let { AiUsage.record(model, it.optLong("prompt_tokens"), it.optLong("completion_tokens")) }
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun gemini(key: String, model: String, system: String, user: String, image: String?, sound: Pair<String, String>?, thinking: AiThinking): String {
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
        val supportsThinkingLevel = supportsThinking(AiProvider.GEMINI, model)
        val level = thinking.apiValue?.takeIf { supportsThinkingLevel }
        val res = if (level != null) {
            try {
                Http.postJson(
                    url, headers,
                    JSONObject(body.toString()).put(
                        "generationConfig",
                        JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", level)),
                    ),
                )
            } catch (e: IOException) {
                // Modelo que não aceita o ajuste: manda de novo sem ele.
                val message = e.message.orEmpty()
                if (message.startsWith("HTTP 400") && "thinking" in message.lowercase()) {
                    Http.postJson(url, headers, body)
                } else throw e
            }
        } else {
            Http.postJson(url, headers, body)
        }
        // Raciocínio ("thoughts") é cobrado como saída.
        res.optJSONObject("usageMetadata")?.let {
            AiUsage.record(model, it.optLong("promptTokenCount"), it.optLong("candidatesTokenCount") + it.optLong("thoughtsTokenCount"))
        }
        val out = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString {
            for (i in 0 until out.length()) {
                val part = out.getJSONObject(i)
                if (!part.optBoolean("thought")) append(part.optString("text"))
            }
        }
    }

    /** Erro em palavras simples, sem o JSON cru do provedor. */
    fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is RateLimited -> m
            m.startsWith("HTTP 503") || m.startsWith("HTTP 500") -> "a IA está sobrecarregada no momento"
            m.startsWith("HTTP 401") || m.startsWith("HTTP 403") -> "a chave da IA foi recusada"
            m.startsWith("HTTP 404") -> "o modelo de IA não foi encontrado"
            m.startsWith("HTTP") -> "a IA respondeu com erro (${m.take(8)})"
            e is java.net.UnknownHostException || e is java.net.ConnectException -> "sem internet"
            else -> m.take(80)
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
