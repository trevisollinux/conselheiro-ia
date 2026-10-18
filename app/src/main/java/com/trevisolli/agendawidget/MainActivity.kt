package com.trevisolli.agendawidget

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.CalendarSource
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.GoogleTasksSource
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.Snapshot
import com.trevisolli.agendawidget.smart.AiClient
import com.trevisolli.agendawidget.smart.AiProvider
import com.trevisolli.agendawidget.smart.NotificationWatcher
import com.trevisolli.agendawidget.smart.SuggestionStore
import com.trevisolli.agendawidget.widget.AgendaWidget
import com.trevisolli.agendawidget.widget.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Incrementa a cada onResume para recarregar estado vindo de fora (sugestões, permissões). */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { SettingsScreen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SettingsScreen() {
        val settings = remember { Settings(this) }
        val scope = rememberCoroutineScope()
        var snapshot by remember { mutableStateOf(AgendaRepository.load(this)) }
        var busy by remember { mutableStateOf(false) }
        var calendarGranted by remember { mutableStateOf(CalendarSource.hasPermission(this)) }
        var token by remember { mutableStateOf(settings.todoistToken) }
        var days by remember { mutableStateOf(settings.days) }

        var useAi by remember { mutableStateOf(settings.useAi) }
        var provider by remember { mutableStateOf(settings.aiProvider) }
        var aiKey by remember { mutableStateOf(settings.aiKey) }
        var aiModel by remember { mutableStateOf(settings.aiModel) }
        var aiTest by remember { mutableStateOf<String?>(null) }

        var detect by remember { mutableStateOf(settings.detectNotifications) }
        val suggestions = remember(resumeCount, snapshot) { SuggestionStore.all(this) }
        val listenerOn = remember(resumeCount) { NotificationWatcher.isEnabled(this) }

        var tasksEnabled by remember { mutableStateOf(settings.googleTasksEnabled) }
        var tasksMessage by remember { mutableStateOf<String?>(null) }

        var showQr by remember { mutableStateOf<Bitmap?>(null) }
        var backupMessage by remember { mutableStateOf<String?>(null) }

        fun refresh() {
            busy = true
            scope.launch {
                val s = AgendaRepository.refresh(this@MainActivity)
                AgendaWidget.push(this@MainActivity, s)
                snapshot = s
                busy = false
            }
        }

        fun reloadFromSettings() {
            token = settings.todoistToken
            days = settings.days
            useAi = settings.useAi
            provider = settings.aiProvider
            aiKey = settings.aiKey
            aiModel = settings.aiModel
            detect = settings.detectNotifications
        }

        val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            calendarGranted = granted
            if (granted) refresh()
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
            val contents = result.contents ?: return@rememberLauncherForActivityResult
            if (settings.importPayload(contents)) {
                reloadFromSettings()
                backupMessage = "Configurações importadas."
                refresh()
            } else {
                backupMessage = "Esse QR não é do ConsigliereIA."
            }
        }

        val googleConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
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
                tasksMessage = googleError(e)
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
                    tasksMessage = googleError(e)
                }
            }
        }

        fun saveAiAndTest() {
            settings.useAi = useAi
            settings.aiProvider = provider
            settings.aiKey = aiKey
            settings.aiModel = aiModel
            settings.summaryCacheKey = ""
            if (!useAi) {
                aiTest = null
                refresh()
                return
            }
            if (aiKey.isBlank()) {
                aiTest = "Cole a chave da API primeiro."
                return
            }
            aiTest = "Testando ${provider.label}…"
            scope.launch {
                aiTest = try {
                    val answer = withContext(Dispatchers.IO) {
                        AiClient.complete(provider, aiKey.trim(), settings.effectiveModel, "Responda em português.", "Diga apenas: funcionando")
                    }
                    "✅ ${provider.label} respondeu: ${answer.take(60)}"
                } catch (e: Exception) {
                    "❌ ${e.message?.take(200)}"
                }
                refresh()
            }
        }

        LaunchedEffect(Unit) { if (calendarGranted) refresh() }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("ConsigliereIA", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Configure as fontes e adicione o widget \"ConsigliereIA\" na tela inicial.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { refresh() }, enabled = !busy) { Text(if (busy) "Atualizando…" else "Atualizar agora") }
                Spacer(Modifier.width(12.dp))
                Text("Última: ${Format.updatedAt(snapshot.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            }
            Preview(snapshot)

            if (suggestions.isNotEmpty()) {
                Section("📌 Sugestões para revisar") {
                    suggestions.forEach { s ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                startActivity(
                                    Intent(this@MainActivity, ConfirmActivity::class.java)
                                        .putExtra(ConfirmActivity.EXTRA_SUGGESTION_ID, s.id),
                                )
                            }.padding(vertical = 6.dp),
                        ) {
                            Text(s.title, fontWeight = FontWeight.Bold)
                            Text(
                                listOfNotNull(Format.suggestionWhen(s.date, s.time), s.origin).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

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
                    "Pegue em app.todoist.com/app/settings/integrations/developer",
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

            Section("Inteligência") {
                Text(
                    "Usada no resumo do dia e para entender mensagens compartilhadas ou recebidas.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useAi, onClick = { useAi = false }, label = { Text("Automático (sem IA)") })
                    FilterChip(selected = useAi, onClick = { useAi = true }, label = { Text("Minha IA") })
                }
                if (!useAi) {
                    Text(
                        "Regras no próprio celular: entende \"amanhã às 9h\", \"sexta 14:30\", \"dia 21/10\"… Grátis e privado.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiProvider.entries.forEach { p ->
                            FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p.label) })
                        }
                    }
                    OutlinedTextField(
                        value = aiKey,
                        onValueChange = { aiKey = it },
                        label = { Text("Chave da API (${provider.label})") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Onde pegar: ${provider.keyHint}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = aiModel,
                        onValueChange = { aiModel = it },
                        label = { Text("Modelo (opcional)") },
                        placeholder = { Text(provider.defaultModel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "O texto vai direto do celular para a ${provider.label}, com a sua chave. O custo é cobrado na sua conta dela.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { saveAiAndTest() }) { Text(if (useAi) "Salvar e testar" else "Salvar") }
                aiTest?.let { Text(it) }
            }

            Section("Ler mensagens (WhatsApp, Gmail…)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Detectar compromissos nas notificações", modifier = Modifier.weight(1f))
                    Switch(checked = detect, onCheckedChange = {
                        detect = it
                        settings.detectNotifications = it
                        if (it && Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    })
                }
                Text(
                    "Quando chega uma mensagem com data/hora (ex.: \"consulta quinta às 9h\"), aparece uma sugestão para " +
                        "você confirmar. Nada é criado sozinho. Apps: WhatsApp, Telegram, Gmail, Outlook e SMS." +
                        if (useAi) " Com \"Minha IA\" ligada, só as mensagens que parecem compromisso são enviadas para a IA." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (detect && !listenerOn) {
                    Text("Falta liberar o acesso às notificações:", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = {
                        startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Abrir acesso a notificações") }
                } else if (detect) {
                    Text("✅ Acesso liberado.")
                }
            }

            Section("Compartilhar com o ConsigliereIA") {
                Text(
                    "Em qualquer app (WhatsApp, e-mail, notas, navegador), selecione um texto e toque em Compartilhar → " +
                        "\"ConsigliereIA: criar compromisso\". Ele monta o evento ou a tarefa para você conferir.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Período") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Section("Levar para outro aparelho") {
                Text(
                    "Mostre o QR aqui e leia no outro aparelho em Importar. O QR contém seu token e sua chave de IA: " +
                        "não mostre a mais ninguém.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showQr = qrBitmap(settings.exportPayload()) }) { Text("Exportar (QR)") }
                    OutlinedButton(onClick = {
                        scanQr.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Aponte para o QR do ConsigliereIA")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    }) { Text("Importar") }
                }
                backupMessage?.let { Text(it) }
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

            Section("Google Tasks (experimental)") {
                if (tasksEnabled) {
                    Text("Conectado.")
                    OutlinedButton(onClick = {
                        settings.googleTasksEnabled = false
                        tasksEnabled = false
                        refresh()
                    }) { Text("Desconectar") }
                } else {
                    Text("Precisa de configuração no Google Cloud (veja o README).", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { connectGoogleTasks() }) { Text("Conectar conta Google") }
                }
                tasksMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        showQr?.let { bmp ->
            AlertDialog(
                onDismissRequest = { showQr = null },
                confirmButton = { TextButton(onClick = { showQr = null }) { Text("Fechar") } },
                title = { Text("Leia no outro aparelho") },
                text = {
                    Box(Modifier.fillMaxWidth().background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
                        Image(bmp.asImageBitmap(), contentDescription = "QR de configuração", modifier = Modifier.size(260.dp))
                    }
                },
            )
        }
    }

    private fun qrBitmap(text: String, size: Int = 640): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun googleError(e: Exception): String {
        val msg = e.message.orEmpty()
        return if ("UNREGISTERED_ON_API_CONSOLE" in msg) {
            "App ainda não registrado no Google Cloud. Crie o ID do cliente OAuth Android " +
                "(pacote com.trevisolli.agendawidget + SHA-1 do README) e tente de novo em alguns minutos."
        } else {
            "Não autorizado: $msg"
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
                Text("Prévia do widget", color = Color(0xFFBDC1C6))
                if (snapshot.summary.isNotBlank()) Text(snapshot.summary, color = Color(0xFF8AB4F8))
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
