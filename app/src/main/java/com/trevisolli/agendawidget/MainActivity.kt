package com.trevisolli.agendawidget

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.trevisolli.agendawidget.ui.isDarkTheme
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.trevisolli.agendawidget.data.CalendarWriter
import com.trevisolli.agendawidget.data.Range
import com.trevisolli.agendawidget.data.WidgetTheme
import com.trevisolli.agendawidget.data.AppTheme
import com.trevisolli.agendawidget.data.ArtStyle
import com.trevisolli.agendawidget.data.WeatherSource
import com.trevisolli.agendawidget.widget.Backgrounds
import com.trevisolli.agendawidget.widget.LandscapeArt
import com.trevisolli.agendawidget.ui.ConsigliereTheme
import com.trevisolli.agendawidget.ui.AgendaTab
import com.trevisolli.agendawidget.ui.ChatPanel
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

class MainActivity : ComponentActivity() {
    /** Incrementa a cada onResume para recarregar estado vindo de fora (sugestões, permissões). */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 desenha atrás da barra de status; o Scaffold cuida dos espaços.
        val dark = isDarkTheme(Settings(this).appTheme, (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        RefreshWorker.schedule(this)
        Briefing.scheduleAll(this)
        setContent {
            ConsigliereTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        val settings = remember { Settings(this) }
        val scope = rememberCoroutineScope()
        var snapshot by remember { mutableStateOf(AgendaRepository.load(this)) }
        var busy by remember { mutableStateOf(false) }
        var calendarGranted by remember { mutableStateOf(CalendarSource.hasPermission(this)) }
        var token by remember { mutableStateOf(settings.todoistToken) }

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

        var theme by remember { mutableStateOf(settings.widgetTheme) }
        var scene by remember { mutableStateOf(settings.sceneChoice) }
        var artStyle by remember { mutableStateOf(settings.artStyle) }
        var rotate by remember { mutableStateOf(settings.rotateMinutes) }
        var cityInput by remember { mutableStateOf(settings.city) }
        var cityStatus by remember { mutableStateOf<String?>(WeatherSource.load(this)?.label) }
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
        var tab by remember { mutableStateOf(0) }
        var inSettings by remember { mutableStateOf(false) }
        BackHandler(enabled = inSettings) { inSettings = false }
        var userName by remember { mutableStateOf(settings.userName) }
        var range by remember { mutableStateOf(settings.widgetRange) }
        var targetCal by remember { mutableStateOf(settings.targetCalendarId) }
        var canWriteCalendar by remember { mutableStateOf(CalendarWriter.canWrite(this)) }
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
            useAi = settings.useAi
            provider = settings.aiProvider
            aiKey = settings.aiKey
            aiModel = settings.aiModel
            detect = settings.detectNotifications
            notionToken = settings.notionToken
            userName = settings.userName
        }

        val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            calendarGranted = granted
            if (granted) refresh()
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            canWriteCalendar = granted
        }

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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (inSettings) "Configurações" else "ConsigliereIA",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    navigationIcon = {
                        if (inSettings) {
                            IconButton(onClick = { inSettings = false }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                            }
                        }
                    },
                    actions = {
                        if (!inSettings) {
                            IconButton(onClick = {
                                // ↻ também troca a arte do widget.
                                settings.artFetchedAt = 0
                                settings.sceneNonce = settings.sceneNonce + 1
                                refresh()
                            }, enabled = !busy) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Atualizar")
                            }
                            IconButton(onClick = { inSettings = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Configurações")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                if (!inSettings) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Hoje") },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.DateRange, contentDescription = null) }, label = { Text("Agenda") },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Face, contentDescription = null) }, label = { Text("Conversa") },
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize()) {
                when {
                    inSettings -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
            Section("Você") {
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text("Como devo te chamar?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    settings.userName = userName
                    refresh()
                }) { Text("Salvar") }
            }

            Section("Tempo") {
                Text(
                    "A paisagem acompanha o tempo e o resumo avisa de chuva nos seus compromissos.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = cityInput,
                    onValueChange = { cityInput = it },
                    label = { Text("Sua cidade (ou use a localização)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (!granted) {
                        cityStatus = "Sem permissão de localização. Digite a cidade."
                        return@rememberLauncherForActivityResult
                    }
                    cityStatus = "Localizando…"
                    scope.launch {
                        cityStatus = try {
                            val place = WeatherSource.useDeviceLocation(this@MainActivity)
                            if (place == null) {
                                "Não consegui a localização agora. Ligue a localização ou digite a cidade."
                            } else {
                                withContext(Dispatchers.IO) { WeatherSource.refresh(this@MainActivity) }
                                cityInput = place
                                "📍 $place · " + (WeatherSource.load(this@MainActivity)?.label ?: "")
                            }
                        } catch (e: Exception) {
                            "Erro: ${e.message?.take(80)}"
                        }
                        refresh()
                    }
                }
                OutlinedButton(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                    Text("📍 Usar minha localização")
                }
                Button(onClick = {
                    cityStatus = "Procurando…"
                    scope.launch {
                        cityStatus = try {
                            val found = withContext(Dispatchers.IO) {
                                WeatherSource.setCity(this@MainActivity, cityInput)?.also { WeatherSource.refresh(this@MainActivity) }
                            }
                            if (found == null) {
                                if (cityInput.isBlank()) "Tempo desligado." else "Cidade não encontrada."
                            } else {
                                cityInput = found
                                "✅ $found · " + (WeatherSource.load(this@MainActivity)?.label ?: "")
                            }
                        } catch (e: Exception) {
                            "Erro: ${e.message?.take(80)}"
                        }
                        refresh()
                    }
                }) { Text("Salvar cidade") }
                cityStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            Section("Agenda do celular") {
                if (calendarGranted) {
                    Text("Conectada (todas as agendas visíveis, inclusive Google).")
                } else {
                    Button(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text("Permitir acesso à agenda")
                    }
                }
                Text("Criar eventos pela conversa", style = MaterialTheme.typography.labelLarge)
                if (!canWriteCalendar) {
                    Text(
                        "Para eu criar eventos direto (\"dentista quinta 15h\"), libere a escrita na agenda.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { writePermission.launch(Manifest.permission.WRITE_CALENDAR) }) {
                        Text("Permitir criar eventos")
                    }
                } else {
                    val calendars = remember(canWriteCalendar, resumeCount) { CalendarWriter.writableCalendars(this@MainActivity) }
                    val current = calendars.firstOrNull { it.id == targetCal } ?: calendars.firstOrNull()
                    Text("Novos eventos vão para:", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        calendars.forEach { c ->
                            FilterChip(
                                selected = current?.id == c.id,
                                onClick = {
                                    targetCal = c.id
                                    settings.targetCalendarId = c.id
                                },
                                label = { Text(c.name.ifBlank { c.account }, maxLines = 1) },
                            )
                        }
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
                    "Plano do dia de manhã e balanço no fim do dia, no topo da aba Hoje.",
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
                    briefings.take(3).forEach { b ->
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

            Section("Tema do app") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { t ->
                        FilterChip(
                            selected = settings.appTheme == t,
                            onClick = {
                                settings.appTheme = t
                                // Widget acompanha o tema: redesenha já.
                                RefreshWorker.runNow(this@MainActivity)
                                recreate()
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }

            Section("Visual do widget") {
                Text("Fundo", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetTheme.entries.forEach { t ->
                        FilterChip(
                            selected = theme == t,
                            onClick = {
                                theme = t
                                settings.widgetTheme = t
                                refresh()
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
                if (theme == WidgetTheme.ART) {
                    Text("Estilo", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArtStyle.entries.forEach { st ->
                            FilterChip(
                                selected = artStyle == st,
                                onClick = {
                                    artStyle = st
                                    settings.artStyle = st
                                    settings.artFetchedAt = 0
                                    refresh()
                                },
                                label = { Text(st.label) },
                            )
                        }
                    }
                    TextButton(onClick = {
                        settings.artFetchedAt = 0
                        refresh()
                    }) { Text("Trocar obra agora") }
                    Text(
                        "Destaques do Met Museum (Nova York), em domínio público." +
                            settings.artCaption.takeIf { it.isNotBlank() }?.let { "\nAgora: $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    settings.artError.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (theme == WidgetTheme.LANDSCAPE || theme == WidgetTheme.ART) {
                    Text("Trocar a cada", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30 to "30 min", 60 to "1 h", 180 to "3 h", 360 to "6 h", 1440 to "1 dia").forEach { (m, label) ->
                            FilterChip(
                                selected = rotate == m,
                                onClick = {
                                    rotate = m
                                    settings.rotateMinutes = m
                                    refresh()
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                if (theme == WidgetTheme.LANDSCAPE) {
                    Text("Cena", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = scene.isEmpty(),
                            onClick = {
                                scene = ""
                                settings.sceneChoice = ""
                                refresh()
                            },
                            label = { Text("Muda sozinha") },
                        )
                        LandscapeArt.Scene.entries.forEach { sc ->
                            FilterChip(
                                selected = scene == sc.name,
                                onClick = {
                                    scene = sc.name
                                    settings.sceneChoice = sc.name
                                    refresh()
                                },
                                label = { Text(sc.label) },
                            )
                        }
                    }
                }
                Text("Opacidade", style = MaterialTheme.typography.labelLarge)
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
                Preview(snapshot, artSeed, theme, scene, opacity)
                if (theme == WidgetTheme.LANDSCAPE && scene.isEmpty()) {
                    TextButton(onClick = { artSeed = System.nanoTime() }) { Text("Ver outra paisagem") }
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
                        "Usa a sua chave; o uso é cobrado na sua conta da ${provider.label}.",
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
                    "Mensagens com data e hora viram sugestões 📌 para você confirmar.",
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
                    "Em qualquer app, toque em Compartilhar → ConsigliereIA para virar evento ou tarefa. Vale para texto e print.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Levar para outro aparelho") {
                Text(
                    "Leve suas configurações por QR. Ele contém suas chaves: não mostre a ninguém.",
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
                    "Deixe o app \"sem restrições\" para o widget atualizar sozinho.",
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

                    tab == 0 -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
            Banner(artSeed, opacity = 1f)

            // Atalho visível para as Configurações enquanto falta permissão importante.
            val missing = listOfNotNull(
                "ler a agenda".takeIf { !calendarGranted },
                "criar eventos".takeIf { !canWriteCalendar },
                "Todoist".takeIf { settings.todoistToken.isBlank() },
            )
            if (missing.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { inSettings = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⚙️", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Falta configurar", fontWeight = FontWeight.Bold)
                            Text(missing.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Abrir ›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }

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
            OutlinedButton(onClick = { inSettings = true }, modifier = Modifier.fillMaxWidth()) {
                Text("⚙️  Configurações")
            }
            Text(
                "Atualizado às ${Format.updatedAt(snapshot.updatedAt)}" + if (busy) " · atualizando…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
                    }

                    tab == 1 -> AgendaTab(
                        snapshot = snapshot,
                        range = range,
                        onRange = {
                            range = it
                            settings.widgetRange = it
                            refresh()
                        },
                        doneNow = doneNow.values.toList(),
                        toggling = toggling,
                        onToggle = { item, done -> toggleTask(item, done) },
                    )

                    else -> ChatPanel(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        onChanged = { snapshot = AgendaRepository.load(this@MainActivity) },
                    )
                }
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
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                content()
            }
        }
    }

    /** Amostra compacta do widget com o fundo escolhido. */
    @Composable
    private fun Preview(snapshot: Snapshot, seed: Long, theme: WidgetTheme, scene: String, opacity: Int) {
        val art = remember(seed, theme, scene, opacity, snapshot.updatedAt) {
            Backgrounds.render(this, 900, 560, java.time.LocalTime.now().hour, 0f, seed)
        }
        val primary = if (art.dark) Color.White else Color(0xFF1C2A24)
        val secondary = if (art.dark) Color(0xFFD6DEE5) else Color(0xFF3D4E47)
        val accent = if (art.dark) Color(0xFFFFE0A3) else Color(0xFF2E6B45)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF8E9A93))) {
            Image(
                art.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (java.time.LocalTime.now().hour < 12) "Bom dia" else "Boa tarde",
                    color = primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                )
                if (snapshot.summary.isNotBlank()) {
                    Text(snapshot.summary, color = accent, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                snapshot.items.take(3).forEach { item ->
                    Column {
                        Text(item.title, color = primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(Format.shortSubtitle(item), color = secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}
