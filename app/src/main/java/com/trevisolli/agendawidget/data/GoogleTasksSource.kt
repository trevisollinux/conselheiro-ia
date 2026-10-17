package com.trevisolli.agendawidget.data

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/** Google Tasks via API REST. O token vem do Google Play Services (sem guardar senha). */
object GoogleTasksSource {
    private const val COLOR = 0xFF4285F4.toInt()
    private val SCOPE = Scope("https://www.googleapis.com/auth/tasks.readonly")

    val authRequest: AuthorizationRequest
        get() = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Token sem interação; null se o usuário ainda precisa autorizar no app. */
    suspend fun silentToken(context: Context): String? {
        val result = Identity.getAuthorizationClient(context).authorize(authRequest).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    suspend fun fetch(context: Context, days: Int): List<AgendaItem> {
        val token = silentToken(context)
            ?: throw IllegalStateException("abra o app e conecte o Google Tasks de novo")
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val lastDay = today.plusDays(days.toLong())
        // O Google Tasks guarda só a data, como meia-noite UTC.
        val dueMax = lastDay.plusDays(1).toString() + "T00:00:00Z"

        val items = mutableListOf<AgendaItem>()
        val lists = Http.getJson("https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=100", token)
            .optJSONArray("items")
        for (l in 0 until (lists?.length() ?: 0)) {
            val listId = lists!!.getJSONObject(l).getString("id")
            var pageToken: String? = null
            do {
                var url = "https://tasks.googleapis.com/tasks/v1/lists/" + URLEncoder.encode(listId, "UTF-8") +
                    "/tasks?showCompleted=false&showHidden=false&maxResults=100&dueMax=" +
                    URLEncoder.encode(dueMax, "UTF-8")
                if (pageToken != null) url += "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
                val page = Http.getJson(url, token)
                val tasks = page.optJSONArray("items")
                for (t in 0 until (tasks?.length() ?: 0)) {
                    val task = tasks!!.getJSONObject(t)
                    val title = task.optString("title").trim()
                    val due = task.optString("due")
                    if (title.isEmpty() || due.length < 10) continue
                    val date = LocalDate.parse(due.substring(0, 10))
                    if (date.isAfter(lastDay)) continue
                    items += AgendaItem(
                        title = title,
                        source = Source.GOOGLE_TASKS,
                        startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                        endMillis = null,
                        dateOnly = true,
                        overdue = date.isBefore(today),
                        color = COLOR,
                        openUri = task.optString("webViewLink").ifBlank { null },
                    )
                }
                pageToken = page.optString("nextPageToken").ifBlank { null }
            } while (pageToken != null)
        }
        return items
    }
}
