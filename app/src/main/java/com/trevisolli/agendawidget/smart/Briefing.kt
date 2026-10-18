package com.trevisolli.agendawidget.smart

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.Source
import com.trevisolli.agendawidget.data.TodoistSource
import com.trevisolli.agendawidget.widget.AgendaWidget
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Resumos automáticos: plano do dia de manhã, balanço no fim da tarde. */
object Briefing {
    enum class Slot(val label: String) { MORNING("Manhã"), EVENING("Fim do dia") }

    data class Entry(val time: Long, val slot: Slot, val text: String)

    private const val CHANNEL = "briefings"
    private const val MAX = 40
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    // ---------- Montagem ----------

    suspend fun build(context: Context, slot: Slot): String {
        val snapshot = AgendaRepository.refresh(context)
        AgendaWidget.push(context, snapshot)
        val settings = Settings(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val completed = if (slot == Slot.EVENING && settings.todoistToken.isNotBlank()) {
            runCatching { TodoistSource.completedSince(settings.todoistToken, today.atStartOfDay(zone).toInstant()) }
                .getOrElse { settings.completionsToday() }
        } else {
            settings.completionsToday()
        }

        val ruleText = when (slot) {
            Slot.MORNING -> morningRules(snapshot.items, today, zone)
            Slot.EVENING -> eveningRules(snapshot.items, completed, today, zone)
        }
        if (!settings.aiReady) return ruleText
        return try {
            val text = AiClient.complete(
                settings.aiProvider, settings.aiKey, settings.effectiveModel,
                SYSTEM, aiPrompt(slot, snapshot.items, completed, today, zone),
            ).trim()
            text.ifBlank { ruleText }
        } catch (e: Exception) {
            ruleText
        }
    }

    private fun dateOf(i: AgendaItem, zone: ZoneId) = Instant.ofEpochMilli(i.startMillis).atZone(zone).toLocalDate()

    private fun line(i: AgendaItem, zone: ZoneId): String {
        val time = if (i.dateOnly) "" else Instant.ofEpochMilli(i.startMillis).atZone(zone).format(hhmm) + " "
        return "• $time${i.title}"
    }

    private fun morningRules(items: List<AgendaItem>, today: LocalDate, zone: ZoneId): String {
        val todays = items.filter { !it.overdue && dateOf(it, zone) == today }
        val events = todays.filter { it.source == Source.CALENDAR }
        val tasks = todays.filter { it.source != Source.CALENDAR }
        val overdue = items.filter { it.overdue }
        return buildString {
            append("☀️ Bom dia! ")
            if (todays.isEmpty() && overdue.isEmpty()) {
                append("Agenda livre hoje.")
            } else {
                append("Hoje: ${events.size} compromisso(s) · ${tasks.size} tarefa(s)")
                if (overdue.isNotEmpty()) append(" · ${overdue.size} atrasada(s)")
                append(".")
            }
            (events + tasks).take(5).forEach { append("\n").append(line(it, zone)) }
            if (overdue.isNotEmpty()) append("\nAtrasadas: ").append(overdue.take(3).joinToString(", ") { it.title })
        }
    }

    private fun eveningRules(items: List<AgendaItem>, completed: List<String>, today: LocalDate, zone: ZoneId): String {
        val pending = items.filter { it.source != Source.CALENDAR && (it.overdue || dateOf(it, zone) == today) }
        val tomorrow = items.filter { dateOf(it, zone) == today.plusDays(1) }
        return buildString {
            append("🌙 Fim do dia. ")
            if (completed.isEmpty()) {
                append("Nenhuma tarefa concluída registrada hoje.")
            } else {
                append("Você concluiu ${completed.size}: ").append(completed.take(5).joinToString(", "))
                if (completed.size > 5) append("…")
                append(".")
            }
            if (pending.isNotEmpty()) {
                append("\nFicou pendente: ").append(pending.take(4).joinToString(", ") { it.title })
            }
            if (tomorrow.isEmpty()) {
                append("\nAmanhã: nada marcado.")
            } else {
                append("\nAmanhã:")
                tomorrow.take(4).forEach { append("\n").append(line(it, zone)) }
            }
        }
    }

    private const val SYSTEM =
        "Você é o ConsigliereIA, um conselheiro pessoal calmo e acolhedor, direto e sem enrolação. " +
            "Escreva em português do Brasil, no máximo 5 linhas curtas, usando tópicos com '•' quando listar. " +
            "Manhã: o plano do dia e a prioridade número 1. Fim do dia: o que foi feito, o que ficou e o que vem amanhã. " +
            "Pode usar no máximo uma imagem curta de natureza (vento, chuva, sol). Sem markdown além dos '•'."

    private fun aiPrompt(slot: Slot, items: List<AgendaItem>, completed: List<String>, today: LocalDate, zone: ZoneId): String {
        val relevant = items.filter { it.overdue || !dateOf(it, zone).isAfter(today.plusDays(1)) }.take(30)
        return buildString {
            append("Momento: ${slot.label}, ${LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("EEEE dd/MM HH:mm", java.util.Locale("pt", "BR")))}\n")
            append("Itens (atrasadas, hoje e amanhã):\n")
            relevant.forEach {
                append("- ${it.title} (${com.trevisolli.agendawidget.data.Format.subtitle(it)})\n")
            }
            if (relevant.isEmpty()) append("(nada)\n")
            if (slot == Slot.EVENING) {
                append("Concluídas hoje: ${if (completed.isEmpty()) "nenhuma registrada" else completed.joinToString("; ")}\n")
            }
        }
    }

    // ---------- Histórico ----------

    private fun file(context: Context) = File(context.filesDir, "briefings.json")

    @Synchronized
    fun history(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Entry(o.getLong("time"), Slot.valueOf(o.getString("slot")), o.getString("text"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, entry: Entry) {
        val list = (listOf(entry) + history(context)).take(MAX)
        file(context).writeText(
            JSONArray().apply {
                list.forEach { put(JSONObject().put("time", it.time).put("slot", it.slot.name).put("text", it.text)) }
            }.toString(),
        )
    }

    // ---------- Notificação ----------

    fun notify(context: Context, slot: Slot, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Resumos do dia", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, slot.ordinal,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (slot == Slot.MORNING) "ConsigliereIA · Bom dia" else "ConsigliereIA · Fim do dia"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_compass)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1000 + slot.ordinal, notification)
    }

    // ---------- Agendamento ----------

    fun scheduleAll(context: Context) {
        val s = Settings(context)
        schedule(context, Slot.MORNING, s.briefMorningOn, s.briefMorningHour, ExistingWorkPolicy.REPLACE)
        schedule(context, Slot.EVENING, s.briefEveningOn, s.briefEveningHour, ExistingWorkPolicy.REPLACE)
    }

    internal fun schedule(context: Context, slot: Slot, enabled: Boolean, hour: Int, policy: ExistingWorkPolicy) {
        val wm = WorkManager.getInstance(context)
        val name = "brief-${slot.name}"
        if (!enabled) {
            wm.cancelUniqueWork(name)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        wm.enqueueUniqueWork(
            name,
            policy,
            OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("slot" to slot.name))
                .build(),
        )
    }
}

class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val slot = Briefing.Slot.valueOf(inputData.getString("slot") ?: return Result.failure())
        val s = Settings(applicationContext)
        val enabled = if (slot == Briefing.Slot.MORNING) s.briefMorningOn else s.briefEveningOn
        if (enabled) {
            val text = Briefing.build(applicationContext, slot)
            Briefing.save(applicationContext, Briefing.Entry(System.currentTimeMillis(), slot, text))
            Briefing.notify(applicationContext, slot, text)
        }
        // Próximo dia: entra na fila depois deste (não cancela a execução atual).
        val hour = if (slot == Briefing.Slot.MORNING) s.briefMorningHour else s.briefEveningHour
        Briefing.schedule(applicationContext, slot, enabled, hour, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
