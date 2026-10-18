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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.trevisolli.agendawidget.smart.Briefing
import com.trevisolli.agendawidget.widget.LandscapeRenderer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.Source
import com.trevisolli.agendawidget.data.TodoistSource
import java.time.LocalDate
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

/** Tons de aquarela: papel creme, verde de campo, azul de céu. */
internal val APP_COLORS = lightColorScheme(
    primary = Color(0xFF3E7C59),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E8D3),
    onPrimaryContainer = Color(0xFF1C3A28),
    secondary = Color(0xFF5E93B8),
    secondaryContainer = Color(0xFFD6E8F3),
    onSecondaryContainer = Color(0xFF1E3A4F),
    background = Color(0xFFF4EFE2),
    surface = Color(0xFFF4EFE2),
    onSurface = Color(0xFF2D3A31),
    onBackground = Color(0xFF2D3A31),
    surfaceVariant = Color(0xFFFFFBF2),
    onSurfaceVariant = Color(0xFF55615A),
    surfaceContainerHighest = Color(0xFFFFFBF2),
    surfaceContainerHigh = Color(0xFFFFFBF2),
    outline = Color(0xFFB9B29E),
    error = Color(0xFFB3261E),
)

internal val APP_TYPE = Typography().let { t ->
    t.copy(
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 30.sp),
        headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Color(0xFF3E7C59)),
    )
}

class MainActivity : ComponentActivity() {
    /** Incrementa a cada onResume para recarregar estado vindo de fora (sugestões, permissões). */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        Briefing.scheduleAll(this)
        setContent {
            MaterialTheme(colorScheme = APP_COLORS, typography = APP_TYPE) {
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
        var notionToken by remember { mutableStateOf(settings.notionToken) }
        val suggestions = remember(resumeCount, snapshot) { SuggestionStore.all(this) }
        val listenerOn = remember(resumeCount) { NotificationWatcher.isEnabled(this) }

        var tasksEnabled by remember { mutableStateOf(settings.googleTasksEnabled) }
        var tasksMessage by remember { mutableStateOf<String?>(null) }

        var artBg by remember { mutableStateOf(settings.artBackground) }
        var opacity by remember { mutableStateOf(settings.widgetOpacity) }
        var artSeed by remember { mutableStateOf(System.currentTimeMillis()) }
        var morningOn by remember { mutableStateOf(settings.briefMorningOn) }
        var morningHour by remember { mutableStateOf(settings.briefMorningHour) }
        var eveningOn by remember { mutableStateOf(settings.briefEveningOn) }
        var eveningHour by remember { mutableStateOf(settings.briefEveningHour) }
        var briefings by remember { mutableStateOf(Briefing.history(this)) }
        var briefBusy by remember { mutableStateOf(false) }
        var notifyBrief by remember { mutableStateOf(settings.briefNotify) }
        /** Tarefas concluídas nesta tela: continuam visíveis (riscadas) para poder desmarcar. */
        val doneNow = remember { mutableStateMapOf<String, AgendaItem>() }
        var toggling by remember { mutableStateOf<String?>(null) }

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
            notionToken = settings.notionToken
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
                    val switched = AiClient.redirects[provider]
                    if (switched != null && switched != settings.effectiveModel) {
                        // Guarda o modelo novo para não errar de novo.
                        settings.aiModel = switched
                        aiModel = switched
                        "✅ ${provider.label} respondeu: ${answer.take(60)}\n(o modelo antigo saiu de linha; troquei para $switched)"
                    } else {
                        "✅ ${provider.label} respondeu: ${answer.take(60)}"
                    }
                } catch (e: Exception) {
                    "❌ ${e.message?.take(200)}"
                }
                refresh()
            }
        }

        LaunchedEffect(Unit) {
            if (calendarGranted) refresh()
            // Resumos e sugestões chegam como notificação.
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        LaunchedEffect(resumeCount) { briefings = Briefing.history(this@MainActivity) }

        fun briefNow(slot: Briefing.Slot) {
            briefBusy = true
            scope.launch {
                val text = Briefing.build(this@MainActivity, slot)
                Briefing.save(this@MainActivity, Briefing.Entry(System.currentTimeMillis(), slot, text))
                if (settings.briefNotify) Briefing.notify(this@MainActivity, slot, text)
                briefings = Briefing.history(this@MainActivity)
                briefBusy = false
            }
        }

        fun toggleTask(item: AgendaItem, done: Boolean) {
            val id = item.taskId ?: return
            toggling = id
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        if (done) TodoistSource.close(settings.todoistToken, id) else TodoistSource.reopen(settings.todoistToken, id)
                    }.isSuccess
                }
                if (ok) {
                    if (done) {
                        doneNow[id] = item
                        settings.logCompletion(item.title)
                    } else {
                        doneNow.remove(id)
                    }
                }
                toggling = null
                refresh()
            }
        }

        fun saveBriefSchedule() {
            settings.briefMorningOn = morningOn
            settings.briefMorningHour = morningHour
            settings.briefEveningOn = eveningOn
            settings.briefEveningHour = eveningHour
            Briefing.scheduleAll(this@MainActivity)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Banner(artSeed, opacity = 1f)

            DayCard(
                snapshot = snapshot,
                briefings = briefings,
                doneNow = doneNow.values.toList(),
                toggling = toggling,
                busy = briefBusy,
                onPrepare = {
                    briefNow(if (java.time.LocalTime.now().hour < 15) Briefing.Slot.MORNING else Briefing.Slot.EVENING)
                },
                onToggle = { item, done -> toggleTask(item, done) },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { refresh() }, enabled = !busy) { Text(if (busy) "Atualizando…" else "Atualizar agora") }
                Spacer(Modifier.width(12.dp))
                Text("Última: ${Format.updatedAt(snapshot.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            }
            Preview(snapshot, artSeed, opacity / 100f, artBg)

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

            Section("Resumos automáticos") {
                Text(
                    "De manhã, o plano do dia; no fim do dia, o que foi feito, o que ficou e o que vem amanhã. " +
                        "Aparecem no topo do app, em \"Seu dia\"." + if (useAi) " Escritos pela sua IA." else " Modo automático.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Também avisar por notificação", modifier = Modifier.weight(1f))
                    Switch(checked = notifyBrief, onCheckedChange = {
                        notifyBrief = it
                        settings.briefNotify = it
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("☀️ Manhã", modifier = Modifier.weight(1f))
                    Switch(checked = morningOn, onCheckedChange = { morningOn = it; saveBriefSchedule() })
                }
                if (morningOn) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 7, 8, 9, 10).forEach { h ->
                            FilterChip(selected = morningHour == h, onClick = { morningHour = h; saveBriefSchedule() }, label = { Text("${h}h") })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🌙 Fim do dia", modifier = Modifier.weight(1f))
                    Switch(checked = eveningOn, onCheckedChange = { eveningOn = it; saveBriefSchedule() })
                }
                if (eveningOn) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(16, 17, 18, 19, 20, 21).forEach { h ->
                            FilterChip(selected = eveningHour == h, onClick = { eveningHour = h; saveBriefSchedule() }, label = { Text("${h}h") })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { briefNow(Briefing.Slot.MORNING) }, enabled = !briefBusy) { Text("Testar manhã") }
                    OutlinedButton(onClick = { briefNow(Briefing.Slot.EVENING) }, enabled = !briefBusy) { Text("Testar fim do dia") }
                }
                if (briefBusy) Text("Escrevendo…")
                if (briefings.isNotEmpty()) {
                    Text("Últimos resumos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    val fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    briefings.take(6).forEach { b ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${b.slot.label} · ${Instant.ofEpochMilli(b.time).atZone(ZoneId.systemDefault()).format(fmt)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(b.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Section("Visual do widget") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paisagem que muda sozinha", modifier = Modifier.weight(1f))
                    Switch(checked = artBg, onCheckedChange = {
                        artBg = it
                        settings.artBackground = it
                        refresh()
                    })
                }
                Text(
                    "Colinas, mar, bosque, campo de flores e lago, sem pessoas. A cena muda a cada atualização e o céu " +
                        "acompanha o dia: amanhecer, tarde de verão, hora dourada, entardecer e noite estrelada. " +
                        "O texto fica escuro ou claro conforme a cena.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Opacidade do fundo", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 85, 70, 55, 40).forEach { o ->
                        FilterChip(
                            selected = opacity == o,
                            onClick = {
                                opacity = o
                                settings.widgetOpacity = o
                                refresh()
                            },
                            label = { Text("$o%") },
                        )
                    }
                }
                if (artBg) {
                    OutlinedButton(onClick = { artSeed = System.nanoTime() }) { Text("Ver outra paisagem na prévia") }
                }
            }

            Section("Notion") {
                OutlinedTextField(
                    value = notionToken,
                    onValueChange = { notionToken = it },
                    label = { Text("Token da integração") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "1) notion.so/profile/integrations → Nova integração (interna) → copie o \"Internal Integration Secret\".\n" +
                        "2) Em cada página ou banco de notas que ele pode ler: ••• → Conexões → adicione a integração.\n" +
                        "A cada atualização ele olha as páginas editadas e sugere o que tiver data (ex.: \"Dentista 22/10 15h\").",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        settings.notionToken = notionToken
                        settings.notionLastScan = 0
                        refresh()
                    }) { Text("Salvar e ler agora") }
                    if (settings.notionToken.isNotBlank()) {
                        OutlinedButton(onClick = {
                            notionToken = ""
                            settings.notionToken = ""
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
                        "\"ConsigliereIA: criar compromisso\". Ele monta o evento ou a tarefa para você conferir. " +
                        "Funciona também com prints (imagens): no modo automático o celular lê o texto; com IA, ela vê a imagem.",
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

    /** O ConsigliereIA "falando" o resumo do dia, com a lista de hoje para marcar logo abaixo. */
    @Composable
    private fun DayCard(
        snapshot: Snapshot,
        briefings: List<Briefing.Entry>,
        doneNow: List<AgendaItem>,
        toggling: String?,
        busy: Boolean,
        onPrepare: () -> Unit,
        onToggle: (AgendaItem, Boolean) -> Unit,
    ) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val brief = briefings.firstOrNull { dateOf(it.time) == today }
        val doneIds = doneNow.mapNotNull { it.taskId }.toSet()
        val dayItems = snapshot.items.filter { it.overdue || dateOf(it.startMillis) == today }
        val tasks = dayItems.filter { it.source != Source.CALENDAR && it.taskId !in doneIds }
        val events = dayItems.filter { it.source == Source.CALENDAR }
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Seu dia", style = MaterialTheme.typography.titleMedium)

                // Balão de conversa
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Text("🧭") }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val who = brief?.let {
                            "ConsigliereIA · ${it.slot.label}, ${Instant.ofEpochMilli(it.time).atZone(zone).format(hhmm)}"
                        } ?: "ConsigliereIA"
                        Text(who, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                brief?.text ?: snapshot.summary.ifBlank { "Ainda não preparei o resumo de hoje." },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        TextButton(onClick = onPrepare, enabled = !busy) {
                            Text(if (busy) "Escrevendo…" else if (brief == null) "Preparar meu dia" else "Conversar de novo")
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Text("Para hoje", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                if (tasks.isEmpty() && events.isEmpty() && doneNow.isEmpty()) {
                    Text("Nada pendente para hoje. Aproveite 🍃", style = MaterialTheme.typography.bodyMedium)
                }
                events.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.size(10.dp).background(Color(e.color), CircleShape))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (e.dateOnly) "dia todo" else Instant.ofEpochMilli(e.startMillis).atZone(zone).format(hhmm),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
                tasks.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t.taskId != null) {
                            Checkbox(checked = false, enabled = toggling != t.taskId, onCheckedChange = { onToggle(t, true) })
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                        Column {
                            Text(t.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (t.overdue) "Atrasada · ${Format.subtitle(t).substringAfter("Atrasada · ")}" else t.source.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (t.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                doneNow.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, enabled = toggling != t.taskId, onCheckedChange = { onToggle(t, false) })
                        Text(
                            t.title,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (doneNow.isNotEmpty()) {
                    Text(
                        "Concluída sem querer? Desmarque que ela volta no Todoist.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun Banner(seed: Long, opacity: Float) {
        val art = remember(seed, opacity) {
            LandscapeRenderer.render(1080, 380, seed + 1, java.time.LocalTime.now().hour, opacity, 48f)
        }
        Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(20.dp))) {
            Image(art.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) {
                Text(
                    "ConsigliereIA",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (art.dark) Color(0xFFFFF8EC) else Color(0xFF1C2A24),
                )
                Text(
                    "🍃 ${art.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (art.dark) Color(0xFFE8EEF2) else Color(0xFF3D4E47),
                )
            }
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
    private fun Preview(snapshot: Snapshot, seed: Long, opacity: Float, artOn: Boolean) {
        val art = remember(seed, opacity, artOn) {
            if (artOn) LandscapeRenderer.render(900, 1200, seed, java.time.LocalTime.now().hour, opacity, 0f) else null
        }
        val dark = art?.dark ?: true
        val primary = if (dark) Color.White else Color(0xFF1C2A24)
        val secondary = if (dark) Color(0xFFD6DEE5) else Color(0xFF3D4E47)
        val overdueColor = if (dark) Color(0xFFFF9C93) else Color(0xFFB3261E)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF8E9A93))) {
            if (art != null) {
                Image(
                    art.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Box(Modifier.matchParentSize().background(Color(0xFF202124).copy(alpha = opacity)))
            }
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Prévia do widget", color = secondary)
                if (snapshot.summary.isNotBlank()) Text(snapshot.summary, color = if (dark) Color(0xFFFFE0A3) else Color(0xFF2E6B45))
                if (snapshot.items.isEmpty()) Text("Nada nos próximos dias", color = primary)
                snapshot.items.take(30).forEach { item ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Color(item.color), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(item.title, color = primary, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Text(
                            Format.subtitle(item),
                            color = if (item.overdue) overdueColor else secondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 18.dp),
                        )
                    }
                }
                snapshot.errors.forEach { Text(it, color = overdueColor) }
                art?.let { Text("🍃 ${it.label}", color = secondary, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
