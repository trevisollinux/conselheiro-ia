package com.trevisolli.agendawidget.smart

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

/** Grava um áudio curto no próprio app (não depende do reconhecimento de voz do aparelho). */
class VoiceRecorder(private val context: Context, private val forOpenAi: Boolean) {
    private var recorder: MediaRecorder? = null
    private val file = File(context.cacheDir, "voz-${UUID.randomUUID()}." + if (forOpenAi) "m4a" else "aac")

    /** Gemini aceita AAC puro; a OpenAI prefere m4a. */
    val mime: String get() = if (forOpenAi) "audio/mp4" else "audio/aac"

    fun start() {
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = r
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(if (forOpenAi) MediaRecorder.OutputFormat.MPEG_4 else MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(32_000)
        r.setMaxDuration(90_000) // a tela para em 60 s; isso é só uma trava
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
    }

    fun maxAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Para e devolve o áudio; null se ficou curto demais. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return try {
            if (ok && file.isFile && file.length() >= 2_000) file.readBytes() else null
        } finally {
            file.delete()
        }
    }

    fun cancel() {
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        file.delete()
    }
}
