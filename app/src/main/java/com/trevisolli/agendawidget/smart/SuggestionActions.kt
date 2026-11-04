package com.trevisolli.agendawidget.smart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.trevisolli.agendawidget.data.CalendarWriter
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.TodoistSource
import com.trevisolli.agendawidget.widget.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Criar ou ignorar uma sugestão direto pela notificação, sem abrir o app. */
object SuggestionActions {
    const val ACTION_CREATE = "com.trevisolli.agendawidget.SUGGESTION_CREATE"
    const val ACTION_DISMISS = "com.trevisolli.agendawidget.SUGGESTION_DISMISS"
    const val EXTRA_ID = "id"

    /** Evento vai para a agenda (com hora) ; tarefa vai para o Todoist, se conectado. Devolve a mensagem para mostrar. */
    fun accept(context: Context, s: Suggestion): String {
        val settings = Settings(context)
        val asTask = s.kind == Kind.TASK && settings.todoistToken.isNotBlank()
        return if (asTask) {
            TodoistSource.create(settings.todoistToken, s.title, s.date, s.time, s.text)
            "✅ Tarefa criada: ${s.title}"
        } else {
            val id = CalendarWriter.insert(context, s.title, s.date ?: LocalDate.now(), s.time, s.durationMin, s.text)
                ?: return "Libere \"Criar eventos\" nas Configurações para criar pela notificação"
            if (id > 0) "📅 Na agenda: ${s.title}" else "Não deu para criar"
        }
    }
}

class SuggestionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(SuggestionActions.EXTRA_ID) ?: return
        val app = context.applicationContext
        NotificationManagerCompat.from(app).cancel(id.hashCode())
        val pending = goAsync()
        scope.launch {
            try {
                val s = SuggestionStore.get(app, id) ?: return@launch
                val msg = if (intent.action == SuggestionActions.ACTION_CREATE) {
                    runCatching { SuggestionActions.accept(app, s) }.getOrElse { "Erro: ${it.message?.take(60)}" }
                } else {
                    "Sugestão ignorada"
                }
                SuggestionStore.remove(app, id)
                RefreshWorker.runNow(app)
                withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
            } finally {
                pending.finish()
            }
        }
    }
}
