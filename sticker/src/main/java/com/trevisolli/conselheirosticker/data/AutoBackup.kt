package com.trevisolli.conselheirosticker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Backup automático: uma vez por semana, o mesmo .zip do "Exportar" vai para uma pasta do celular
 * que o usuário escolheu (fica lá mesmo se o app for desinstalado). Guarda os 3 mais recentes.
 */
object AutoBackup {
    private const val PREFS = "sticker"
    private const val KEY_FOLDER = "backup_folder"
    private const val KEY_LAST = "backup_last"
    private const val KEY_FAILED = "backup_failed"
    private const val WORK = "auto-backup"
    private const val PREFIX = "unusual-stickers-auto-"
    private const val KEEP = 3

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(ctx: Context): Uri? = prefs(ctx).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun folderName(ctx: Context): String? =
        folder(ctx)?.let { runCatching { DocumentFile.fromTreeUri(ctx, it)?.name }.getOrNull() }

    /** Quando foi o último backup que deu certo (0 = nunca). */
    fun lastRun(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST, 0L)

    /** O último backup tentado falhou (pasta apagada, sem permissão…). */
    fun failed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FAILED, false)

    fun enable(ctx: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(tree, flags)
        folder(ctx)?.takeIf { it != tree }?.let { old ->
            runCatching { ctx.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs(ctx).edit().putString(KEY_FOLDER, tree.toString()).putBoolean(KEY_FAILED, false).apply()
        schedule(ctx, ExistingPeriodicWorkPolicy.UPDATE)
        runNow(ctx)
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        folder(ctx)?.let { old ->
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs(ctx).edit().remove(KEY_FOLDER).remove(KEY_FAILED).apply()
    }

    /** Garante o agendamento semanal (chamado ao abrir o app). */
    fun schedule(ctx: Context, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        if (folder(ctx) == null) return
        val req = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, policy, req)
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BackupWorker>().build(),
        )
    }

    /** Grava o backup agora. Devolve false se não há pasta ou ela não aceita gravar. */
    fun write(ctx: Context): Boolean {
        val tree = folder(ctx) ?: return false
        val ok = runCatching {
            val dir = DocumentFile.fromTreeUri(ctx, tree) ?: error("pasta sumiu")
            if (!dir.canWrite()) error("sem permissão")
            val store = PackStore(ctx)
            val ids = store.list().map { it.id }
            if (ids.isEmpty()) return@runCatching true
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val name = "$PREFIX$day.zip"
            dir.findFile(name)?.delete()
            val file = dir.createFile("application/zip", name) ?: error("não consegui criar o arquivo")
            ctx.contentResolver.openOutputStream(file.uri)?.use { Backup(ctx, store).export(ids, it) }
                ?: error("não consegui gravar")
            // Fica só com os mais recentes.
            dir.listFiles().filter { it.name?.startsWith(PREFIX) == true }
                .sortedByDescending { it.name }
                .drop(KEEP)
                .forEach { it.delete() }
            true
        }.getOrDefault(false)
        prefs(ctx).edit().apply {
            putBoolean(KEY_FAILED, !ok)
            if (ok) putLong(KEY_LAST, System.currentTimeMillis())
        }.apply()
        return ok
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (AutoBackup.folder(applicationContext) == null || AutoBackup.write(applicationContext)) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.failure()
}
