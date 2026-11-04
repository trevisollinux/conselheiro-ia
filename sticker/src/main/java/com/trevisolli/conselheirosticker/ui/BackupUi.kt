package com.trevisolli.conselheirosticker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.trevisolli.conselheirosticker.data.Backup
import com.trevisolli.conselheirosticker.data.PackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Botões de backup: exportar (escolhendo onde salvar) e importar um .zip. */
class BackupActions(
    val export: (packIds: List<String>, fileName: String) -> Unit,
    val import: () -> Unit,
    val busy: () -> Boolean,
)

@Composable
fun rememberBackupActions(
    store: PackStore,
    snackbar: SnackbarHostState,
    onImported: () -> Unit = {},
): BackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember { Backup(context, store) }
    var pendingIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var working by remember { mutableStateOf(false) }

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val ids = pendingIds
        if (uri == null || ids.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { backup.export(ids, it) }
                        ?: error("Não consegui abrir o destino")
                }
            }
            working = false
            say(
                ok.fold(
                    { if (ids.size == 1) "Pacote exportado ✅" else "${ids.size} pacotes exportados ✅" },
                    { "Não consegui exportar: ${it.message}" },
                )
            )
        }
    }

    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { backup.import(it) }
                        ?: error("Não consegui abrir o arquivo")
                }
            }
            working = false
            result.fold(
                { r ->
                    onImported()
                    say(
                        when {
                            r.packs.isEmpty() -> "Nenhum pacote encontrado no arquivo"
                            r.packs.size == 1 -> "Pacote \"${r.packs[0].name}\" importado ✅"
                            else -> "${r.packs.size} pacotes importados ✅"
                        } + if (r.skipped > 0) " (${r.skipped} com problema)" else ""
                    )
                },
                { say("Não consegui importar: ${it.message}") },
            )
        }
    }

    return BackupActions(
        export = { ids, name ->
            if (ids.isEmpty()) say("Nenhum pacote para exportar")
            else { pendingIds = ids; saver.launch(name) }
        },
        import = { opener.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        busy = { working },
    )
}

fun backupFileName(base: String): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val clean = base.filter { it.isLetterOrDigit() || it in " -_" }.trim().replace(' ', '-').ifBlank { "pacote" }
    return "$clean-$day.zip"
}
