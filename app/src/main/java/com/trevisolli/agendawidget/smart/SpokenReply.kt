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
    private var pending: Pair<String, (Boolean) -> Unit>? = null
    private var listener: Pair<String, (Boolean) -> Unit>? = null
    private var sequence = 0
    private var closed = false
    private val engine = TextToSpeech(context.applicationContext) { result ->
        main.post { if (!closed) initialize(result) }
    }

    private fun initialize(result: Int) {
        ready = result == TextToSpeech.SUCCESS && engine.setLanguage(Locale.forLanguageTag("pt-BR")) >= 0
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { complete(utteranceId, true) } }
            @Deprecated("Callback legado do Android")
            override fun onError(utteranceId: String?) { main.post { complete(utteranceId, false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
        })
        pending?.let { (text, done) ->
            pending = null
            speak(text, done)
        }
    }

    fun speak(text: String, done: (Boolean) -> Unit) {
        if (ready == null) {
            val request = text to done
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
        listener = id to done
        if (engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) complete(id, false)
    }

    private fun complete(id: String?, result: Boolean) {
        val entry = listener?.takeIf { it.first == id } ?: return
        listener = null
        entry.second(result)
    }

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
}
