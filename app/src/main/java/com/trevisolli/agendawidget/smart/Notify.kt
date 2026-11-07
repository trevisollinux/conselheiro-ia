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
import com.trevisolli.agendawidget.ActionActivity
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R

/** Notificação simples do app, com botões de um toque opcionais. */
object Notify {
    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(
        context: Context, channel: String, channelName: String, id: Int, title: String, text: String,
        actions: List<QuickAction> = emptyList(),
    ) {
        if (!allowed(context)) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        actions.take(3).forEachIndexed { i, a ->
            val pi = PendingIntent.getActivity(
                context, id * 7 + i + 1, ActionActivity.intent(context, a),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, a.label, pi)
        }
        NotificationManagerCompat.from(context).notify(id, b.build())
    }
}
