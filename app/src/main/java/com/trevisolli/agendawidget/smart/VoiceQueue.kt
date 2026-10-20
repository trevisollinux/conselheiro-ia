package com.trevisolli.agendawidget.smart

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Áudios que a IA não conseguiu ouvir na hora: ficam guardados e são processados quando ela voltar. */
object VoiceQueue {
    private const val CHANNEL = "voice"

    private fun dir(context: Context) = File(context.filesDir, "voice_pending").apply { mkdirs() }

    fun add(context: Context, audio: ByteArray, mime: String) {
        val ext = if (mime == "audio/mp4") "m4a" else "aac"
        File(dir(context), "${System.currentTimeMillis()}.$ext").writeBytes(audio)
        schedule(context)
    }

    fun pending(context: Context): List<File> = dir(context).listFiles()?.sortedBy { it.name }.orEmpty()

    /** [now]: tenta já (botão "Tentar agora"); senão espera 1 min ou o fim da pausa por limite. */
    fun schedule(context: Context, now: Boolean = false) {
        val paused = AiClient.pausedUntil(com.trevisolli.agendawidget.data.Settings(context).effectiveModel) - System.currentTimeMillis()
        val wait = if (now) 0L else maxOf(60_000L, paused)
        // Linear de 2 em 2 min: sobrecarga (503) costuma passar em minutos, não em horas.
        val request = OneTimeWorkRequestBuilder<VoiceQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(wait, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("voice-queue", ExistingWorkPolicy.REPLACE, request)
    }

    fun notify(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Áudios anotados", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 7, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("🎤 Áudio anotado")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(System.currentTimeMillis().toInt(), n) }
    }
}

class VoiceQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        for (file in VoiceQueue.pending(ctx)) {
            val mime = if (file.extension == "m4a") "audio/mp4" else "audio/aac"
            try {
                val answer = Assistant.hear(ctx, file.readBytes(), mime)
                file.delete()
                VoiceQueue.notify(ctx, answer.text)
            } catch (e: IOException) {
                // IA ainda fora: tenta mais tarde (2, 4, 6… min; o refresh de 30 min também reagenda).
                return if (runAttemptCount < 20) Result.retry() else Result.failure()
            } catch (e: Exception) {
                file.delete()
                VoiceQueue.notify(ctx, "Não consegui entender um áudio guardado: ${e.message?.take(80)}")
            }
        }
        return Result.success()
    }
}
