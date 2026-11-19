package com.trevisolli.agendawidget.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.smart.AiClient
import com.trevisolli.agendawidget.smart.AiProvider
import com.trevisolli.agendawidget.smart.GeminiVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GeminiVoiceSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = Settings(context)
    val key = settings.keyFor(AiProvider.GEMINI)
    val configured = settings.useAi && settings.aiProvider == AiProvider.GEMINI && key.isNotBlank()
    var voice by remember(key) { mutableStateOf(GeminiVoice.voiceId(context, key)) }
    var enabled by remember { mutableStateOf(GeminiVoice.enabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    SettingsSection("Voz feminina do Gemini") {
        Text("Uma voz feminina em português brasileiro. O Gemini cria a voz na sua conta e sintetiza cada resposta com a sua chave. Isso pode ter custo.", style = MaterialTheme.typography.bodySmall)
        if (voice.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usar voz feminina do Gemini", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    GeminiVoice.setEnabled(context, it)
                    enabled = it
                })
            }
        }
        OutlinedButton(enabled = configured && !busy, onClick = {
            busy = true
            status = if (voice.isBlank()) "Criando a voz…" else "Preparando a prévia…"
            scope.launch {
                try {
                    val sample = if (voice.isBlank()) {
                        val design = GeminiVoice.create(context, key)
                        voice = design.id
                        enabled = true
                        design.sample
                    } else null
                    val wav = sample ?: GeminiVoice.synthesize(key, voice, "Olá. Eu sou a Consigliere. Em que posso ajudar hoje?")
                    status = if (GeminiVoice.play(wav)) null else "Não consegui tocar a prévia."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = AiClient.friendly(e).take(160)
                } finally {
                    busy = false
                }
            }
        }) { Text(if (voice.isBlank()) "Criar e ouvir prévia" else "Ouvir prévia") }
        if (!configured) Text("Ative o Gemini em Inteligência para criar ou ouvir esta voz.", style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
