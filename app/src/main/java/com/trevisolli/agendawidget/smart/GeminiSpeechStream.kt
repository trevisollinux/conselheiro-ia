package com.trevisolli.agendawidget.smart

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A API entrega PCM em eventos SSE. Toca cada trecho enquanto os próximos são gerados. */
internal object GeminiSpeechStream {
    private const val RATE = 24_000
    private const val FRAME_BYTES = 2
    private const val MAX_BYTES = RATE * FRAME_BYTES * 60

    class Unsupported : IOException("Síntese em partes indisponível")

    suspend fun play(
        url: String, key: String, body: JSONObject, allowPlayback: () -> Boolean, onStarted: () -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        var track: AudioTrack? = null
        var bytesWritten = 0
        val context = currentCoroutineContext()
        try {
            if (!allowPlayback()) return@withContext false
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("x-goog-api-key", key)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = connection.responseCode
            if (status in setOf(400, 404, 405, 501)) throw Unsupported()
            if (status !in 200..299) throw IOException("HTTP $status na síntese de voz")
            if (!connection.contentType.orEmpty().startsWith("text/event-stream", ignoreCase = true)) throw Unsupported()

            connection.inputStream.bufferedReader().use { reader ->
                val event = StringBuilder()
                while (true) {
                    context.ensureActive()
                    if (!allowPlayback()) return@withContext false
                    val line = reader.readLine()
                    if (line != null && !line.isBlank()) {
                        if (line.startsWith("data:")) {
                            if (event.isNotEmpty()) event.append('\n')
                            event.append(line.removePrefix("data:").trimStart())
                            if (event.length > MAX_BYTES * 2) throw IOException("Trecho de áudio grande demais")
                        }
                        continue
                    }
                    if (event.isNotEmpty() && event.toString() != "[DONE]") {
                        val json = JSONObject(event.toString())
                        val type = json.optString("event_type")
                        if (type == "error" || type == "interaction.failed") throw IOException("O Gemini interrompeu a síntese")
                        val delta = json.optJSONObject("delta")
                        if (type == "step.delta" && delta?.optString("type") == "audio") {
                            val pcm = Base64.decode(delta.optString("data"), Base64.DEFAULT)
                            if (pcm.size % FRAME_BYTES != 0 || bytesWritten + pcm.size > MAX_BYTES) {
                                throw IOException("Trecho de áudio inválido")
                            }
                            if (pcm.isNotEmpty()) {
                                val output = track ?: newTrack().also {
                                    track = it
                                    if (!allowPlayback()) return@withContext false
                                    it.play()
                                }
                                var offset = 0
                                while (offset < pcm.size) {
                                    context.ensureActive()
                                    if (!allowPlayback()) return@withContext false
                                    val count = output.write(pcm, offset, minOf(2400, pcm.size - offset), AudioTrack.WRITE_BLOCKING)
                                    if (count <= 0) throw IOException("Não consegui tocar o áudio")
                                    if (bytesWritten == 0) onStarted()
                                    offset += count
                                    bytesWritten += count
                                }
                            }
                        }
                    }
                    event.clear()
                    if (line == null) break
                }
            }

            val output = track ?: throw IOException("O Gemini não devolveu áudio")
            val frames = bytesWritten / FRAME_BYTES
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (output.playbackHeadPosition < frames && SystemClock.elapsedRealtime() < deadline) {
                if (!allowPlayback()) return@withContext false
                delay(20)
            }
            output.playbackHeadPosition >= frames
        } finally {
            track?.release()
            connection.disconnect()
        }
    }

    private fun newTrack(): AudioTrack {
        val size = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(size > 0) { "Formato de áudio indisponível" }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(size, 4_800))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
