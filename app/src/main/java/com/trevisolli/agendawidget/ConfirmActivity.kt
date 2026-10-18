package com.trevisolli.agendawidget

import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.TodoistSource
import com.trevisolli.agendawidget.smart.Brain
import com.trevisolli.agendawidget.smart.Kind
import com.trevisolli.agendawidget.smart.Suggestion
import com.trevisolli.agendawidget.smart.SuggestionStore
import com.trevisolli.agendawidget.widget.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Revisa uma sugestão antes de criar: vem do "Compartilhar" de qualquer app
 * ou de uma notificação detectada.
 */
class ConfirmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SUGGESTION_ID = "suggestion_id"
        private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storedId = intent.getStringExtra(EXTRA_SUGGESTION_ID)
        val stored = storedId?.let { SuggestionStore.get(this, it) }
        val shared = if (intent.action == Intent.ACTION_SEND) {
            listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            ).joinToString("\n").trim()
        } else {
            ""
        }
        if (stored == null && shared.isBlank()) {
            Toast.makeText(this, "Sugestão não encontrada", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { Screen(stored, shared) }
            }
        }
    }

    @Composable
    private fun Screen(stored: Suggestion?, shared: String) {
        val settings = remember { Settings(this) }
        val scope = rememberCoroutineScope()
        val initial = remember { stored ?: Brain.rules(shared, "Compartilhado") }

        var title by remember { mutableStateOf(initial.title) }
        var date by remember { mutableStateOf(initial.date?.format(DATE).orEmpty()) }
        var time by remember { mutableStateOf(initial.time?.format(TIME).orEmpty()) }
        var duration by remember { mutableStateOf(initial.durationMin.toString()) }
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }

        fun apply(s: Suggestion) {
            title = s.title
            date = s.date?.format(DATE).orEmpty()
            time = s.time?.format(TIME).orEmpty()
            duration = s.durationMin.toString()
        }

        // Texto compartilhado + IA ligada: refina o que as regras acharam.
        LaunchedEffect(Unit) {
            if (stored == null && settings.aiReady) {
                status = "Analisando com ${settings.aiProvider.label}…"
                try {
                    val ai = withContext(Dispatchers.IO) { Brain.ai(this@ConfirmActivity, shared, "Compartilhado") }
                    if (ai != null) {
                        apply(ai)
                        status = "Sugestão da ${settings.aiProvider.label}. Confira antes de salvar."
                    } else {
                        status = "A IA não achou compromisso aqui. Preencha à mão se quiser."
                    }
                } catch (e: Exception) {
                    status = "IA indisponível (${e.message?.take(80)}). Usando o modo automático."
                }
            }
        }

        fun parsedDate(): LocalDate? = runCatching { LocalDate.parse(date.trim(), DATE) }.getOrNull()
        fun parsedTime(): LocalTime? = runCatching { LocalTime.parse(time.trim().padStart(5, '0'), TIME) }.getOrNull()

        fun done(message: String) {
            stored?.let { SuggestionStore.remove(this, it.id) }
            RefreshWorker.runNow(this)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
        }

        fun addToCalendar() {
            val d = parsedDate() ?: LocalDate.now()
            val t = parsedTime()
            val zone = ZoneId.systemDefault()
            val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.Events.DESCRIPTION, (stored?.text ?: shared).take(2000))
            if (t != null) {
                val start = d.atTime(t).atZone(zone)
                val minutes = duration.toLongOrNull()?.coerceIn(5, 1440) ?: 60
                intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plusMinutes(minutes).toInstant().toEpochMilli())
            } else {
                intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, d.atStartOfDay(zone).toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            }
            startActivity(intent)
            done("Confirme no app de agenda")
        }

        fun addToTodoist() {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        TodoistSource.create(settings.todoistToken, title, parsedDate(), parsedTime(), stored?.text ?: shared)
                    }
                    done("Tarefa criada no Todoist")
                } catch (e: Exception) {
                    status = "Erro no Todoist: ${e.message?.take(120)}"
                    busy = false
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Nova sugestão", style = MaterialTheme.typography.headlineSmall)
            Text(
                "De: ${stored?.origin ?: "Compartilhado"}" + if (settings.aiReady) " · IA" else " · automático",
                style = MaterialTheme.typography.bodySmall,
            )
            Card(Modifier.fillMaxWidth()) {
                Text(
                    (stored?.text ?: shared).take(600),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            OutlinedTextField(title, { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    date, { date = it },
                    label = { Text("Data (dd/mm/aaaa)") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedTextField(
                    time, { time = it },
                    label = { Text("Hora") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                duration, { duration = it.filter(Char::isDigit) },
                label = { Text("Duração (min), se for evento") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            val prefersTask = initial.kind == Kind.TASK && settings.todoistToken.isNotBlank()
            if (prefersTask) {
                Button(onClick = { addToTodoist() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Criar tarefa no Todoist")
                }
                OutlinedButton(onClick = { addToCalendar() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Adicionar à agenda")
                }
            } else {
                Button(onClick = { addToCalendar() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Adicionar à agenda")
                }
                if (settings.todoistToken.isNotBlank()) {
                    OutlinedButton(onClick = { addToTodoist() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Criar tarefa no Todoist")
                    }
                }
            }
            TextButton(onClick = {
                stored?.let { SuggestionStore.remove(this@ConfirmActivity, it.id) }
                RefreshWorker.runNow(this@ConfirmActivity)
                finish()
            }, modifier = Modifier.fillMaxWidth()) { Text("Descartar") }
        }
    }
}
