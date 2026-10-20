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
        if (!Settings(this).detectNotifications) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (body.length < 6) return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        // Grupos (WhatsApp e afins) quase sempre são planos de outras pessoas: fora, a não ser que a pessoa ligue.
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null ||
            GROUP_TITLE.containsMatchIn(sender)
        if (isGroup && !Settings(this).notifGroups) return
        val label = if (sender.isBlank()) origin else "$origin · $sender"
        val isEmail = sbn.packageName in EMAIL_APPS

        scope.launch {
            InboxStore.add(applicationContext, InboxStore.Msg(origin, sender, body, System.currentTimeMillis()))
            // E-mails automáticos (avisos do Google, login, propaganda, códigos) não viram sugestão.
            if (isEmail && AUTOMATED.containsMatchIn((sender + " " + body).lowercase())) return@launch
            val suggestion = Brain.fromNotification(applicationContext, body, label, strict = isEmail) ?: return@launch
            if (SuggestionStore.add(applicationContext, suggestion)) {
                notifySuggestion(applicationContext, suggestion)
                RefreshWorker.runNow(applicationContext)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "suggestions"

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
            val notification = NotificationCompat.Builder(context, CHANNEL)
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
