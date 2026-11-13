package com.trevisolli.agendawidget.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.BuildConfig
import com.trevisolli.agendawidget.Support

@Composable
internal fun BatterySettings() {
    val context = LocalContext.current
    SettingsSection("Bateria") {
        Text("Deixe o app \"sem restrições\" para o widget atualizar sozinho.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }) { Text("Abrir configurações de bateria") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutSettings() {
    val context = LocalContext.current
    SettingsSection("Sobre") {
        Text("Consigliere ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.PRIVACY_URL)))
            }) { Text("Privacidade") }
            OutlinedButton(onClick = { Support.contact(context, "Consigliere: contato", "") }) { Text("Fale conosco") }
        }
    }
}
