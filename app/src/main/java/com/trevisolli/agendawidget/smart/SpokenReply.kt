package com.trevisolli.agendawidget.smart

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Lê uma resposta por vez e libera o microfone para a próxima fala só depois do áudio. */
internal class SpokenReply(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready: Boolean? = null
    private data class Request(val text: String, val onStarted: () -> Unit, val done: (Boolean) -> Unit)
    private var pending: Request? = null
    private var listener: Pair<String, Request>? = null
    private var sequence = 0
    private var closed = false
    // Prefere a voz do Google: o motor padrão de alguns aparelhos (ex.: Vivo) fala português com sotaque estranho.
    // Sem o Google instalado, o Android usa o motor padrão do celular.
    private val engine = TextToSpeech(context.applicationContext, { result ->
        main.post { if (!closed) initialize(result) }
    }, GOOGLE_TTS)

    private fun initialize(result: Int) {
        val brazil = Locale.forLanguageTag("pt-BR")
        ready = result == TextToSpeech.SUCCESS && engine.setLanguage(brazil) >= 0
        if (ready == true) bestVoice(brazil)?.let { runCatching { engine.voice = it } }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { listener?.takeIf { it.first == utteranceId }?.second?.onStarted?.invoke() }
            }
            override fun onDone(utteranceId: String?) { main.post { complete(utteranceId, true) } }
            @Deprecated("Callback legado do Android")
            override fun onError(utteranceId: String?) { main.post { complete(utteranceId, false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
        })
        pending?.let { request ->
            pending = null
            speak(request.text, request.onStarted, request.done)
        }
    }

    fun speak(text: String, onStarted: () -> Unit = {}, done: (Boolean) -> Unit) {
        if (ready == null) {
            val request = Request(text, onStarted, done)
            pending = request
            main.postDelayed({
                if (pending === request) {
                    pending = null
                    done(false)
                }
            }, 5000)
            return
        }
        if (ready != true) {
            done(false)
            return
        }
        val line = text.replace(Regex("https?://\\S+"), "link na tela")
            .replace(Regex("[*_#`]+"), "")
            .take(TextToSpeech.getMaxSpeechInputLength())
        val id = "reply-${++sequence}"
        listener = id to Request(text, onStarted, done)
        if (engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) complete(id, false)
    }

    private fun complete(id: String?, result: Boolean) {
        val entry = listener?.takeIf { it.first == id } ?: return
        listener = null
        entry.second.done(result)
    }

    /** Voz em português do Brasil já baixada, de melhor qualidade (evita a voz padrão robótica). */
    private fun bestVoice(locale: Locale): android.speech.tts.Voice? = runCatching {
        engine.voices.orEmpty()
            .filter { it.locale.language == locale.language && it.locale.country == locale.country }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .maxWithOrNull(compareBy<android.speech.tts.Voice>({ !it.isNetworkConnectionRequired }, { it.quality }))
    }.getOrNull()

    fun stop() {
        pending = null
        listener = null
        engine.stop()
    }

    fun close() {
        closed = true
        stop()
        engine.shutdown()
    }

    private companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
    }
}
