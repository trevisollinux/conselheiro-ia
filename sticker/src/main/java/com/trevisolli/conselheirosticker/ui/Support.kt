package com.trevisolli.conselheirosticker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Chave Pix para quem quiser apoiar o app (opcional; o app continua grátis e sem anúncios). */
const val PIX_KEY = "gustavotrevisolli@gmail.com"

@Composable
fun SupportDialog(onDismiss: () -> Unit, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💜 Gostou do app?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("O Unusual Stickers é grátis e sem anúncios. Se quiser contribuir, mande qualquer valor via Pix:")
                Text(PIX_KEY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Chave Pix (e-mail). Obrigado!", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(PIX_KEY))
                onDismiss()
                onCopied()
            }) { Text("Copiar chave Pix") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
