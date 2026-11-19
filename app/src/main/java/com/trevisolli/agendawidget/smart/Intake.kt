package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.widget.RefreshWorker
import java.time.Instant
import java.time.ZoneId

/** A mesma triagem para e-mail e notificações. */
object Intake {
    fun process(
        context: Context, origin: String, sender: String, body: String, isEmail: Boolean,
        isGroup: Boolean = false, replyTo: String? = null, sourceId: String? = null,
        receivedAt: Long = System.currentTimeMillis(), conversationContext: String? = null,
        paymentContext: String? = null,
    ) {
        val label = if (sender.isBlank()) origin else "$origin · $sender"
        val groupIgnored = isGroup && !Settings(context).notifGroups
        InboxStore.add(context, InboxStore.Msg(origin, sender, body, receivedAt))
        val parcels = Parcels.scan(context, body, label)
        parcels.forEach { NotificationWatcher.notifyParcel(context, it, label) }
        val bills = Bills.scan(context, paymentContext.takeUnless { groupIgnored } ?: body, sender.ifBlank { origin }, receivedAt, sourceId, !groupIgnored)
        bills.fresh.forEach { Bills.notify(context, it) }
        val reason = when {
            bills.fresh.isNotEmpty() -> "💸 conta guardada"
            bills.matched -> "conta já registrada ou paga"
            groupIgnored -> "grupo (ignorado para sugestões)"
            isEmail && NotificationWatcher.AUTOMATED.containsMatchIn("${sender.lowercase()} ${body.lineSequence().first().lowercase()}") ->
                "e-mail automático (ignorado para sugestões)"
            else -> null
        }
        if (reason != null) {
            NotifLog.add(context, label, body, reason)
            return
        }
        var why = "sem compromisso"
        val date = Instant.ofEpochMilli(receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val suggestion = Brain.fromNotification(context, conversationContext ?: body, label, strict = isEmail, receivedOn = date) { why = it }
            ?.let { if (replyTo != null) it.copy(replyTo = replyTo) else it }
        when {
            suggestion == null -> {
                val result = if (parcels.isEmpty()) why else "📦 rastreio guardado · $why"
                NotifLog.add(context, label, body, result)
            }
            SuggestionStore.add(context, suggestion) -> {
                NotifLog.add(context, label, body, "📌 sugestão: ${suggestion.title}")
                NotificationWatcher.notifySuggestion(context, suggestion)
                RefreshWorker.runNow(context)
            }
            else -> NotifLog.add(context, label, body, "já sugerido")
        }
    }
}
