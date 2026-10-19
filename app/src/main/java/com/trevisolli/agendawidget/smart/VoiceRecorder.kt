package com.trevisolli.agendawidget.smart

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Grava um áudio curto no próprio app (não depende do reconhecimento de voz do aparelho). */
class VoiceRecorder(private val context: Context, private val forOpenAi: Boolean) {
    private var recorder: MediaRecorder? = null
    private val file = File(context.cacheDir, if (forOpenAi) "voz.m4a" else "voz.aac")

    /** Gemini aceita AAC puro; a OpenAI prefere m4a. */
    val mime: String get() = if (forOpenAi) "audio/mp4" else "audio/aac"

    fun start() {
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
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
        recorder = r
    }

    /** Para e devolve o áudio; null se ficou curto demais. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || !file.exists() || file.length() < 2_000) return null
        return file.readBytes().also { file.delete() }
    }

    fun cancel() {
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        file.delete()
    }
}
