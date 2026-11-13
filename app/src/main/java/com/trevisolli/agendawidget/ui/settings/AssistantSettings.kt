package com.trevisolli.agendawidget.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.trevisolli.agendawidget.smart.ChatMessage
import com.trevisolli.agendawidget.smart.ChatStore
import com.trevisolli.agendawidget.smart.Memory
import com.trevisolli.agendawidget.smart.Weekly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fatos guardados ("lembra que…"), com ✕ para apagar. */
@Composable
internal fun MemorySettings(refreshKey: Int) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val facts = remember(refreshKey, version) { Memory.all(context) }
    SettingsSection("Memória") {
        Text(
            "Diga \"lembra que a senha do Wi-Fi da mãe é…\" e depois pergunte \"qual a senha do Wi-Fi da mãe?\". Fica só no celular.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (facts.isEmpty()) Text("Nada guardado ainda.", style = MaterialTheme.typography.bodySmall)
        facts.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 ${f.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    Memory.remove(context, f.id)
                    version++
                }) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Revisão da semana aos domingos 19h. [onReady]: aviso de que ela está na conversa. */
@Composable
internal fun WeeklySettings(onReady: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(prefs.getBoolean("weekly_on", true)) }
    SettingsSection("Revisão da semana") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Domingo às 19h, resumo da semana que vem", modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = {
                on = it
                prefs.edit().putBoolean("weekly_on", it).apply()
            })
        }
        TextButton(onClick = {
            scope.launch {
                val text = withContext(Dispatchers.IO) { Weekly.build(context) }
                ChatStore.add(context, ChatMessage(fromUser = false, text = "🗓️ Sua semana:\n$text"))
                onReady("Revisão pronta na aba Conversa")
            }
        }) { Text("Ver agora") }
    }
}
