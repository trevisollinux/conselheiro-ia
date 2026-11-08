package com.trevisolli.conselheirosticker.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trevisolli.conselheirosticker.data.AutoBackup
import java.util.Date

/** Liga/desliga o backup automático semanal e mostra quando foi o último. */
@Composable
fun AutoBackupDialog(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { AutoBackup.enable(ctx, uri) }
            .onSuccess { onMessage("Backup automático ligado. O primeiro está sendo feito agora.") }
            .onFailure { onMessage("Essa pasta não aceita gravação. Escolha outra (ex.: Documentos).") }
        refresh++
    }
    @Suppress("UNUSED_VARIABLE") val r = refresh
    val folder = AutoBackup.folderName(ctx)
    val last = AutoBackup.lastRun(ctx)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup automático") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (folder == null) {
                        "Uma vez por semana o app salva todos os pacotes num .zip numa pasta do celular que você " +
                            "escolher. Ele continua lá mesmo se você desinstalar o app (é só usar Importar backup)."
                    } else {
                        "Ligado: toda semana em \"$folder\" (guarda os 3 últimos)."
                    }
                )
                if (folder != null) {
                    Text(
                        when {
                            AutoBackup.failed(ctx) -> "⚠️ O último backup falhou. Escolha a pasta de novo."
                            last > 0 -> "Último backup: " + DateFormat.getMediumDateFormat(ctx).format(Date(last)) +
                                " " + DateFormat.getTimeFormat(ctx).format(Date(last))
                            else -> "O primeiro backup ainda vai ser feito."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Dica: escolha uma pasta como Documentos. O Google Drive não deixa apps gravarem sozinhos; " +
                        "para ele, use Exportar.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { picker.launch(null) }) {
                    Text(if (folder == null) "Escolher pasta e ligar" else "Trocar pasta")
                }
                if (folder != null) {
                    OutlinedButton(onClick = {
                        AutoBackup.runNow(ctx)
                        onMessage("Fazendo backup agora…")
                    }) { Text("Fazer backup agora") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            if (folder != null) {
                TextButton(onClick = {
                    AutoBackup.disable(ctx)
                    refresh++
                    onMessage("Backup automático desligado")
                }) { Text("Desligar") }
            }
        },
    )
}
