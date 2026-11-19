package com.trevisolli.agendawidget.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    SettingsSection("Carioca e atrevida") {
        Text("Voz feminina com sotaque carioca natural e um tom brincalhão. O Gemini cria a voz na sua conta e sintetiza cada resposta com a sua chave. Isso pode ter custo.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = configured && !busy, onClick = {
            busy = true
            status = if (voice.isBlank()) "Criando a voz…" else "Preparando a prévia…"
            scope.launch {
                try {
                    val sample = if (voice.isBlank()) {
                        val design = GeminiVoice.create(context, key)
                        voice = design.id
                        design.sample
                    } else null
                    val wav = sample ?: GeminiVoice.synthesize(key, voice, "Oi, eu sou a Consigliere. Manda aí, o que a gente resolve hoje?")
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
