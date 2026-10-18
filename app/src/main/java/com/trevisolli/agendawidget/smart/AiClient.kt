package com.trevisolli.agendawidget.smart

import com.trevisolli.agendawidget.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

enum class AiProvider(val label: String, val defaultModel: String, val keyHint: String) {
    CLAUDE("Claude", "claude-opus-5-5", "console.anthropic.com → API Keys"),
    OPENAI("ChatGPT", "gpt-5-mini", "platform.openai.com → API keys"),
    GEMINI("Gemini", "gemini-2.5-flash", "aistudio.google.com → Get API key"),
}

/** Chamada única de texto para a IA escolhida pelo usuário (chave dele, sem servidor no meio). */
object AiClient {
    fun complete(provider: AiProvider, key: String, model: String, system: String, user: String): String =
        when (provider) {
            AiProvider.CLAUDE -> claude(key, model, system, user)
            AiProvider.OPENAI -> openAi(key, model, system, user)
            AiProvider.GEMINI -> gemini(key, model, system, user)
        }.trim()

    private fun claude(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4000)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
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
        val content = res.getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
    }

    private fun openAi(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val res = Http.postJson(
            "https://api.openai.com/v1/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body,
        )
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun gemini(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))),
                ),
            )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val res = Http.postJson(url, mapOf("x-goog-api-key" to key), body)
        val parts = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
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
