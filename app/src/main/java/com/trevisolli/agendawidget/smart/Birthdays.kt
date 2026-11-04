package com.trevisolli.agendawidget.smart

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trevisolli.agendawidget.ActionActivity
import com.trevisolli.agendawidget.R
import java.time.LocalDate
import java.time.LocalTime

/** Aniversários dos contatos: aviso de manhã com a mensagem de parabéns pronta. */
object Birthdays {
    data class Person(val name: String, val age: Int?)

    private const val CHANNEL = "birthdays"

    fun on(context: Context, date: LocalDate = LocalDate.now()): List<Person> {
        if (!QuickActions.hasContacts(context)) return emptyList()
        val out = mutableListOf<Person>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0).orEmpty()
                val raw = c.getString(1).orEmpty().trim()
                // Formatos: "1990-03-15", "--03-15" (sem ano), às vezes "1990-03-15T00:00:00".
                val m = Regex("^(\\d{4}|-)-?(\\d{2})-(\\d{2})").find(raw) ?: continue
                val month = m.groupValues[2].toIntOrNull() ?: continue
                val day = m.groupValues[3].toIntOrNull() ?: continue
                if (month != date.monthValue || day != date.dayOfMonth) continue
                val year = m.groupValues[1].toIntOrNull()
                out += Person(name, year?.let { date.year - it }?.takeIf { it in 1..120 })
            }
        }
        return out.distinctBy { it.name }
    }

    fun greeting(p: Person): String = "Parabéns, ${p.name.substringBefore(' ')}! 🎉 Muitas felicidades, saúde e alegria. Um abraço!"

    /** Uma vez por dia, a partir das 8h. */
    fun notifyToday(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("bday_notified", null) == today || LocalTime.now().hour < 8) return
        prefs.edit().putString("bday_notified", today).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Aniversários", NotificationManager.IMPORTANCE_DEFAULT))
        on(context).forEach { p ->
            val action = QuickAction(QuickAction.WHATSAPP, "🎉 Mandar parabéns", p.name, greeting(p))
            val pending = PendingIntent.getActivity(
                context, ("bday" + p.name).hashCode(), ActionActivity.intent(context, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("🎂 Hoje é aniversário de ${p.name}" + (p.age?.let { " ($it anos)" } ?: ""))
                .setContentText("Toque para mandar os parabéns pelo WhatsApp")
                .setContentIntent(pending)
                .addAction(0, "🎉 Mandar parabéns", pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(("bday" + p.name).hashCode(), n)
        }
    }
}
