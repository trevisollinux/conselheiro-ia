package com.trevisolli.conselheirosticker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trevisolli.conselheirosticker.data.Emojis
import com.trevisolli.conselheirosticker.data.Pack
import com.trevisolli.conselheirosticker.data.PackStore
import com.trevisolli.conselheirosticker.whatsapp.WhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Brand = Color(0xFF6C3CE9)

@Composable
fun StickerTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFB9A2FF), secondary = Color(0xFFFFD23F), background = Color(0xFF1B1530),
            surface = Color(0xFF1B1530))
    } else {
        lightColorScheme(primary = Brand, secondary = Color(0xFFFFB300))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Imagem de arquivo carregada fora da thread principal. */
@Composable
private fun fileBitmap(file: File, key: Any): Bitmap? {
    val bmp by produceState<Bitmap?>(null, file.path, key) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }
    }
    return bmp
}

private const val PREFS = "sticker"

private fun lastPublisher(ctx: Context) =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("publisher", "") ?: ""

private fun savePublisher(ctx: Context, v: String) =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("publisher", v).apply()

// ---------------------------------------------------------------- Lista de pacotes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackListScreen(
    store: PackStore,
    refreshKey: Int,
    pendingPhoto: Boolean,
    onOpen: (Pack) -> Unit,
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val packs by produceState(emptyList<Pack>(), refreshKey, version) {
        value = withContext(Dispatchers.IO) { store.list() }
    }
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var supporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val backup = rememberBackupActions(store, snackbar, onImported = { version++ })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unusual Stickers", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Exportar todos os pacotes") },
                            onClick = {
                                menu = false
                                backup.export(packs.map { it.id }, backupFileName("unusual-stickers"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Importar backup (.zip)") },
                            onClick = { menu = false; backup.import() },
                        )
                        DropdownMenuItem(
                            text = { Text("💜 Apoiar o app (Pix)") },
                            onClick = { menu = false; supporting = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Novo pacote") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pendingPhoto) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Escolha (ou crie) o pacote para a foto que você compartilhou.",
                            Modifier.padding(16.dp),
                        )
                    }
                }
            }
            if (backup.busy()) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (packs.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎨", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("Nenhum pacote ainda", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Crie um pacote, adicione de 3 a 30 figurinhas e mande para o WhatsApp. Sem anúncios, sem cobrança.\n\nTem um backup? Menu ⋮ → Importar backup.",
                            Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(packs, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(p) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val tray = fileBitmap(File(store.dir(p.id), p.trayFile), p.version)
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (tray != null) Image(tray.asImageBitmap(), contentDescription = null, Modifier.size(48.dp))
                            else Text("＋")
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${p.stickers.size} figurinha(s) · ${p.publisher}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { supporting = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("💜 Gostou do app? Mande qualquer valor via Pix")
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (supporting) {
        SupportDialog(
            onDismiss = { supporting = false },
            onCopied = { scope.launch { snackbar.showSnackbar("Chave Pix copiada: $PIX_KEY") } },
        )
    }

    if (creating) {
        PackNameDialog(
            title = "Novo pacote",
            initialName = "",
            initialPublisher = lastPublisher(context),
            onDismiss = { creating = false },
            onConfirm = { name, publisher ->
                savePublisher(context, publisher)
                val pack = store.create(name, publisher)
                creating = false
                version++
                onOpen(pack)
            },
        )
    }
}

@Composable
private fun PackNameDialog(
    title: String,
    initialName: String,
    initialPublisher: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var publisher by remember { mutableStateOf(initialPublisher) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(128) }, label = { Text("Nome do pacote") }, singleLine = true)
                OutlinedTextField(publisher, { publisher = it.take(128) }, label = { Text("Autor") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, publisher) }, enabled = name.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- Um pacote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(
    store: PackStore,
    packId: String,
    refreshKey: Int,
    onBack: () -> Unit,
    onAddPhoto: () -> Unit,
    onAddText: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var version by remember { mutableStateOf(0) }
    val pack by produceState<Pack?>(null, packId, refreshKey, version) {
        value = withContext(Dispatchers.IO) { store.get(packId) }
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var emojiFor by remember { mutableStateOf<String?>(null) }
    val backup = rememberBackupActions(store, snackbar)

    val whatsapp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        WhatsApp.describeResult(r.resultCode, r.data)?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    }

    fun changed() { version++ }

    val p = pack
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
                actions = {
                    IconButton(onClick = { p?.let { backup.export(listOf(it.id), backupFileName(it.name)) } }) {
                        Icon(Icons.Filled.Share, contentDescription = "Exportar pacote (.zip)")
                    }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Renomear") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, contentDescription = "Apagar pacote") }
                },
            )
        },
        floatingActionButton = {
            if (p != null && p.stickers.size < Pack.MAX_STICKERS) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Figurinha") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (p == null) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val missing = Pack.MIN_STICKERS - p.stickers.size
                Button(
                    onClick = {
                        val apps = WhatsApp.installed(context)
                        if (apps.isEmpty()) {
                            scope.launch { snackbar.showSnackbar("WhatsApp não encontrado neste celular") }
                        } else {
                            // Com WhatsApp e WhatsApp Business, o Android pergunta qual usar.
                            val target = if (apps.size == 1) apps.first() else null
                            runCatching { whatsapp.launch(WhatsApp.addPackIntent(context, p, target)) }
                                .onFailure { scope.launch { snackbar.showSnackbar("Não consegui abrir o WhatsApp") } }
                        }
                    },
                    enabled = p.readyForWhatsApp,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Adicionar ao WhatsApp") }
                Text(
                    if (missing > 0) "Faltam $missing figurinha(s) para o mínimo do WhatsApp (3)."
                    else "${p.stickers.size}/30 figurinhas. Depois de editar, toque de novo para atualizar no WhatsApp.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(p.stickers, key = { it.file }) { s ->
                    val bmp = fileBitmap(store.stickerFile(p.id, s.file), s.file)
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { menuFor = s.file },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = s.emojis.joinToString(" "),
                            Modifier.fillMaxSize().padding(4.dp))
                        Text(s.emojis.firstOrNull().orEmpty(), Modifier.align(Alignment.BottomEnd).padding(4.dp))
                    }
                }
            }
        }
    }

    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = false; onAddPhoto() }, Modifier.fillMaxWidth()) { Text("🖼️  Foto da galeria") }
                Button(onClick = { adding = false; onAddText() }, Modifier.fillMaxWidth()) { Text("🔤  Só texto") }
            }
        }
    }

    menuFor?.let { file ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { menuFor = null; onEdit(file) }, Modifier.fillMaxWidth()) { Text("Editar") }
                Button(onClick = { menuFor = null; emojiFor = file }, Modifier.fillMaxWidth()) { Text("Emojis") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { store.move(packId, file, -1); changed() }) { Text("◀ Mover") }
                    TextButton(onClick = { store.move(packId, file, 1); changed() }) { Text("Mover ▶") }
                }
                TextButton(onClick = {
                    store.removeSticker(packId, file); menuFor = null; changed()
                }) { Text("Apagar figurinha", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    emojiFor?.let { file ->
        var text by remember(file) {
            mutableStateOf(p?.stickers?.firstOrNull { it.file == file }?.emojis?.joinToString("").orEmpty())
        }
        AlertDialog(
            onDismissRequest = { emojiFor = null },
            title = { Text("Emojis da figurinha") },
            text = {
                OutlinedTextField(text, { text = it.take(24) }, label = { Text("Até 3 (usados na busca do WhatsApp)") })
            },
            confirmButton = {
                TextButton(onClick = { store.setEmojis(packId, file, Emojis.parse(text)); emojiFor = null; changed() }) {
                    Text("Salvar")
                }
            },
            dismissButton = { TextButton(onClick = { emojiFor = null }) { Text("Cancelar") } },
        )
    }

    if (renaming && p != null) {
        PackNameDialog("Renomear pacote", p.name, p.publisher, onDismiss = { renaming = false }) { n, a ->
            store.rename(packId, n, a); renaming = false; changed()
        }
    }

    if (deleting && p != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Apagar \"${p.name}\"?") },
            text = { Text("As figurinhas saem deste app. No WhatsApp, o pacote some na próxima atualização dele.") },
            confirmButton = {
                TextButton(onClick = { store.delete(packId); deleting = false; onBack() }) {
                    Text("Apagar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
        )
    }
}
