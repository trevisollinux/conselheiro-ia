package com.trevisolli.agendawidget.ui

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.Source
import com.trevisolli.agendawidget.data.TodoistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Detalhe de um item: quando, subtarefas do Todoist (marcáveis) e atalho para o app de origem. */
@Composable
fun ItemDetail(item: AgendaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val token = remember { Settings(context).todoistToken }
    var subtasks by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val done = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(item.taskId) {
        val id = item.taskId ?: return@LaunchedEffect
        subtasks = withContext(Dispatchers.IO) {
            runCatching { TodoistSource.subtasks(token, id) }.onFailure { error = "Não consegui carregar as subtarefas." }.getOrNull()
        } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOf(item.source.label, Format.subtitle(item)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.taskId != null) {
                    val list = subtasks
                    when {
                        list == null -> CircularProgressIndicator(Modifier.heightIn(max = 28.dp))
                        list.isEmpty() -> Text(error ?: "Sem subtarefas.", style = MaterialTheme.typography.bodySmall)
                        else -> list.forEach { (id, text) ->
                            val checked = done[id] == true
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = { now ->
                                    done[id] = now
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { if (now) TodoistSource.close(token, id) else TodoistSource.reopen(token, id) }
                                            .onFailure { done[id] = !now }
                                    }
                                })
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                                    color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            item.openUri?.let { uri ->
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (item.source == Source.CALENDAR) {
                        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.startMillis)
                        item.endMillis?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                    }
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }) { Text(if (item.source == Source.CALENDAR) "Abrir na agenda" else "Abrir no Todoist") }
            }
        },
    )
}
