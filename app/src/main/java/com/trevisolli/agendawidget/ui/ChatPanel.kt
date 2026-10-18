package com.trevisolli.agendawidget.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.smart.Assistant
import com.trevisolli.agendawidget.smart.ChatMessage
import com.trevisolli.agendawidget.smart.ChatStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EXAMPLES = listOf(
    "O que tenho hoje?",
    "O que tenho amanhã?",
    "Dentista quinta às 15h na agenda",
    "Lembrar de pagar conta amanhã",
)

/** Conversa com o ConsigliereIA, por texto ou voz. Usada na aba Conversa e na janela aberta pelo widget. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(modifier: Modifier = Modifier, autoVoice: Boolean = false, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(ChatStore.all(context)) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        input = ""
        busy = true
        messages = messages + ChatMessage(fromUser = true, text = t)
        scope.launch {
            Assistant.handle(context, t)
            messages = ChatStore.all(context)
            busy = false
            onChanged()
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send(it) }
    }

    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o ConsigliereIA")
        try {
            voice.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Reconhecimento de voz indisponível neste aparelho", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { if (autoVoice) listen() }
    LaunchedEffect(messages.size, busy) {
        val count = messages.size + if (busy) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Bubble(
                            ChatMessage(
                                fromUser = false,
                                text = "Oi! Me diga o que anotar e eu coloco na agenda ou no Todoist. Pode escrever, tocar no 🎤 " +
                                    "ou usar uma das dicas aqui embaixo.",
                            ),
                            onUndo = null,
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { m ->
                Bubble(
                    m,
                    onUndo = if (!m.fromUser && m.undo.isNotEmpty() && !m.undone) {
                        {
                            scope.launch {
                                Assistant.undo(context, m)
                                messages = ChatStore.all(context)
                                onChanged()
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            if (busy) item { Bubble(ChatMessage(fromUser = false, text = "…"), onUndo = null) }
        }

        // Dicas sempre à mão, algumas a partir da sua lista ("Terminei Pagar mei").
        val tips = remember(messages.size) {
            val items = AgendaRepository.load(context).items
            val zone = ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val firstTask = items.firstOrNull {
                it.taskId != null && (it.overdue || Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == today)
            }
            listOfNotNull(firstTask?.let { "Terminei ${it.title}" }) + EXAMPLES
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items(tips) { tip ->
                Text(
                    tip,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(enabled = !busy) { send(tip) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Fale com o Consigliere…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
            )
            Spacer(Modifier.width(6.dp))
            if (input.isBlank()) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { listen() },
                    contentAlignment = Alignment.Center,
                ) { Text("🎤", style = MaterialTheme.typography.titleLarge) }
            } else {
                IconButton(onClick = { send(input) }, enabled = !busy) {
                    Icon(Icons.Filled.Send, contentDescription = "Enviar", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onUndo: (() -> Unit)?) {
    val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        if (m.fromUser) {
                            RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
                        } else {
                            RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
                        },
                    )
                    .background(if (m.fromUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    m.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (m.fromUser) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Instant.ofEpochMilli(m.time).atZone(ZoneId.systemDefault()).format(hhmm) + if (m.undone) " · desfeito" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onUndo != null) TextButton(onClick = onUndo) { Text("Desfazer") }
            }
        }
    }
}
