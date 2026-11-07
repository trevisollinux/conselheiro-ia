package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.widget.RefreshWorker

/** Uma mensagem recebida (notificação ou e-mail): encomendas, contas, sugestões de compromisso e diagnóstico. */
object Intake {
    fun process(context: Context, origin: String, sender: String, body: String, isEmail: Boolean, isGroup: Boolean = false, replyable: Boolean = false) {
        val settings = Settings(context)
        val label = if (sender.isBlank()) origin else "$origin · $sender"
        InboxStore.add(context, InboxStore.Msg(origin, sender, body, System.currentTimeMillis()))
        // Código de rastreio e contas (mesmo em grupo ou e-mail automático de loja).
        val parcels = Parcels.scan(context, body, label)
        parcels.forEach { NotificationWatcher.notifyParcel(context, it, label) }
        val bills = Bills.scan(context, body, sender.ifBlank { origin })
        bills.forEach { Bills.notify(context, it) }
        val extra = when {
            bills.isNotEmpty() -> "💸 conta guardada"
            parcels.isNotEmpty() -> "📦 rastreio guardado"
            else -> null
        }
        if (isGroup && !settings.notifGroups) {
            NotifLog.add(context, label, body, extra ?: "grupo (ignorado)")
            return
        }
        // E-mails automáticos (avisos do Google, login, propaganda, códigos) não viram sugestão.
        if (isEmail && NotificationWatcher.AUTOMATED.containsMatchIn((sender + " " + body).lowercase())) {
            NotifLog.add(context, label, body, extra ?: "e-mail automático (ignorado)")
            return
        }
        var why = "sem compromisso"
        val suggestion = Brain.fromNotification(context, body, label, strict = isEmail) { why = it }
            ?.let { if (replyable && sender.isNotBlank()) it.copy(replyTo = sender) else it }
        when {
            suggestion == null -> NotifLog.add(context, label, body, extra ?: why)
            SuggestionStore.add(context, suggestion) -> {
                NotifLog.add(context, label, body, "📌 sugestão: ${suggestion.title}")
                NotificationWatcher.notifySuggestion(context, suggestion)
                RefreshWorker.runNow(context)
            }
            else -> NotifLog.add(context, label, body, "já sugerido")
        }
    }
}
