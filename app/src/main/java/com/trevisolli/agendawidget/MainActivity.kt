package com.trevisolli.agendawidget

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.CalendarSource
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.GoogleTasksSource
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.Snapshot
import com.trevisolli.agendawidget.widget.AgendaWidget
import com.trevisolli.agendawidget.widget.RefreshWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { SettingsScreen() }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val settings = remember { Settings(this) }
        val scope = rememberCoroutineScope()
        var snapshot by remember { mutableStateOf(AgendaRepository.load(this)) }
        var busy by remember { mutableStateOf(false) }
        var calendarGranted by remember { mutableStateOf(CalendarSource.hasPermission(this)) }
        var token by remember { mutableStateOf(settings.todoistToken) }
        var tasksEnabled by remember { mutableStateOf(settings.googleTasksEnabled) }
        var tasksMessage by remember { mutableStateOf<String?>(null) }
        var days by remember { mutableStateOf(settings.days) }

        fun refresh() {
            busy = true
            scope.launch {
                val s = AgendaRepository.refresh(this@MainActivity)
                AgendaWidget.push(this@MainActivity, s)
                snapshot = s
                busy = false
            }
        }

        val calendarPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            calendarGranted = granted
            if (granted) refresh()
        }

        val googleConsent = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            val data = result.data
            if (data == null) {
                tasksMessage = "Autorização cancelada."
                return@rememberLauncherForActivityResult
            }
            try {
                val auth = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
                if (auth.accessToken != null) {
                    settings.googleTasksEnabled = true
                    tasksEnabled = true
                    tasksMessage = null
                    refresh()
                }
            } catch (e: Exception) {
                tasksMessage = "Não autorizado: ${e.message}"
            }
        }

        fun connectGoogleTasks() {
            scope.launch {
                try {
                    val result = Identity.getAuthorizationClient(this@MainActivity)
                        .authorize(GoogleTasksSource.authRequest).await()
                    val pending = result.pendingIntent
                    if (result.hasResolution() && pending != null) {
                        googleConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    } else {
                        settings.googleTasksEnabled = true
                        tasksEnabled = true
                        tasksMessage = null
                        refresh()
                    }
                } catch (e: Exception) {
                    tasksMessage = "Falhou: ${e.message}. Confira o cliente OAuth no Google Cloud (veja o README)."
                }
            }
        }

        LaunchedEffect(Unit) { if (calendarGranted) refresh() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Agenda Widget", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Configure as fontes e depois adicione o widget \"Agenda e tarefas\" na tela inicial.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Section("Agenda do celular") {
                if (calendarGranted) {
                    Text("Conectada (todas as agendas visíveis, inclusive Google).")
                } else {
                    Button(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text("Permitir acesso à agenda")
                    }
                }
            }

            Section("Todoist") {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token da API") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "No Todoist: Configurações → Integrações → Desenvolvedor → Token da API.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        settings.todoistToken = token
                        refresh()
                    }) { Text("Salvar") }
                    if (settings.todoistToken.isNotBlank()) {
                        OutlinedButton(onClick = {
                            token = ""
                            settings.todoistToken = ""
                            refresh()
                        }) { Text("Remover") }
                    }
                }
            }

            Section("Google Tasks") {
                if (tasksEnabled) {
                    Text("Conectado.")
                    OutlinedButton(onClick = {
                        settings.googleTasksEnabled = false
                        tasksEnabled = false
                        refresh()
                    }) { Text("Desconectar") }
                } else {
                    Button(onClick = { connectGoogleTasks() }) { Text("Conectar conta Google") }
                }
                tasksMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            Section("Período") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 7, 14, 30).forEach { d ->
                        FilterChip(
                            selected = days == d,
                            onClick = {
                                days = d
                                settings.days = d
                                refresh()
                            },
                            label = { Text("$d dias") },
                        )
                    }
                }
            }

            Section("Bateria") {
                Text(
                    "Em celulares vivo, libere o app em \"sem restrições\" para o widget continuar atualizando.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Abrir configurações de bateria") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { refresh() }, enabled = !busy) {
                    Text(if (busy) "Atualizando…" else "Atualizar agora")
                }
                Spacer(Modifier.width(12.dp))
                Text("Última: ${Format.updatedAt(snapshot.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            }

            Preview(snapshot)
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun Preview(snapshot: Snapshot) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.background(Color(0xFF202124)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Prévia", color = Color(0xFFBDC1C6))
                if (snapshot.items.isEmpty()) Text("Nada nos próximos dias", color = Color.White)
                snapshot.items.take(30).forEach { item ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Color(item.color), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Text(
                            Format.subtitle(item),
                            color = if (item.overdue) Color(0xFFF28B82) else Color(0xFFBDC1C6),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 18.dp),
                        )
                    }
                }
                snapshot.errors.forEach { Text(it, color = Color(0xFFF28B82)) }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
