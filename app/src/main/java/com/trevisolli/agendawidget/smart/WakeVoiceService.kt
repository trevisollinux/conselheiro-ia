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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.Settings
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
            .setContentText("Diga “Consigliere” e espere o bip antes de perguntar.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Desativar", stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun listen() {
        val minimum = AudioRecord.getMinBufferSize(WakeAudio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microfone sem suporte ao formato de voz" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeAudio.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, WakeAudio.FRAME * 4),
        )
        recorder = audio
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microfone indisponível" }
            Model(WakeModel.path(this).absolutePath).use { model ->
                Recognizer(model, WakeAudio.RATE.toFloat(), WakePhrase.grammar).use { recognition ->
                    recognition.setWords(true)
                    val frame = ShortArray(WakeAudio.FRAME)
                    val context = currentCoroutineContext()
                    audio.startRecording()
                    state.value = LISTENING
                    while (true) {
                        context.ensureActive()
                        val count = read(audio, frame)
                        if (count == 0 || !recognition.acceptWaveForm(frame, count)) continue
                        if (!WakePhrase.matches(recognition.result)) continue
                        audio.stop()
                        try {
                            handleTurn(audio, frame)
                        } finally {
                            context.ensureActive()
                            recognition.reset()
                            audio.startRecording()
                            state.value = LISTENING
                        }
                    }
                }
            }
        } finally {
            recorder = null
            runCatching { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }
            audio.release()
        }
    }

    private suspend fun handleTurn(audio: AudioRecord, frame: ShortArray) {
        state.value = WORKING
        val settings = Settings(this)
        val key = settings.keyFor(AiProvider.GEMINI)
        if (!settings.useAi || settings.aiProvider != AiProvider.GEMINI || key.isBlank()) {
            say("Ative o Gemini nas configurações para conversar.")
            return
        }
        cue()
        val pcm = capture(audio, frame) ?: return
        val protected = deviceLocked()
        val result = try {
            if (protected) ChatMessage(fromUser = false, text = lockedAnswer(settings, key, pcm))
            else Assistant.hear(this, WakeAudio.wav(pcm), "audio/wav") { !deviceLocked() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FailLog.add(this, "Conversa por ativação", AiClient.friendly(e))
            ChatMessage(fromUser = false, text = "Não consegui responder agora. Tente de novo.")
        }
        val text = when {
            !protected && deviceLocked() -> "Desbloqueie o celular para conferir a resposta."
            result.actions.isNotEmpty() -> "Abra o Consigliere para conferir e confirmar a ação."
            else -> result.text
        }
        if (GeminiVoice.isConfigured(this)) {
            GeminiVoice.speak(this, text) { protected || !deviceLocked() }
        } else {
            say(if (!protected && deviceLocked()) "Desbloqueie o celular para conferir a resposta." else text)
        }
    }

    private suspend fun cue() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() ?: return
        try {
            if (tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)) delay(230)
        } finally {
            tone.release()
        }
    }

    private fun lockedAnswer(settings: Settings, key: String, pcm: ByteArray): String = AiClient.complete(
        AiProvider.GEMINI, key, settings.effectiveModelFor(AiProvider.GEMINI),
        "Você é o Consigliere. Responda em português do Brasil em até três frases curtas para serem faladas. " +
            "Você só tem acesso à pergunta em áudio. Responda perguntas gerais. Para solicitar agenda, e-mail, mensagens, " +
            "contas, dados pessoais ou executar ações, diga que é preciso desbloquear o celular e perguntar novamente. " +
            "Não afirme ter acessado esses dados ou executado ações. Não use Markdown.",
        "Responda à pergunta do áudio. Se estiver inaudível, peça que a pessoa repita.",
        audio = WakeAudio.wav(pcm) to "audio/wav", fastTimeoutMs = 45_000,
        thinking = AiThinking.LOW,
    ).ifBlank { "Não entendi a pergunta. Tente de novo." }

    private fun deviceLocked() = getSystemService(KeyguardManager::class.java).isDeviceLocked

    /** Espera até quatro segundos pela fala. Encerra após silêncio ou quinze segundos. */
    private suspend fun capture(audio: AudioRecord, frame: ShortArray): ByteArray? {
        val output = ByteArrayOutputStream()
        val tail = ArrayDeque<ByteArray>()
        var speech = false
        var samples = 0
        var quiet = 0
        var noise = 35.0
        val context = currentCoroutineContext()
        audio.startRecording()
        try {
            while (samples < WakeAudio.RATE * 15) {
                context.ensureActive()
                val count = read(audio, frame)
                if (count == 0) continue
                samples += count
                val talking = WakeAudio.isSpeech(frame, count, noise)
                if (!talking && !speech) noise = noise * 0.85 + WakeAudio.rms(frame, count) * 0.15
                val bytes = ByteArray(count * 2)
                for (i in 0 until count) {
                    val sample = frame[i].toInt()
                    bytes[i * 2] = sample.toByte()
                    bytes[i * 2 + 1] = (sample shr 8).toByte()
                }
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
                    if (samples >= WakeAudio.RATE * 4) break
                }
            }
        } finally {
            audio.stop()
        }
        return if (speech) output.toByteArray() else null
    }

    private fun read(audio: AudioRecord, frame: ShortArray): Int {
        val count = audio.read(frame, 0, frame.size)
        if (count < 0) throw IOException("O Android interrompeu o microfone")
        return count
    }

    private suspend fun say(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { result ->
                    voice.speak(text) { spoken -> if (result.isActive) result.resume(spoken) }
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

    companion object {
        const val IDLE = "Escuta desativada"
        const val STARTING = "Iniciando a escuta…"
        const val LISTENING = "Escutando “Consigliere”"
        const val WORKING = "Conversa por voz em andamento"
        private val activeStates = setOf(STARTING, LISTENING, WORKING)
        private const val NOTICE_ID = 163
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
    val grammar = """["consigliere","consiglieri","considera","considerare","consiglia","consigliare","consiglio","consigli","conseguire","segue","conseguenze","tom sedie","tony blair","[unk]"]"""

    fun matches(result: String): Boolean = runCatching {
        val json = JSONObject(result)
        val words = json.optJSONArray("result") ?: return false
        val word = words.optJSONObject(0) ?: return false
        words.length() == 1 && isActivation(word.optString("word"), word.optDouble("conf", 0.0))
    }.getOrDefault(false)

    fun isActivation(word: String, confidence: Double): Boolean =
        (word == "consigliere" || word == "consiglieri") && confidence >= 0.50
}
