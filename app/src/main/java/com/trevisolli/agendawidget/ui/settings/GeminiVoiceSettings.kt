package com.trevisolli.agendawidget.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
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
internal fun GeminiVoiceSettings(aiVersion: Int = 0) {
    val context = LocalContext.current
    // [aiVersion] muda ao salvar a IA (ou voltar à tela): relê chave e provedor sem precisar sair das Configurações.
    val settings = remember(aiVersion) { Settings(context) }
    val apiKey = settings.keyFor(AiProvider.GEMINI)
    val configured = settings.useAi && settings.aiProvider == AiProvider.GEMINI && apiKey.isNotBlank()
    key(apiKey, configured) {
        VoicePicker(apiKey, configured)
    }
}

@Composable
private fun VoicePicker(apiKey: String, configured: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeVoice by remember { mutableStateOf(GeminiVoice.voiceId(context, apiKey)) }
    var description by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<GeminiVoice.Design?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun preview(create: Boolean, generate: suspend () -> ByteArray) {
        busy = true
        status = if (create) "Criando a voz…" else "Preparando a prévia…"
        scope.launch {
            try {
                status = if (GeminiVoice.play(generate())) null else "Não consegui tocar a prévia."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = AiClient.friendly(e).take(160)
            } finally {
                busy = false
            }
        }
    }

    suspend fun sample(id: String) =
        GeminiVoice.synthesize(apiKey, id, "Olá, eu sou o Consigliere. Em que posso ajudar hoje?")

    SettingsSection("Voz personalizada do Gemini") {
        Text("O Gemini cria a voz na sua conta. Gerar a prévia e sintetizar respostas pode ter custo.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(GeminiVoice.MAX_DESCRIPTION) },
            label = { Text("Descreva a voz") },
            placeholder = { Text("Ex.: homem com sotaque paulista, falando de forma descontraída") },
            modifier = Modifier.fillMaxWidth(),
            enabled = configured && !busy,
            minLines = 2,
            maxLines = 4,
        )
        OutlinedButton(enabled = configured && !busy && description.isNotBlank(), onClick = {
            val prompt = description.trim()
            preview(create = true) {
                val design = GeminiVoice.create(apiKey, prompt)
                candidate = design
                design.sample ?: sample(design.id)
            }
        }) { Text("Criar e ouvir prévia") }

        candidate?.let { design ->
            OutlinedButton(enabled = configured && !busy, onClick = {
                preview(create = false) { design.sample ?: sample(design.id) }
            }) { Text("Ouvir prévia novamente") }
            OutlinedButton(enabled = configured && !busy, onClick = {
                GeminiVoice.select(context, apiKey, design.id)
                activeVoice = design.id
                candidate = null
                status = "Voz selecionada."
            }) { Text("Usar esta voz") }
        }
        if (activeVoice.isNotBlank()) {
            OutlinedButton(enabled = configured && !busy, onClick = {
                val id = activeVoice
                preview(create = false) { sample(id) }
            }) { Text("Ouvir voz em uso") }
        }
        if (!configured) Text("Ative o Gemini em Inteligência para criar ou ouvir esta voz.", style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
