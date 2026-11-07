package com.trevisolli.agendawidget.smart

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trevisolli.agendawidget.ConfirmActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.widget.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Lê notificações de apps de mensagem e sugere compromissos encontrados nelas. */
class NotificationWatcher : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val origin = WATCHED[sbn.packageName] ?: return
        val settings = Settings(this)
        if (!settings.detectNotifications) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val label = if (sender.isBlank()) origin else "$origin · $sender"
        // Grupos (WhatsApp e afins) quase sempre são planos de outras pessoas: fora, a não ser que a pessoa ligue.
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null ||
            GROUP_TITLE.containsMatchIn(sender)
        val isEmail = sbn.packageName in EMAIL_APPS

        // WhatsApp junta várias mensagens numa notificação ("3 novas mensagens"): lê cada uma pelo MessagingStyle.
        val recent = System.currentTimeMillis() - 15 * 60_000L
        val messages = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages
                ?.filter { it.person != null && it.timestamp >= recent }
                ?.mapNotNull { it.text?.toString()?.trim() }
                ?.takeLast(3)
        }.getOrNull().orEmpty()
        val bodies = messages.ifEmpty {
            listOfNotNull(
                (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim(),
            )
        }.filter { it.length >= 6 && !SUMMARY_TEXT.matches(it) }

        for (body in bodies) {
            // A mesma mensagem volta a cada atualização da notificação: processa uma vez só.
            if (!settings.markSeen("notif:" + (sbn.packageName + sender + body).hashCode())) continue
            scope.launch {
                val app = applicationContext
                InboxStore.add(app, InboxStore.Msg(origin, sender, body, System.currentTimeMillis()))
                // Código de rastreio (mesmo em grupo ou e-mail automático de loja): vira encomenda.
                val parcels = Parcels.scan(app, body, label)
                parcels.forEach { notifyParcel(app, it, label) }
                if (isGroup && !settings.notifGroups) {
                    NotifLog.add(app, label, body, if (parcels.isNotEmpty()) "📦 rastreio guardado" else "grupo (ignorado)")
                    return@launch
                }
                // E-mails automáticos (avisos do Google, login, propaganda, códigos) não viram sugestão.
                if (isEmail && AUTOMATED.containsMatchIn((sender + " " + body).lowercase())) {
                    NotifLog.add(app, label, body, "e-mail automático (ignorado)")
                    return@launch
                }
                var why = "sem compromisso"
                val suggestion = Brain.fromNotification(app, body, label, strict = isEmail) { why = it }
                when {
                    suggestion == null -> NotifLog.add(app, label, body, if (parcels.isNotEmpty()) "📦 rastreio guardado" else why)
                    SuggestionStore.add(app, suggestion) -> {
                        NotifLog.add(app, label, body, "📌 sugestão: ${suggestion.title}")
                        notifySuggestion(app, suggestion)
                        RefreshWorker.runNow(app)
                    }
                    else -> NotifLog.add(app, label, body, "já sugerido")
                }
            }
        }
    }

    /** O Android às vezes derruba o leitor (bateria, atualização): pede para religar. */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching { requestRebind(ComponentName(this, NotificationWatcher::class.java)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "suggestions"

        /** "3 novas mensagens", "2 mensagens de 2 conversas": resumo, não conteúdo. */
        private val SUMMARY_TEXT = Regex("^\\d+ (novas? )?(mensagens?|messages?)( de \\d+ conversas?)?$", RegexOption.IGNORE_CASE)

        /** Religa o leitor se o sistema o derrubou (chamado ao abrir o app). */
        fun rebind(context: Context) {
            if (isEnabled(context)) runCatching { requestRebind(ComponentName(context, NotificationWatcher::class.java)) }
        }

        private val GROUP_TITLE = Regex("\\(\\d+ (mensagens|messages)\\)|: ~|\\s@\\s")

        private val EMAIL_APPS = setOf("com.google.android.gm", "com.microsoft.office.outlook")

        private val AUTOMATED = Regex(
            "no-?reply|nao-?responda|não responda|noreply|notifica|newsletter|você está recebendo este e-mail|" +
                "voce esta recebendo|fazer login|login com o google|alerta de segurança|alerta de seguranca|" +
                "código de verificação|codigo de verificacao|redefinir (a )?senha|cancelar inscrição|cancelar inscricao|" +
                "unsubscribe|promoção|promocao|oferta|desconto|cupom|% off|sua fatura|seu pedido|rastreio|" +
                "compartilhou alguns dados|conta do google|novas condições|novas condicoes|termos de uso|política de privacidade|" +
                "politica de privacidade|atualização dos termos|seu extrato|fatura|boleto disponível|webinar|inscreva-se",
        )

        val WATCHED = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.messaging" to "Mensagens",
            "com.android.mms" to "Mensagens",
            "com.samsung.android.messaging" to "Mensagens",
            "com.microsoft.office.outlook" to "Outlook",
        )

        fun isEnabled(context: Context): Boolean {
            val flat = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationWatcher::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun notifyParcel(context: Context, code: String, origin: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sugestões de compromisso", NotificationManager.IMPORTANCE_DEFAULT))
            val pending = PendingIntent.getActivity(
                context, code.hashCode(),
                Intent(context, com.trevisolli.agendawidget.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val track = PendingIntent.getActivity(
                context, code.hashCode() + 1,
                com.trevisolli.agendawidget.ActionActivity.intent(context, QuickAction(QuickAction.TRACK, "Rastrear", code)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .addAction(0, "📦 Rastrear", track)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("📦 Encomenda $code")
                .setContentText("Guardei o rastreio · $origin")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(code.hashCode(), n)
        }

        fun notifySuggestion(context: Context, s: Suggestion) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sugestões de compromisso", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val intent = Intent(context, ConfirmActivity::class.java)
                .putExtra(ConfirmActivity.EXTRA_SUGGESTION_ID, s.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context,
                s.id.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Botões na própria notificação: cria ou ignora sem abrir o app.
            fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
                context, s.id.hashCode() + code,
                Intent(context, SuggestionReceiver::class.java).setAction(action).putExtra(SuggestionActions.EXTRA_ID, s.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .addAction(0, if (s.kind == Kind.TASK) "✅ Criar tarefa" else "📅 Criar", broadcast(SuggestionActions.ACTION_CREATE, 1))
                .addAction(0, "Ignorar", broadcast(SuggestionActions.ACTION_DISMISS, 2))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("📌 ${s.title}")
                .setContentText(listOfNotNull(Format.suggestionWhen(s.date, s.time), s.origin).joinToString(" · "))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(s.id.hashCode(), notification)
        }
    }
}
