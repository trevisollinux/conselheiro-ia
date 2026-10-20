package com.trevisolli.agendawidget.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.smart.AiProvider
import com.trevisolli.agendawidget.widget.AgendaWidgetReceiver

/** Ícone do app desenhado em Compose (o adaptativo não roda em painterResource). */
@Composable
fun AppLogo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF9A3346), Color(0xFF3E0F1B)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(size * 1.5f))
    }
}

/** Primeiro uso: apresenta o app e guia permissões, Todoist, IA e o widget. Tudo pode ser pulado. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var step by remember { mutableIntStateOf(0) }
    val last = 4

    var calendarOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    val calendarPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        calendarOk = r[Manifest.permission.READ_CALENDAR] == true
        if (calendarOk) step++
    }
    var name by remember { mutableStateOf(settings.userName) }
    var todoist by remember { mutableStateOf(settings.todoistToken) }
    var aiKey by remember { mutableStateOf(settings.aiKey) }

    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun next() {
        when (step) {
            0 -> settings.userName = name
            2 -> settings.todoistToken = todoist
            3 -> if (aiKey.isNotBlank()) {
                settings.aiProvider = AiProvider.GEMINI
                settings.aiKey = aiKey
                settings.useAi = true
            }
        }
        if (step == last) onDone() else step++
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Progresso em bolinhas + pular
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..last).forEach { i ->
                    Box(
                        Modifier.height(6.dp).width(if (i == step) 22.dp else 6.dp).clip(CircleShape)
                            .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    )
                }
            }
            if (step < last) TextButton(onClick = onDone) { Text("Pular tudo") }
        }

        AnimatedContent(targetState = step, label = "passo", modifier = Modifier.weight(1f)) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (s) {
                    0 -> {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.mascot), contentDescription = null, modifier = Modifier.size(150.dp))
                        }
                        Text(
                            "Seu conselheiro pessoal",
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Agenda e tarefas juntas num widget, com um resumo do que importa e um assistente que anota por você.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Feature("📅", "Agenda + Todoist", "Tudo em uma lista, com o dia de hoje em destaque.")
                        Feature("✨", "Resumo do dia", "Prioridades, atrasadas e até o guarda-chuva quando vai chover.")
                        Feature("🎤", "Fale e pronto", "\"Dentista quinta às 15h\" vira compromisso na hora.")
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Como podemos te chamar? (opcional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    1 -> {
                        StepTitle("📅", "Sua agenda", "Mostramos seus compromissos e criamos os que você pedir. Tudo fica no celular.")
                        if (calendarOk) {
                            Text("✅ Acesso liberado", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Button(
                                onClick = { calendarPerms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Permitir acesso à agenda") }
                        }
                    }
                    2 -> {
                        StepTitle("✅", "Tarefas do Todoist", "Opcional. Suas tarefas aparecem junto da agenda e dá para concluir pelo widget.")
                        OutlinedTextField(
                            value = todoist, onValueChange = { todoist = it.trim() },
                            label = { Text("Token da API do Todoist") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = { open("https://app.todoist.com/app/settings/integrations/developer") }) {
                            Text("Onde pego o token?")
                        }
                        Text(
                            "Todoist → Configurações → Integrações → Desenvolvedor → copie o token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    3 -> {
                        StepTitle("✨", "Assistente", "Sem configurar nada, o modo automático já entende frases como \"reunião amanhã às 10h\".")
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Quer mais inteligência? (grátis)", fontWeight = FontWeight.Bold)
                                Text(
                                    "Com uma chave gratuita do Google Gemini, o resumo fica mais esperto e você pode mandar áudio e prints.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = { open("https://aistudio.google.com/app/apikey") }) { Text("Pegar chave grátis") }
                                OutlinedTextField(
                                    value = aiKey, onValueChange = { aiKey = it.trim() },
                                    label = { Text("Cole a chave aqui (opcional)") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    else -> {
                        StepTitle("🏠", "Coloque na tela inicial", "O Consigliere brilha no widget: o dia inteiro sem abrir app nenhum.")
                        Button(onClick = {
                            val mgr = AppWidgetManager.getInstance(context)
                            if (mgr.isRequestPinAppWidgetSupported) {
                                mgr.requestPinAppWidget(ComponentName(context, AgendaWidgetReceiver::class.java), null, null)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Segure num espaço vazio da tela inicial → Widgets → Consigliere",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Adicionar widget \"Meu dia\"") }
                        Text(
                            "Também há a \"Conversa rápida\": uma barra para escrever ou falar direto da tela inicial.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Voltar") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { next() }) {
                Text(
                    when {
                        step == 0 -> "Começar"
                        step == last -> "Concluir"
                        step == 1 && !calendarOk -> "Agora não"
                        step == 2 && todoist.isBlank() -> "Pular"
                        step == 3 && aiKey.isBlank() -> "Usar o automático"
                        else -> "Continuar"
                    },
                )
            }
        }
    }
}

@Composable
private fun StepTitle(emoji: String, title: String, text: String) {
    Text(emoji, style = MaterialTheme.typography.displaySmall)
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Feature(emoji: String, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
