package com.trevisolli.agendawidget.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.smart.MailSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** E-mail pelo IMAP com senha de app. [onChanged]: atualizar agenda/widget depois de ler a caixa. */
@Composable
internal fun MailSettings(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(MailSource.address(context)) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    SettingsSection("E-mail") {
        Text(
            "Lê a caixa de entrada inteira (não só as notificações) para achar compromissos, contas e rastreios. " +
                "Use uma \"senha de app\": conta Google → Segurança → Verificação em duas etapas → Senhas de app.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (MailSource.configured(context)) {
            Text("Lendo ${MailSource.address(context)}")
            Text(MailSource.statusLine(context), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    status = "Verificando…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            runCatching { MailSource.poll(context, force = true); "✅ Caixa verificada" }
                                .getOrElse { "❌ ${it.message?.take(100)}" }
                        }
                        onChanged()
                    }
                }) { Text("Verificar agora") }
                OutlinedButton(onClick = {
                    MailSource.clear(context)
                    address = ""
                    status = null
                }) { Text("Desconectar") }
            }
        } else {
            OutlinedTextField(
                value = address, onValueChange = { address = it.trim() },
                label = { Text("E-mail (Gmail, Outlook…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Senha de app (16 letras)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords")))
            }) { Text("Criar senha de app (Google)") }
            Button(onClick = {
                status = "Testando…"
                scope.launch {
                    val err = withContext(Dispatchers.IO) { MailSource.test(address, password) }
                    if (err == null) {
                        MailSource.save(context, address, password)
                        password = ""
                        status = "✅ Conectado. Vou olhar os e-mails a cada atualização."
                        withContext(Dispatchers.IO) { runCatching { MailSource.poll(context, force = true) } }
                        onChanged()
                    } else {
                        status = "❌ $err"
                    }
                }
            }, enabled = address.contains("@") && password.length >= 8) { Text("Conectar") }
        }
        status?.let { Text(it) }
    }
}
