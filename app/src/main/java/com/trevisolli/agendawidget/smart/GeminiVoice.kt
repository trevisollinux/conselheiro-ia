package com.trevisolli.agendawidget.smart

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Base64
import com.trevisolli.agendawidget.data.Http
import com.trevisolli.agendawidget.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume

internal object GeminiVoice {
    private const val API = "https://generativelanguage.googleapis.com/v1beta/"
    private const val MODEL = "gemini-3.8-flash-tts"
    private const val MAX_AUDIO = 4 * 1024 * 1024

    data class Design(val id: String, val sample: ByteArray?)

    private fun settings(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun voicePreference(key: String): String {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return "carioca_voice_$fingerprint"
    }

    fun voiceId(context: Context, key: String): String =
        settings(context).getString(voicePreference(key), "").orEmpty()
    suspend fun create(context: Context, key: String): Design = withContext(Dispatchers.IO) {
        val name = "Consigliere — Voz personalizada"
        val voice = JSONObject().put("model", MODEL).put("type", "prompted")
            .put("display_name", name).put("gender", "female").put("language_code", "pt-BR")
            .put("prompted", JSONObject().put(
                "input",
                "Voz de uma mulher adulta do Rio de Janeiro, em português brasileiro nativo, com sotaque carioca natural. " +
                    "Fala descontraída, clara, brincalhona e ligeiramente atrevida. Ritmo de conversa, sem exagerar o sotaque.",
            ))
        val result = post("voices", key, JSONObject().put("store", true).put("voice", voice), 45_000)
        val id = result.optString("id").takeIf { it.startsWith("voice_") }
            ?: throw IOException("O Gemini não devolveu o identificador da voz")
        settings(context).edit().putString(voicePreference(key), id).apply()
        val sample = result.optJSONObject("sample_audio")?.optString("data")?.let { runCatching { decode(it) }.getOrNull() }
        Design(id, sample)
    }

    suspend fun synthesize(key: String, id: String, text: String): ByteArray = withContext(Dispatchers.IO) {
        val line = text.replace(Regex("https?://\\S+"), "link na tela").replace(Regex("[*_#`]+"), "").take(1600)
        val content = JSONObject().put("type", "text").put("text", line)
        val body = JSONObject().put("model", MODEL).put("store", false)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(content))))
            .put("response_format", JSONObject().put("type", "audio").put("mime_type", "audio/wav"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", id))))
        val result = post("interactions", key, body, 15_000)
        val steps = result.optJSONArray("steps") ?: throw IOException("O Gemini não devolveu áudio")
        for (i in steps.length() - 1 downTo 0) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val parts = step.optJSONArray("content") ?: continue
            for (j in parts.length() - 1 downTo 0) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") == "audio") return@withContext decode(part.optString("data"))
            }
        }
        throw IOException("O Gemini não devolveu áudio")
    }

    fun isConfigured(context: Context): Boolean {
        val settings = Settings(context)
        val key = settings.keyFor(AiProvider.GEMINI)
        return settings.useAi && settings.aiProvider == AiProvider.GEMINI &&
            key.isNotBlank() && voiceId(context, key).isNotBlank()
    }

    suspend fun speak(context: Context, text: String, allowPlayback: () -> Boolean = { true }): Boolean {
        val settings = Settings(context)
        if (!settings.useAi || settings.aiProvider != AiProvider.GEMINI) return false
        val key = settings.keyFor(AiProvider.GEMINI)
        val id = voiceId(context, key)
        if (key.isBlank() || id.isBlank() || text.isBlank()) return false
        return try {
            play(synthesize(key, id, text), allowPlayback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FailLog.add(context, "Voz personalizada do Gemini", AiClient.friendly(e))
            false
        }
    }

    suspend fun play(wav: ByteArray, allowPlayback: () -> Boolean = { true }): Boolean = withContext(Dispatchers.Main.immediate) {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(object : MediaDataSource() {
                override fun getSize() = wav.size.toLong()
                override fun close() = Unit
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= wav.size) return -1
                    val length = minOf(size, wav.size - position.toInt())
                    wav.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
                    return length
                }
            })
            withTimeoutOrNull(45_000L) {
                suspendCancellableCoroutine { result ->
                    fun complete(ok: Boolean) { if (result.isActive) result.resume(ok) }
                    player.setOnPreparedListener {
                        if (result.isActive && allowPlayback()) {
                            runCatching { it.start() }.onFailure { complete(false) }
                        } else complete(false)
                    }
                    player.setOnCompletionListener { complete(true) }
                    player.setOnErrorListener { _, _, _ -> complete(false); true }
                    player.prepareAsync()
                }
            } ?: false
        } finally {
            player.release()
        }
    }

    private fun post(path: String, key: String, body: JSONObject, timeout: Int): JSONObject {
        Http.readTimeoutOverride.set(timeout)
        try {
            return Http.postJson(API + path, mapOf("x-goog-api-key" to key), body)
        } finally {
            Http.readTimeoutOverride.remove()
        }
    }

    private fun decode(data: String): ByteArray {
        if (data.isBlank() || data.length > MAX_AUDIO * 2) throw IOException("Áudio inválido")
        return Base64.decode(data, Base64.DEFAULT).also {
            if (it.size !in 44..MAX_AUDIO) throw IOException("Áudio inválido")
        }
    }
}
