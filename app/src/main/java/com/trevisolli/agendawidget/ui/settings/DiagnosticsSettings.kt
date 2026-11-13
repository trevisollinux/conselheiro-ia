package com.trevisolli.agendawidget.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.smart.AiUsage
import com.trevisolli.agendawidget.smart.FailLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Uso da IA (com custo estimado) e o que falhou em segundo plano. [refreshKey]: muda ao voltar para a tela. */
@Composable
internal fun DiagnosticsSettings(refreshKey: Int) {
    val context = LocalContext.current
    var cleared by remember { mutableIntStateOf(0) }
    var showFails by remember { mutableStateOf(false) }

    SettingsSection("Uso da IA") {
        val today = remember(refreshKey) { AiUsage.today() }
        val month = remember(refreshKey) { AiUsage.last30() }
        Text("Hoje: ${AiUsage.line(today)}")
        Text("Últimos 30 dias: ${AiUsage.line(month)}")
        Hint("Custo estimado pela tabela de preços aproximada de cada modelo. A conta real é a do painel do provedor (ex.: Google AI Studio).")
    }

    SettingsSection("O que falhou") {
        val fails = remember(refreshKey, cleared) { FailLog.all(context) }
        val recent = remember(refreshKey, cleared) { FailLog.recentCount(context) }
        Text(
            when {
                fails.isEmpty() -> "✅ Nenhuma falha registrada."
                recent == 0 -> "✅ Nada nas últimas 24 h (${fails.size} mais antigas)."
                else -> "⚠️ $recent falha${if (recent == 1) "" else "s"} nas últimas 24 h."
            },
        )
        if (fails.isNotEmpty()) {
            TextButton(onClick = { showFails = !showFails }) { Text(if (showFails) "Ocultar" else "Ver detalhes") }
        }
        if (showFails) {
            val fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            fails.forEach { e ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()).format(fmt) + " · " + e.where,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(e.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = {
                FailLog.clear(context)
                cleared++
                showFails = false
            }) { Text("Limpar registro") }
        }
    }
}
