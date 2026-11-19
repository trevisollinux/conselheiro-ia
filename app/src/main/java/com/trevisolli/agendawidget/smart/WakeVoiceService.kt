package com.trevisolli.agendawidget.smart

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.WeatherSource
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume

/** Monitoramento iniciado manualmente. O áudio da palavra de ativação é descartado após a detecção local. */
class WakeVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var voice: SpokenReply
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var recorder: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        if (!WakeModel.installed(this) || !hasMicrophone(this)) {
            state.value = "Prepare o modelo e permita o microfone"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ativação por voz", NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            voice = SpokenReply(this)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Consigliere:VoiceActivation").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            state.value = "O Android não liberou a escuta"
            FailLog.add(this, "Ativação por voz", e)
            stopSelf()
            return START_NOT_STICKY
        }
        state.value = STARTING
        job = scope.launch {
            try {
                listen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                state.value = "A escuta foi interrompida"
                FailLog.add(this@WakeVoiceService, "Ativação por voz", e)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PENDING_FLAGS)
        val stop = PendingIntent.getService(this, 0, Intent(this, WakeVoiceService::class.java).setAction(STOP), PENDING_FLAGS)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Ativação por voz ligada")
            .setContentText("Diga “Consigliere” para conversar por voz.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Desativar", stop)
            .build()
    }

    private class MicrophoneInterrupted : IOException("O Android interrompeu o microfone")

    private suspend fun listen() {
        Model(WakeModel.path(this).absolutePath).use { model ->
            var retries = 0
            while (hasMicrophone(this)) {
                try {
                    listenWithMicrophone(model) { retries = 0 }
                } catch (e: MicrophoneInterrupted) {
                    currentCoroutineContext().ensureActive()
                    if (++retries > 5) throw e
                    state.value = WAITING_FOR_MIC
                    delay(350L * retries)
                }
            }
            throw IOException("Permissão de microfone indisponível")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun listenWithMicrophone(model: Model, onAudio: () -> Unit) {
        val minimum = AudioRecord.getMinBufferSize(WakeAudio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microfone sem suporte ao formato de voz" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeAudio.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, WakeAudio.FRAME * 4),
        )
        recorder = audio
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microfone indisponível" }
            Recognizer(model, WakeAudio.RATE.toFloat(), WakePhrase.grammar).use { recognition ->
                recognition.setWords(true)
                recognition.setEndpointerDelays(5.0f, 0.45f, 15.0f)
                val frame = ShortArray(WakeAudio.FRAME)
                val context = currentCoroutineContext()
                val recent = WakeBuffer()
                startRecording(audio)
                state.value = LISTENING
                var nextCheck = 0L
                var silenced = false
                while (true) {
                    context.ensureActive()
                    val count = read(audio, frame)
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextCheck) {
                        nextCheck = now + 250
                        val muted = micSilenced(audio)
                        if (muted != silenced) {
                            silenced = muted
                            recent.clear()
                            recognition.reset()
                            state.value = if (muted) WAITING_FOR_MIC else LISTENING
                        }
                    }
                    if (count == 0) {
                        delay(20)
                        continue
                    }
                    if (silenced) continue
                    onAudio()
                    recent.add(frame, count)
                    if (!recognition.acceptWaveForm(frame, count)) continue
                    val activation = WakePhrase.activation(recognition.result)
                    val firstQuestion = if (activation == WakePhrase.Activation.QUESTION) {
                        recent.pcm()
                    } else null
                    recent.clear()
                    if (activation == null) continue
                    audio.stop()
                    lastActivation.value = System.currentTimeMillis()
                    converse(audio, frame, firstQuestion)
                    context.ensureActive()
                    recognition.reset()
                    startRecording(audio)
                    state.value = LISTENING
                }
            }
        } finally {
            recorder = null
            runCatching { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }
            audio.release()
        }
    }

    private suspend fun converse(audio: AudioRecord, frame: ShortArray, firstQuestion: ByteArray?) {
        val settings = Settings(this)
        val liveKey = settings.keyFor(AiProvider.GEMINI)
        if (settings.useAi && settings.aiProvider == AiProvider.GEMINI && liveKey.isNotBlank() && settings.geminiLiveVoice) {
            val locked = deviceLocked()
            val allowed = {
                settings.useAi && settings.aiProvider == AiProvider.GEMINI && settings.geminiLiveVoice &&
                    settings.keyFor(AiProvider.GEMINI) == liveKey && deviceLocked() == locked
            }
            state.value = LIVE_CONNECTING
            try {
                val outcome = GeminiLiveConversation.run(
                    context = this, key = liveKey, audio = audio, initialPcm = firstQuestion,
                    personal = !locked, allowed = allowed, allowPersonal = { !locked && !deviceLocked() },
                    beginRecording = ::startRecording, read = ::read,
                    onReady = {
                        state.value = LIVE_LISTENING
                        lastTiming.value = null
                        cue(if (firstQuestion == null) 180 else 75)
                    },
                    onAudioStarted = { ms -> lastTiming.value = VoiceTiming(ms, live = true) },
                )
                if (outcome == GeminiLiveConversation.Outcome.FINISHED || !allowed()) return
                FailLog.add(this, "Gemini Live", "Não consegui iniciar a sessão. Usei a conversa padrão.")
                Notify.post(this, CHANNEL, "Ativação por voz", RESPONSE_STATUS_ID,
                    "Gemini Live indisponível", "Usando a conversa padrão.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicrophoneInterrupted) {
                throw e
            } catch (e: Exception) {
                FailLog.add(this, "Gemini Live", AiClient.friendly(e))
                if (allowed()) say("A conversa em tempo real foi interrompida. Chame de novo.")
                return
            }
        }
        val lockedHistory = ArrayDeque<LockedReply>()
        var pending = firstQuestion
        repeat(8) {
            currentCoroutineContext().ensureActive()
            val key = settings.keyFor(AiProvider.GEMINI)
            if (!settings.useAi || settings.aiProvider != AiProvider.GEMINI || key.isBlank()) {
                say("Ative o Gemini nas configurações para conversar.")
                return
            }

            val pcm = pending ?: run {
                state.value = FOLLOW_UP
                cue()
                capture(audio, frame) ?: return
            }
            val inlineQuestion = pending != null
            pending = null
            state.value = WORKING
            if (inlineQuestion) cue(75)
            val processingStartedAt = SystemClock.elapsedRealtime()
            val locked = deviceLocked()
            var farewell = false
            val result = try {
                if (locked) {
                    val reply = lockedAnswer(settings, key, pcm, lockedHistory)
                    farewell = VoiceConversation.isFarewell(reply.heard)
                    if (!farewell && reply.heard.isNotBlank()) {
                        lockedHistory.addLast(reply)
                        if (lockedHistory.size > 4) lockedHistory.removeFirst()
                    }
                    ChatMessage(fromUser = false, text = reply.text)
                } else {
                    lockedHistory.clear()
                    Assistant.hear(this, WakeAudio.wav(pcm), "audio/wav", onFarewell = { farewell = true }, quickVoice = true) { !deviceLocked() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FailLog.add(this, "Conversa por ativação", AiClient.friendly(e))
                ChatMessage(fromUser = false, text = "Não consegui responder agora. Tente de novo.")
            }
            val responseReadyAt = SystemClock.elapsedRealtime()
            val modelMs = responseReadyAt - processingStartedAt
            lastTiming.value = VoiceTiming(modelMs)
            val markPlayback = { lastTiming.value = VoiceTiming(modelMs, SystemClock.elapsedRealtime() - responseReadyAt) }
            val canSpeak = locked || !deviceLocked()
            val text = when {
                farewell -> VoiceConversation.GOODBYE
                !canSpeak -> "Desbloqueie o celular para conferir a resposta."
                result.actions.isNotEmpty() -> "Abra o Consigliere para conferir e confirmar a ação."
                else -> result.text
            }
            val delivered = if (GeminiVoice.isConfigured(this)) {
                GeminiVoice.speak(this, text, stream = true, onStarted = markPlayback) { locked || !canSpeak || !deviceLocked() }
            } else {
                say(if (!locked && deviceLocked() && canSpeak) "Desbloqueie o celular para conferir a resposta." else text, markPlayback)
            }
            if (!delivered) {
                Notify.post(this, CHANNEL, "Ativação por voz", RESPONSE_STATUS_ID, "Consigliere não conseguiu falar",
                    "A escuta continua. Chame novamente.")
                return
            }
            if (farewell || result.actions.isNotEmpty()) return
        }
    }

    private suspend fun cue(lengthMs: Int = 180) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() ?: return
        try {
            if (tone.startTone(ToneGenerator.TONE_PROP_BEEP, lengthMs)) delay(lengthMs + 50L)
        } finally {
            tone.release()
        }
    }

    private data class LockedReply(val heard: String, val text: String)

    private fun lockedAnswer(settings: Settings, key: String, pcm: ByteArray, history: List<LockedReply>): LockedReply {
        val raw = AiClient.complete(
            AiProvider.GEMINI, key, settings.effectiveLightModelFor(AiProvider.GEMINI),
            "Você é o Consigliere. Responda em português do Brasil, com até duas frases curtas. " +
                "Devolva apenas JSON com as strings heard (transcrição do áudio atual) e reply (resposta). " +
                "Ignore a palavra Consigliere no começo da pergunta. Responda perguntas gerais e use o histórico recente para entender continuações. " +
                "Para agenda, e-mail, mensagens, contas, dados pessoais ou para executar ações, peça que desbloqueie o celular e pergunte de novo. " +
                "Para o tempo, use somente os dados fornecidos. Se não houver previsão para o dia pedido, diga que não tem a previsão. " +
                "Não afirme ter acessado dados pessoais nem executado ações.",
            buildString {
                appendLine("Hoje é ${LocalDate.now()}.")
                WeatherSource.load(this@WakeVoiceService)?.let { appendLine(WeatherSource.dailyForecastLine(it)) }
                history.forEach { turn ->
                    appendLine("Usuário: ${turn.heard.take(350)}")
                    appendLine("Consigliere: ${turn.text.take(350)}")
                }
                append("Responda à pergunta atual no áudio. Se estiver inaudível, peça para repetir.")
            },
            audio = WakeAudio.wav(pcm) to "audio/wav", fastTimeoutMs = 30_000,
            thinking = AiThinking.LOW,
        )
        val json = AiClient.extractJson(raw)
        return LockedReply(
            json?.optString("heard").orEmpty(),
            json?.optString("reply").orEmpty().ifBlank { "Não entendi a pergunta. Pode repetir?" },
        )
    }

    private fun deviceLocked() = getSystemService(KeyguardManager::class.java).isDeviceLocked

    /** Espera até seis segundos pela fala. Encerra após silêncio ou quinze segundos. */
    private suspend fun capture(audio: AudioRecord, frame: ShortArray): ByteArray? {
        val output = ByteArrayOutputStream()
        val tail = ArrayDeque<ByteArray>()
        var speech = false
        var samples = 0
        var quiet = 0
        var noise = 35.0
        val context = currentCoroutineContext()
        startRecording(audio)
        try {
            while (samples < WakeAudio.RATE * 15) {
                context.ensureActive()
                val count = read(audio, frame)
                if (count == 0) continue
                if (samples % WakeAudio.RATE < count && micSilenced(audio)) return null
                samples += count
                val talking = WakeAudio.isSpeech(frame, count, noise)
                if (!talking && !speech) noise = noise * 0.85 + WakeAudio.rms(frame, count) * 0.15
                val bytes = WakeAudio.pcm(frame, count)
                if (!speech && talking) {
                    speech = true
                    tail.forEach { output.write(it) }
                    tail.clear()
                }
                if (speech) {
                    output.write(bytes)
                    quiet = if (talking) 0 else quiet + count
                    if (quiet >= WakeAudio.RATE * WakeAudio.END_SILENCE_MS / 1000) break
                } else {
                    tail.addLast(bytes)
                    if (tail.size > 2) tail.removeFirst()
                    if (samples >= WakeAudio.RATE * 6) break
                }
            }
        } finally {
            audio.stop()
        }
        return if (speech) output.toByteArray() else null
    }

    private fun startRecording(audio: AudioRecord) {
        try {
            audio.startRecording()
        } catch (_: IllegalStateException) {
            throw MicrophoneInterrupted()
        }
        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicrophoneInterrupted()
    }

    private fun read(audio: AudioRecord, frame: ShortArray): Int {
        val count = audio.read(frame, 0, frame.size)
        if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION) throw MicrophoneInterrupted()
        if (count < 0) throw IOException("Falha na leitura do microfone ($count)")
        return count
    }

    private fun micSilenced(audio: AudioRecord): Boolean =
        Build.VERSION.SDK_INT >= 29 && audio.activeRecordingConfiguration?.isClientSilenced == true

    private suspend fun say(text: String, onStarted: () -> Unit = {}): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { result ->
                    voice.speak(text, onStarted = onStarted) { spoken -> if (result.isActive) result.resume(spoken) }
                }
            } ?: false
        } finally {
            voice.stop()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        recorder?.let { runCatching { it.stop() } }
        if (::voice.isInitialized) voice.close()
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (state.value in activeStates) state.value = IDLE
        super.onDestroy()
    }

    data class VoiceTiming(val modelMs: Long, val audioMs: Long? = null, val live: Boolean = false)

    companion object {
        private val lastTiming = MutableStateFlow<VoiceTiming?>(null)
        val timing = lastTiming.asStateFlow()
        private val lastActivation = MutableStateFlow<Long?>(null)
        val activationTime = lastActivation.asStateFlow()

        const val IDLE = "Escuta desativada"
        const val STARTING = "Iniciando a escuta…"
        const val LISTENING = "Escutando “Consigliere”"
        const val FOLLOW_UP = "Ouvindo a pergunta"
        const val WAITING_FOR_MIC = "Aguardando o Android liberar o microfone"
        const val LIVE_CONNECTING = "Conectando ao Gemini Live…"
        const val LIVE_LISTENING = "Conversa com Gemini Live em andamento"
        const val WORKING = "Conversa por voz em andamento"
        private val activeStates = setOf(STARTING, LISTENING, FOLLOW_UP, WAITING_FOR_MIC, LIVE_CONNECTING, LIVE_LISTENING, WORKING)
        private const val NOTICE_ID = 163
        private const val RESPONSE_STATUS_ID = 164
        private const val CHANNEL = "wake_voice"
        private const val STOP = "com.trevisolli.agendawidget.STOP_WAKE_VOICE"
        private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private val state = MutableStateFlow(IDLE)
        val status = state.asStateFlow()
        fun active(status: String) = status in activeStates

        fun hasMicrophone(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WakeVoiceService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeVoiceService::class.java))
        }
    }
}

internal object WakePhrase {
    // O vocabulário concorrente evita que frases parecidas sejam forçadas para a palavra de ativação.
    val grammar = """["consigliere","consiglieri","consigliere [unk]","consiglieri [unk]","considera","considerare","consiglia","consigliare","consiglio","consigli","conseguire","segue","conseguenze","tom sedie","tony blair","[unk]"]"""

    enum class Activation { WORD, QUESTION }

    fun activation(result: String): Activation? = runCatching {
        val words = JSONObject(result).optJSONArray("result") ?: return null
        val first = words.optJSONObject(0) ?: return null
        if (!isActivation(first.optString("word"), first.optDouble("conf", 0.0))) return null
        val question = (1 until words.length()).any { i ->
            val part = words.optJSONObject(i) ?: return@any false
            val word = part.optString("word")
            val duration = part.optDouble("end", 0.0) - part.optDouble("start", 0.0)
            duration >= 0.15 && word.isNotBlank() && word != "consigliere" && word != "consiglieri"
        }
        if (question) Activation.QUESTION else Activation.WORD
    }.getOrNull()

    fun isActivation(word: String, confidence: Double): Boolean =
        (word == "consigliere" || word == "consiglieri") && confidence >= 0.50
}
