package com.trevisolli.agendawidget.smart

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import javax.mail.AuthenticationFailedException
import java.util.concurrent.TimeUnit

class MailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runInterruptible(Dispatchers.IO) {
        try {
            MailSource.poll(applicationContext)
            Result.success()
        } catch (_: AuthenticationFailedException) {
            Result.failure()
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "mail-imap"

        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!MailSource.configured(context)) {
                work.cancelUniqueWork(NAME)
                return
            }
            work.enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MailWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
