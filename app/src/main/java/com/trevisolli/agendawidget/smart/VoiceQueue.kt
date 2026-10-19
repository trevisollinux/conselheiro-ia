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

    fun schedule(context: Context) {
        // Se a IA está pausada pelo limite, nem tenta antes da hora.
        val wait = maxOf(60_000L, AiClient.pausedUntil - System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<VoiceQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(wait, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
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
            .setSmallIcon(R.drawable.ic_mic)
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
                // IA ainda fora: tenta mais tarde (desiste depois de ~1 dia).
                return if (runAttemptCount < 8) Result.retry() else Result.failure()
            } catch (e: Exception) {
                file.delete()
                VoiceQueue.notify(ctx, "Não consegui entender um áudio guardado: ${e.message?.take(80)}")
            }
        }
        return Result.success()
    }
}
