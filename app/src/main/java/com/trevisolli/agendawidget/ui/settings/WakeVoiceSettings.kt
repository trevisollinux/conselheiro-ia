package com.trevisolli.agendawidget.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.smart.AiProvider
import com.trevisolli.agendawidget.smart.WakeModel
import com.trevisolli.agendawidget.smart.WakeVoiceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@Composable
internal fun WakeVoiceSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val status by WakeVoiceService.status.collectAsState()
    val settings = Settings(context)
    val geminiReady = settings.useAi && settings.aiProvider == AiProvider.GEMINI && settings.aiKey.isNotBlank()
    val active = WakeVoiceService.active(status)
    var installed by remember { mutableStateOf(WakeModel.installed(context)) }
    var preparing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun enable() {
        preparing = true
        message = null
        scope.launch {
            try {
                if (!installed) {
                    runInterruptible(Dispatchers.IO) { WakeModel.install(context.applicationContext) }
                    installed = true
                }
                // Depois da permissão, dá ao Android um turno para reativar a janela.
                kotlinx.coroutines.yield()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    WakeVoiceService.start(context)
                } else {
                    message = "Modelo pronto. Volte a esta tela e toque em Ativar."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Não consegui iniciar a escuta"
            } finally {
                preparing = false
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (WakeVoiceService.hasMicrophone(context)) enable() else message = "Permita o microfone para ativar."
    }

    SettingsSection("Ativação por voz") {
        Text(
            "Diga “Consigliere” com a tela ligada ou apagada. Depois do bip, faça a pergunta.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "A palavra é detectada no aparelho. Depois da ativação, a pergunta vai ao Gemini. " +
                "O microfone fica ativo até você desligar pela notificação ou pelo app e usa bateria.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Com o celular bloqueado, o Consigliere responde perguntas gerais. Desbloqueie para acessar dados pessoais ou executar ações.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(if (preparing) "Baixando e preparando o modelo…" else status)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!geminiReady && !active) Text("Ative o Gemini em Inteligência para usar este modo.", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                if (active) {
                    WakeVoiceService.stop(context)
                } else {
                    val requested = buildList {
                        if (!WakeVoiceService.hasMicrophone(context)) add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33 && !com.trevisolli.agendawidget.smart.Notify.allowed(context)) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (requested.isEmpty()) enable() else permissions.launch(requested.toTypedArray())
                }
            },
            enabled = !preparing && (active || geminiReady),
        ) {
            Text(when {
                active -> "Desativar escuta"
                installed -> "Ativar escuta"
                else -> "Baixar modelo (48 MB) e ativar"
            })
        }
    }
}
