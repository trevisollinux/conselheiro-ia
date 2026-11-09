package com.trevisolli.agendawidget.smart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.trevisolli.agendawidget.MeetingActivity
import com.trevisolli.agendawidget.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Estado da gravação, lido pela tela. */
object MeetingState {
    data class S(
        val recording: Boolean = false, val seconds: Int = 0, val silentSeconds: Int = 0, val level: Int = 0,
        /** Pausado porque há uma ligação: o microfone fica com o WhatsApp/telefone. */
        val pausedForCall: Boolean = false,
        val blockedByCall: Boolean = false,
    )

    val state = MutableStateFlow(S())
    const val MAX_SECONDS = 50 * 60
}

/**
 * Grava uma conversa (reunião, consulta, ligação no viva-voz) em primeiro plano, com a tela apagada ou em outro app.
 * Ao parar, o Gemini resume e cria os combinados; se a IA estiver fora, a gravação espera na fila.
 */
class MeetingService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "meeting"

        fun file(context: Context) = File(context.cacheDir, "reuniao.aac")

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, MeetingService::class.java))

        fun stop(context: Context) = context.startService(Intent(context, MeetingService::class.java).setAction(ACTION_STOP))
    }

    private var recorder: MediaRecorder? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun inCall(): Boolean {
        val mode = getSystemService(android.media.AudioManager::class.java)?.mode ?: return false
        return mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (recorder != null) return START_NOT_STICKY
        startForegroundCompat()
        // Em ligação, o microfone é da ligação: gravar aqui cortaria a sua voz no WhatsApp.
        if (inCall()) {
            MeetingState.state.value = MeetingState.S(blockedByCall = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val f = file(this).apply { delete() }
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            stopSelf()
            return START_NOT_STICKY
        }
        recorder = r
        MeetingState.state.value = MeetingState.S(recording = true)
        scope.launch {
            while (isActive && recorder != null) {
                delay(1000)
                val s = MeetingState.state.value
                // Entrou uma ligação: pausa e devolve o microfone; quando ela acaba, volta a gravar sozinho.
                val call = inCall()
                if (call && !s.pausedForCall) {
                    runCatching { recorder?.pause() }
                    MeetingState.state.value = s.copy(pausedForCall = true, silentSeconds = 0)
                    continue
                }
                if (!call && s.pausedForCall) {
                    runCatching { recorder?.resume() }
                    MeetingState.state.value = s.copy(pausedForCall = false)
                    continue
                }
                if (s.pausedForCall) continue
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                MeetingState.state.value = s.copy(
                    seconds = s.seconds + 1,
                    silentSeconds = if (amp < 30) s.silentSeconds + 1 else 0,
                    level = amp,
                )
                if (s.seconds + 1 >= MeetingState.MAX_SECONDS) finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Gravação de conversa", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 91, Intent(this, MeetingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 92, Intent(this, MeetingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("🎙️ Gravando a conversa")
            .setContentText("Toque em Parar para eu resumir")
            .setContentIntent(open)
            .addAction(0, "⏹ Parar e resumir", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(93, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(93, n)
        }
    }

    /** Para a gravação e manda para o resumo em segundo plano. */
    private fun finish() {
        val r = recorder ?: run { stopSelf(); return }
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val seconds = MeetingState.state.value.seconds
        MeetingState.state.value = MeetingState.S()
        val f = file(this)
        val app = applicationContext
        if (ok && f.exists() && f.length() > 4_000 && seconds >= 5) {
            val audio = f.readBytes()
            f.delete()
            Background.scope.launch {
                val answer = Assistant.handleMeeting(app, audio, "audio/aac")
                Notify.post(app, "meeting_done", "Resumos de conversas", 94, "📝 Resumo da conversa", answer.text, answer.actions)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        MeetingState.state.value = MeetingState.S()
        scope.cancel()
        super.onDestroy()
    }
}
