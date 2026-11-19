package com.trevisolli.agendawidget.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
internal fun AiModelPicker(
    label: String,
    value: String,
    suggested: String?,
    models: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    Text(label)
    if (models.isEmpty() || manual) {
        OutlinedTextField(
            value = value,
            onValueChange = onSelected,
            label = { Text("Nome do modelo") },
            placeholder = { Text(suggested ?: "Automático") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (models.isNotEmpty()) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(value.ifBlank { suggested?.let { "Sugerido: $it" } ?: "Automático" } + "  ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(suggested?.let { "Automático · $it" } ?: "Automático") }, onClick = {
                    manual = false
                    onSelected("")
                    expanded = false
                })
                models.forEach { model ->
                    DropdownMenuItem(text = { Text(model) }, onClick = {
                        manual = false
                        onSelected(model)
                        expanded = false
                    })
                }
                DropdownMenuItem(text = { Text("Digitar outro modelo") }, onClick = {
                    manual = true
                    expanded = false
                })
            }
        }
    }
}
