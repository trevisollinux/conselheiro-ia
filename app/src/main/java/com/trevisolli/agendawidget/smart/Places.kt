package com.trevisolli.agendawidget.smart

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.WeatherSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Lugares salvos ("casa", "mercado") e lembretes ao chegar neles.
 * Usa geofences do Google Play Services: o celular avisa o app ao entrar no raio, sem GPS ligado o tempo todo.
 */
object Places {
    data class Place(val name: String, val lat: Double, val lon: Double)
    data class Reminder(val id: String = UUID.randomUUID().toString(), val place: String, val text: String)

    private const val RADIUS = 150f
    private const val CHANNEL = "places"

    private fun file(context: Context, n: String) = File(context.filesDir, n)
    fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").lowercase().trim()

    fun hasFine(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Android 10+: avisar com o app fechado exige "Permitir o tempo todo". */
    fun hasBackground(context: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun places(context: Context): List<Place> = runCatching {
        val arr = JSONArray(file(context, "places.json").readText())
        List(arr.length()) { arr.getJSONObject(it) }.map { Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon")) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun reminders(context: Context): List<Reminder> = runCatching {
        val arr = JSONArray(file(context, "place_reminders.json").readText())
        List(arr.length()) { arr.getJSONObject(it) }.map { Reminder(it.getString("id"), it.getString("place"), it.getString("text")) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun savePlaces(context: Context, list: List<Place>) = file(context, "places.json").writeText(
        JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) } }.toString(),
    )

    @Synchronized
    private fun saveReminders(context: Context, list: List<Reminder>) = file(context, "place_reminders.json").writeText(
        JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("place", it.place).put("text", it.text)) } }.toString(),
    )

    fun find(context: Context, name: String): Place? {
        val q = norm(name).removePrefix("o ").removePrefix("a ")
        return places(context).firstOrNull { norm(it.name) == q } ?: places(context).firstOrNull { norm(it.name).contains(q) || q.contains(norm(it.name)) }
    }

    /** "Salvar este lugar como mercado": usa a posição atual. */
    suspend fun saveHere(context: Context, name: String): Place? {
        val loc = WeatherSource.currentLocation(context) ?: return null
        val p = Place(name.trim().replaceFirstChar { it.uppercase() }, loc.latitude, loc.longitude)
        savePlaces(context, places(context).filterNot { norm(it.name) == norm(p.name) } + p)
        register(context)
        return p
    }

    /** Endereço ou nome de lugar público (ex.: "Carrefour da Av. Brasil") pelo Geocoder do aparelho. */
    @Suppress("DEPRECATION")
    fun geocode(context: Context, query: String): Place? = runCatching {
        Geocoder(context, Locale("pt", "BR")).getFromLocationName(query, 1)?.firstOrNull()
            ?.let { Place(query.trim().replaceFirstChar { c -> c.uppercase() }, it.latitude, it.longitude) }
    }.getOrNull()

    fun addPlace(context: Context, p: Place) {
        savePlaces(context, places(context).filterNot { norm(it.name) == norm(p.name) } + p)
        register(context)
    }

    fun removePlace(context: Context, name: String) {
        savePlaces(context, places(context).filterNot { it.name == name })
        saveReminders(context, reminders(context).filterNot { it.place == name })
        register(context)
    }

    fun addReminder(context: Context, place: Place, text: String): Reminder {
        val r = Reminder(place = place.name, text = text)
        saveReminders(context, reminders(context) + r)
        register(context)
        return r
    }

    fun removeReminder(context: Context, id: String) {
        saveReminders(context, reminders(context).filterNot { it.id == id })
        register(context)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 77, Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** (Re)registra uma geofence por lugar que tem lembrete. Chamado ao mudar a lista, ao abrir o app e ao ligar o celular. */
    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(pendingIntent(context))
        if (!hasFine(context) || !hasBackground(context)) return
        val wanted = reminders(context).map { it.place }.toSet()
        val fences = places(context).filter { it.name in wanted }.take(90).map { p ->
            Geofence.Builder()
                .setRequestId(p.name)
                .setCircularRegion(p.lat, p.lon, RADIUS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }
        if (fences.isEmpty()) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(fences)
            .build()
        runCatching { client.addGeofences(request, pendingIntent(context)) }
    }

    /** Ao chegar: avisa e apaga os lembretes daquele lugar (são de uma vez só). */
    fun arrived(context: Context, placeName: String) {
        val due = reminders(context).filter { it.place == placeName }
        if (due.isEmpty()) return
        saveReminders(context, reminders(context) - due.toSet())
        register(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Lembretes por lugar", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 78, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val body = due.joinToString("\n") { "• ${it.text}" }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("📍 Chegou: $placeName")
            .setContentText(due.first().text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(("place" + placeName).hashCode(), n)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        event.triggeringGeofences?.forEach { Places.arrived(context.applicationContext, it.requestId) }
    }
}

/** Geofences somem quando o celular reinicia: registra de novo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Places.register(context.applicationContext)
        }
    }
}
