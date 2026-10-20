package com.trevisolli.agendawidget.widget

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trevisolli.agendawidget.data.AgendaRepository
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val snapshot = AgendaRepository.refresh(applicationContext)
        AgendaWidget.push(applicationContext, snapshot)
        // O gatilho de mudança na agenda dispara uma vez só; rearma a cada execução.
        watchCalendar(applicationContext)
        // Áudio esperando a IA: garante uma nova tentativa a cada atualização.
        if (com.trevisolli.agendawidget.smart.VoiceQueue.pending(applicationContext).isNotEmpty()) {
            com.trevisolli.agendawidget.smart.VoiceQueue.schedule(applicationContext)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("agenda-periodic", ExistingPeriodicWorkPolicy.UPDATE, request)
            watchCalendar(context)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "agenda-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RefreshWorker>().build(),
            )
        }

        private fun watchCalendar(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "agenda-calendar-change",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(constraints).build(),
            )
        }
    }
}
