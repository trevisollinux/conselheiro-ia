package com.trevisolli.conselheirosticker.editor

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import com.trevisolli.conselheirosticker.data.AnimatedWebp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trevisolli.conselheirosticker.data.Emojis
import com.trevisolli.conselheirosticker.data.PackStore
import com.trevisolli.conselheirosticker.data.WebpEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** O que abrir no editor. */
sealed interface EditorSource {
    data class Photo(val uri: Uri) : EditorSource
    data class Existing(val file: String) : EditorSource
    data object TextOnly : EditorSource
    /** GIF ou vídeo para figurinha animada. */
    data class Animation(val uri: Uri) : EditorSource
}

private enum class EditTab(val label: String) { Cut("Recortar"), Adjust("Ajustar"), Text("Texto"), Effects("Efeitos") }
private enum class CutMode(val label: String) {
    Frame("Retângulo"), Lasso("Laço"), Erase("Borracha"), Restore("Pincel")
}

private val PALETTE = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD23F, 0xFFE53935, 0xFFFF7A00,
    0xFF43A047, 0xFF1E88E5, 0xFF6C3CE9, 0xFFEC407A,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    packId: String,
    source: EditorSource,
    store: PackStore,
    fonts: Fonts,
    onDone: (saved: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var state by remember { mutableStateOf<EditorState?>(null) }
    var tab by remember { mutableStateOf(if (source is EditorSource.TextOnly) EditTab.Text else EditTab.Cut) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var fontOptions by remember { mutableStateOf(fonts.options()) }
    var clip by remember { mutableStateOf<ClipInfo?>(null) }

    fun applyOutlinePrefs(st: EditorState) {
        // Usa a última escolha de borda (com ou sem contorno branco).
        val prefs = context.getSharedPreferences(OUTLINE_PREFS, android.content.Context.MODE_PRIVATE)
        st.outline = prefs.getBoolean("outline_on", false)
        st.outlineWidth = prefs.getFloat("outline_width", 10f)
        st.outlineColor = prefs.getInt("outline_color", android.graphics.Color.WHITE)
    }

    fun loadClip(info: ClipInfo, startMs: Int, endMs: Int) {
        scope.launch {
            busy = "Lendo os quadros…"
            try {
                val c = Clips.extract(context, info, startMs, endMs) { p -> busy = "Lendo os quadros… ${(p * 100).toInt()}%" }
                state = EditorState(c.frames[0], null, c.frames, c.durations).also { applyOutlinePrefs(it) }
            } catch (e: Exception) {
                snackbar.showSnackbar("Não consegui ler a animação: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    fun runAuto(st: EditorState) {
        scope.launch {
            if (st.isAnimated) {
                snackbar.showSnackbar("O recorte automático não funciona em animações. Use o retângulo ou o laço.")
                return@launch
            }
            busy = "Recortando o fundo…"
            try {
                val conf = withContext(Dispatchers.Default) { Images.segment(st.source) }
                st.applyConfidence(conf)
            } catch (e: Exception) {
                snackbar.showSnackbar(
                    "Recorte automático indisponível agora (o modelo pode estar baixando). Use o laço ou tente de novo."
                )
            } finally {
                busy = null
            }
        }
    }

    LaunchedEffect(source) {
        busy = "Abrindo…"
        try {
            state = when (source) {
                is EditorSource.TextOnly -> EditorState.blank()
                is EditorSource.Existing -> EditorState.load(store.editDir(packId, source.file))
                    ?: withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(store.stickerFile(packId, source.file).path, EditorState.mutableOpts())
                    }?.let { bmp ->
                        // Figurinha sem dados de edição: usa a própria imagem (já recortada) como foto.
                        EditorState(bmp).apply { outline = false; tab = EditTab.Text }
                    }
                is EditorSource.Photo -> EditorState(Images.load(context, source.uri)).also { applyOutlinePrefs(it) }
                is EditorSource.Animation -> {
                    // Primeiro escolhe o trecho; os quadros são lidos depois.
                    clip = Clips.probe(context, source.uri)
                    null
                }
            }
            if (state == null && clip == null) snackbar.showSnackbar("Não consegui abrir esta figurinha")
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message?.takeIf { source is EditorSource.Animation } ?: "Não consegui abrir a imagem")
        } finally {
            if (busy == "Abrindo…") busy = null
        }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val key = fonts.import(uri, name)
        if (key == null) {
            scope.launch { snackbar.showSnackbar("Esse arquivo não parece uma fonte (.ttf ou .otf)") }
        } else {
            fontOptions = fonts.options()
            state?.updateSelected { it.copy(font = key) }
        }
    }

    fun save() {
        val st = state ?: return
        scope.launch {
            busy = "Salvando…"
            try {
                val replace = (source as? EditorSource.Existing)?.file
                if (st.willAnimate) {
                    // GIF/vídeo: um quadro por quadro do trecho. Foto com efeito: 16 quadros da mesma imagem.
                    val count = if (st.isAnimated) st.frames!!.size else Effects.STILL_FRAMES
                    val durations = if (st.isAnimated) st.durations!! else List(count) { Effects.STILL_FRAME_MS }
                    val bounds = st.contentBounds()
                    val rendered = withContext(Dispatchers.Default) {
                        val still = if (st.isAnimated) null else st.cutoutFor(0, bounds)
                        try {
                            (0 until count).map { i ->
                                busy = "Preparando quadros… ${(i + 1) * 100 / count}%"
                                val cut = if (st.isAnimated) st.cutoutFor(i, bounds) else still
                                Render.export(st, fonts, cut, phase = i.toFloat() / count)
                                    .also { if (st.isAnimated) cut?.recycle() }
                            }
                        } finally {
                            still?.recycle()
                        }
                    }
                    try {
                        busy = "Comprimindo para o WhatsApp…"
                        val result = withContext(Dispatchers.Default) { AnimatedWebp.encodeFitting(rendered, durations) }
                        if (result.bytes.size > AnimatedWebp.MAX_BYTES) {
                            busy = null
                            snackbar.showSnackbar("A animação ficou grande demais para o WhatsApp. Escolha um trecho mais curto.")
                            return@launch
                        }
                        withContext(Dispatchers.IO) {
                            val file = store.putSticker(
                                packId, result.bytes, Emojis.parse(st.emojis), replace, animated = true, still = rendered[0],
                            )
                            st.save(store.editDir(packId, file))
                        }
                    } finally {
                        rendered.forEach { it.recycle() }
                    }
                } else {
                    val bmp = Render.export(st, fonts)
                    val bytes = withContext(Dispatchers.Default) { WebpEncoder.encode(bmp) }
                    bmp.recycle()
                    withContext(Dispatchers.IO) {
                        val file = store.putSticker(packId, bytes, Emojis.parse(st.emojis), replace)
                        st.save(store.editDir(packId, file))
                    }
                }
                onDone(true)
            } catch (e: Exception) {
                busy = null
                snackbar.showSnackbar("Não consegui salvar: ${e.message}")
            }
        }
    }

    BackHandler { confirmExit = true }

    // Desfazer de textos, ajustes e efeitos: cada mudança vira um passo quando "assenta" (0,45 s parado).
    LaunchedEffect(state) {
        val st = state ?: return@LaunchedEffect
        var committed = st.props()
        snapshotFlow { st.props() }.collectLatest { p ->
            kotlinx.coroutines.delay(450)
            if (p == committed) return@collectLatest
            if (st.skipNextCommit) st.skipNextCommit = false else st.pushProps(committed)
            committed = p
        }
    }

    // Fundo da prévia: xadrez (transparência), conversa clara ou conversa escura, como no WhatsApp.
    var previewBg by remember { mutableIntStateOf(0) }

    // Prévia animada nas abas Ajustar e Texto (para enquanto salva).
    // Os efeitos ficam parados na aba Texto, para dar para tocar e arrastar os textos.
    val st0 = state
    val effectsOn = st0?.effects?.isNotEmpty() == true
    LaunchedEffect(state, tab, busy, effectsOn) {
        val st = state ?: return@LaunchedEffect
        if (tab == EditTab.Cut || busy != null) return@LaunchedEffect
        if (st.isAnimated) {
            val ds = st.durations!!
            while (true) {
                kotlinx.coroutines.delay(ds[st.frameIndex.coerceIn(0, ds.lastIndex)].toLong().coerceAtLeast(30))
                st.frameIndex = (st.frameIndex + 1) % ds.size
                st.effectPhase = if (tab == EditTab.Text) 0f else st.frameIndex.toFloat() / ds.size
            }
        } else if (st.effects.isNotEmpty() && tab != EditTab.Text) {
            var i = 0
            while (true) {
                st.effectPhase = i.toFloat() / Effects.STILL_FRAMES
                kotlinx.coroutines.delay(Effects.STILL_FRAME_MS.toLong())
                i = (i + 1) % Effects.STILL_FRAMES
            }
        } else {
            st.effectPhase = 0f
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Sair sem salvar?") },
            text = { Text("As mudanças desta figurinha serão perdidas.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; onDone(false) }) { Text("Sair") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Continuar editando") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar") },
                navigationIcon = {
                    IconButton(onClick = { confirmExit = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    val st = state
                    if (st != null) {
                        TextButton(onClick = { st.undo() }, enabled = st.canUndo) { Text("Desfazer") }
                    }
                    Button(onClick = { save() }, enabled = state != null && busy == null,
                        modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Salvar")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val st = state
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                EditTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
            if (st == null) {
                val info = clip
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        busy != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(busy.orEmpty())
                        }
                        info != null -> TrimPanel(info) { a, b -> loadClip(info, a, b) }
                        else -> Text("Nada para editar")
                    }
                }
                return@Column
            }
            // Área de edição: o quadro da figurinha ou a foto inteira (no recorte).
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val side = min(maxWidth.value, maxHeight.value).dp
                if (tab == EditTab.Cut) {
                    CutView(st, cutMode.value, brushSize.floatValue, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.size(side)) {
                        StickerPreview(st, fonts, tab, previewBg, Modifier.fillMaxSize())
                        TextButton(
                            onClick = { previewBg = (previewBg + 1) % 3 },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                .clip(RoundedCornerShape(50)).background(Color(0x991B1530)),
                        ) {
                            Text(
                                when (previewBg) { 0 -> "🏁 Fundo"; 1 -> "☀️ Conversa"; else -> "🌙 Conversa" },
                                color = Color.White, style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                busy?.let {
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC1B1530)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Color.White)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().height(250.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tab) {
                    EditTab.Cut -> {
                        if (st.isAnimated) {
                            Text(
                                "Animação com ${st.frames!!.size} quadros. O recorte vale para todos; a prévia anima em Ajustar e Texto.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        CutControls(st, onAuto = { runAuto(st) })
                    }
                    EditTab.Adjust -> AdjustControls(st)
                    EditTab.Text -> TextControls(st, fontOptions, fonts, onImportFont = { fontPicker.launch(arrayOf("*/*")) })
                    EditTab.Effects -> EffectsControls(st)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsControls(st: EditorState) {
    Text(
        if (st.isAnimated) "Os efeitos tocam junto com a animação."
        else "Com um efeito, a figurinha vira animada. Dá para combinar (ex.: fogo + tremor). " +
            "A chuva de emoji usa o emoji da figurinha (aba Ajustar).",
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Effect.entries.forEach { e ->
            FilterChip(
                selected = e in st.effects,
                onClick = { st.effects = if (e in st.effects) st.effects - e else st.effects + e },
                label = { Text(e.label) },
            )
        }
    }
    if (st.effects.isNotEmpty()) {
        OutlinedButton(onClick = { st.effects = emptySet() }) { Text("Tirar efeitos") }
    }
}

/** Escolha do trecho do GIF/vídeo (até 10 segundos). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimPanel(info: ClipInfo, onConfirm: (startMs: Int, endMs: Int) -> Unit) {
    val total = info.durationMs.toFloat()
    val maxLen = Clips.MAX_CLIP_MS.toFloat()
    var range by remember(info) { mutableStateOf(0f..minOf(total, 4000f)) }
    // A prévia mostra o quadro da ponta que está sendo mexida (início ou fim do trecho).
    var showingEnd by remember(info) { mutableStateOf(false) }
    val context = LocalContext.current
    val previewer = remember(info) { runCatching { Clips.FramePreview(context, info) }.getOrNull() }
    DisposableEffect(previewer) { onDispose { previewer?.close() } }
    val targetMs = (if (showingEnd) range.endInclusive else range.start).toInt()
    val frame by produceState(info.preview, previewer, targetMs / 50) {
        kotlinx.coroutines.delay(80) // espera o dedo parar um pouco antes de ler o quadro
        val f = withContext(Dispatchers.IO) { previewer?.frame(targetMs) }
        if (f != null) value = f
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Escolha o trecho", style = MaterialTheme.typography.titleMedium)
        frame?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Text(
            (if (showingEnd) "Mostrando o fim: %.1f s" else "Mostrando o início: %.1f s").format(targetMs / 1000f),
            style = MaterialTheme.typography.bodySmall,
        )
        val len = range.endInclusive - range.start
        Text("De %.1f s a %.1f s (%.1f s)".format(range.start / 1000, range.endInclusive / 1000, len / 1000))
        RangeSlider(
            value = range,
            onValueChange = { r ->
                showingEnd = r.start == range.start && r.endInclusive != range.endInclusive
                // Janela de no máximo 10 s: mexer uma ponta arrasta a outra.
                range = when {
                    r.endInclusive - r.start <= maxLen -> r
                    r.start != range.start -> r.start..(r.start + maxLen)
                    else -> (r.endInclusive - maxLen)..r.endInclusive
                }
            },
            valueRange = 0f..total,
        )
        Text(
            "Trechos curtos (2 a 4 s) ficam mais fluidos: o WhatsApp aceita até 500 KB por figurinha animada.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { onConfirm(range.start.toInt(), range.endInclusive.toInt()) }, enabled = len >= 200f) {
            Text("Usar este trecho")
        }
    }
}

private const val OUTLINE_PREFS = "sticker"
private val OUTLINE_PRESETS = listOf("Sem borda" to 0f, "Fina" to 5f, "Média" to 10f, "Grossa" to 18f)

// Estado das ferramentas de recorte, compartilhado entre a área de desenho e os botões.
private val cutMode = mutableStateOf(CutMode.Frame)
private val brushSize = mutableFloatStateOf(40f)
private val frameShape = mutableStateOf(FrameShape.Free)

/** Formato da moldura de corte. */
private enum class FrameShape(val label: String) { Free("Livre"), Square("Quadrado"), Circle("Círculo") }

/** Qual parte da moldura o dedo está arrastando. */
private enum class Grab { None, Move, L, T, R, B, TL, TR, BL, BR }

// ---------------------------------------------------------------- Recorte

@Composable
private fun CutView(st: EditorState, mode: CutMode, brush: Float, modifier: Modifier) {
    // Pontos do laço em px da tela sem zoom (desenhados junto com a foto).
    val lassoPoints = remember { mutableStateListOf<Offset>() }
    var fingerAt by remember { mutableStateOf<Offset?>(null) }
    val shape = frameShape.value
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = st.source.width.toFloat() / st.source.height
        Canvas(
            Modifier.aspectRatio(ratio).clipToBounds().pointerInput(mode, brush, shape) {
                val k = st.source.width / size.width.toFloat() // px da tela (sem zoom) → px da foto
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                fun content(p: Offset) = (p - st.cutPan) / st.cutZoom
                // Um dedo: a ferramenta. Dois dedos: zoom e mover a foto.
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var zooming = false
                    var started = false
                    var grab = Grab.None
                    var last = first.position
                    val path = Path()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (!zooming) { zooming = true; lassoPoints.clear(); fingerAt = null }
                            val old = st.cutZoom
                            val z = (old * ev.calculateZoom()).coerceIn(1f, 6f)
                            val c = ev.calculateCentroid(useCurrent = true)
                            val pan = (st.cutPan - c) * (z / old) + c + ev.calculatePan()
                            st.cutZoom = z
                            st.cutPan = Offset(pan.x.coerceIn(w * (1 - z), 0f), pan.y.coerceIn(h * (1 - z), 0f))
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (zooming) continue // depois do zoom, espera soltar os dedos
                        val ch = pressed[0]
                        val p = ch.position
                        if (!started) {
                            if ((p - first.position).getDistance() < viewConfiguration.touchSlop) continue
                            started = true
                            val c0 = content(first.position)
                            when (mode) {
                                CutMode.Frame -> grab = st.frame.grabAt(
                                    c0.x * k, c0.y * k, 32.dp.toPx() * k / st.cutZoom, shape == FrameShape.Free,
                                )
                                CutMode.Lasso -> { lassoPoints.clear(); lassoPoints += c0; path.reset(); path.moveTo(c0.x * k, c0.y * k) }
                                else -> st.pushUndo()
                            }
                        }
                        val a = content(last)
                        val b = content(p)
                        when (mode) {
                            CutMode.Frame -> if (grab != Grab.None) {
                                st.frame = st.frame.dragged(grab, (b.x - a.x) * k, (b.y - a.y) * k, shape != FrameShape.Free,
                                    st.source.width.toFloat(), st.source.height.toFloat())
                            }
                            CutMode.Lasso -> { path.lineTo(b.x * k, b.y * k); lassoPoints += b }
                            else -> {
                                val seg = Path().apply { moveTo(a.x * k, a.y * k); lineTo(b.x * k, b.y * k) }
                                // O pincel tem o mesmo tamanho na tela com ou sem zoom.
                                st.brush(seg, brush * k / st.cutZoom, erase = mode == CutMode.Erase)
                                fingerAt = p
                            }
                        }
                        last = p
                        ch.consume()
                    }
                    if (started && !zooming && mode == CutMode.Lasso && lassoPoints.size > 4) st.lasso(path)
                    lassoPoints.clear()
                    fingerAt = null
                }
            }
        ) {
            checkerboard()
            @Suppress("UNUSED_VARIABLE") val v = st.maskVersion // redesenha quando a máscara muda
            withTransform({
                translate(st.cutPan.x, st.cutPan.y)
                scale(st.cutZoom, st.cutZoom, pivot = Offset.Zero)
            }) {
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val src = Rect(0, 0, st.source.width, st.source.height)
                    val dst = RectF(0f, 0f, size.width, size.height)
                    // A parte removida aparece apagadinha, para saber o que dá para devolver com o pincel.
                    nc.drawBitmap(st.source, src, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 60 })
                    val layer = nc.saveLayer(dst, null)
                    nc.drawBitmap(st.source, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                    nc.drawBitmap(st.mask, src, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    })
                    nc.restoreToCount(layer)
                }
                if (mode == CutMode.Frame) drawFrame(st.frame, size.width / st.source.width, shape)
                if (lassoPoints.size > 1) {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(lassoPoints[0].x, lassoPoints[0].y)
                        lassoPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(p, Color.White, style = Stroke(width = 6f / st.cutZoom))
                    drawPath(p, Color(0xFF6C3CE9), style = Stroke(width = 3f / st.cutZoom))
                }
            }
            fingerAt?.let { if (mode != CutMode.Lasso) drawCircle(Color.White, brush / 2, it, style = Stroke(2f)) }
        }
    }
}

/** Moldura: escurece o que fica de fora, linhas de terço e alças nos cantos e lados. */
private fun DrawScope.drawFrame(frameBmp: ComposeRect, s: Float, shape: FrameShape) {
    val r = ComposeRect(frameBmp.left * s, frameBmp.top * s, frameBmp.right * s, frameBmp.bottom * s)
    val outside = androidx.compose.ui.graphics.Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(ComposeRect(Offset.Zero, size))
        if (shape == FrameShape.Circle) addOval(r) else addRect(r)
    }
    drawPath(outside, Color(0x99000000))
    if (shape == FrameShape.Circle) drawOval(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
    drawRect(Color.White.copy(alpha = if (shape == FrameShape.Circle) 0.5f else 1f), r.topLeft, r.size,
        style = Stroke(2.dp.toPx()))
    for (i in 1..2) {
        val x = r.left + r.width * i / 3
        val y = r.top + r.height * i / 3
        drawLine(Color.White.copy(alpha = 0.45f), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.45f), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
    }
    // Cantos em "L", grossos, como nos editores de foto.
    val len = minOf(22.dp.toPx(), r.width / 3, r.height / 3)
    val w = 4.dp.toPx()
    listOf(
        Triple(r.topLeft, 1f, 1f), Triple(r.topRight, -1f, 1f),
        Triple(r.bottomLeft, 1f, -1f), Triple(r.bottomRight, -1f, -1f),
    ).forEach { (c, dx, dy) ->
        drawLine(Color.White, c, Offset(c.x + len * dx, c.y), w, cap = StrokeCap.Round)
        drawLine(Color.White, c, Offset(c.x, c.y + len * dy), w, cap = StrokeCap.Round)
    }
    if (shape == FrameShape.Free) {
        val bar = minOf(18.dp.toPx(), r.width / 4, r.height / 4)
        listOf(Offset(r.center.x, r.top), Offset(r.center.x, r.bottom)).forEach {
            drawLine(Color.White, Offset(it.x - bar, it.y), Offset(it.x + bar, it.y), w, cap = StrokeCap.Round)
        }
        listOf(Offset(r.left, r.center.y), Offset(r.right, r.center.y)).forEach {
            drawLine(Color.White, Offset(it.x, it.y - bar), Offset(it.x, it.y + bar), w, cap = StrokeCap.Round)
        }
    }
}

private fun ComposeRect.grabAt(x: Float, y: Float, tol: Float, edges: Boolean): Grab {
    fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= tol
    val inX = x in (left - tol)..(right + tol)
    val inY = y in (top - tol)..(bottom + tol)
    return when {
        near(x, left) && near(y, top) -> Grab.TL
        near(x, right) && near(y, top) -> Grab.TR
        near(x, left) && near(y, bottom) -> Grab.BL
        near(x, right) && near(y, bottom) -> Grab.BR
        edges && near(x, left) && inY -> Grab.L
        edges && near(x, right) && inY -> Grab.R
        edges && near(y, top) && inX -> Grab.T
        edges && near(y, bottom) && inX -> Grab.B
        contains(Offset(x, y)) -> Grab.Move
        else -> Grab.None
    }
}

/** Nova moldura depois de arrastar; mantém dentro da foto e com tamanho mínimo. */
private fun ComposeRect.dragged(g: Grab, dx: Float, dy: Float, square: Boolean, w: Float, h: Float): ComposeRect {
    val min = maxOf(24f, minOf(w, h) * 0.06f)
    if (g == Grab.Move) {
        val nx = (left + dx).coerceIn(0f, w - width)
        val ny = (top + dy).coerceIn(0f, h - height)
        return ComposeRect(Offset(nx, ny), size)
    }
    var l = left; var t = top; var r = right; var b = bottom
    when (g) {
        Grab.L, Grab.TL, Grab.BL -> l = (l + dx).coerceIn(0f, r - min)
        Grab.R, Grab.TR, Grab.BR -> r = (r + dx).coerceIn(l + min, w)
        else -> {}
    }
    when (g) {
        Grab.T, Grab.TL, Grab.TR -> t = (t + dy).coerceIn(0f, b - min)
        Grab.B, Grab.BL, Grab.BR -> b = (b + dy).coerceIn(t + min, h)
        else -> {}
    }
    if (square) {
        // Lado = média da largura e altura, ancorado no canto oposto, sem sair da foto.
        var s = ((r - l) + (b - t)) / 2f
        s = when (g) {
            Grab.TL -> minOf(s, r, b)
            Grab.TR -> minOf(s, w - l, b)
            Grab.BL -> minOf(s, r, h - t)
            else -> minOf(s, w - l, h - t)
        }.coerceAtLeast(min)
        when (g) {
            Grab.TL -> { l = r - s; t = b - s }
            Grab.TR -> { r = l + s; t = b - s }
            Grab.BL -> { l = r - s; b = t + s }
            else -> { r = l + s; b = t + s }
        }
    }
    return ComposeRect(l, t, r, b)
}

/** Moldura quadrada do mesmo centro (para Quadrado e Círculo). */
private fun ComposeRect.squared(): ComposeRect {
    val s = minOf(width, height) / 2f
    return ComposeRect(center.x - s, center.y - s, center.x + s, center.y + s)
}

@Composable
private fun CutControls(st: EditorState, onAuto: () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CutMode.entries.forEach { m ->
            FilterChip(selected = cutMode.value == m, onClick = { cutMode.value = m }, label = { Text(m.label) })
        }
    }
    when (cutMode.value) {
        CutMode.Frame -> {
            Text("Arraste os cantos e os lados para ajustar; arraste o meio para mover.",
                style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FrameShape.entries.forEach { f ->
                    FilterChip(
                        selected = frameShape.value == f,
                        onClick = {
                            frameShape.value = f
                            if (f != FrameShape.Free) st.frame = st.frame.squared()
                        },
                        label = { Text(f.label) },
                    )
                }
            }
            Button(onClick = { st.cropTo(st.frame, oval = frameShape.value == FrameShape.Circle) }) {
                Text("✂️ Cortar")
            }
        }
        CutMode.Lasso -> Text("Contorne com o dedo o que quer manter.", style = MaterialTheme.typography.bodySmall)
        CutMode.Erase, CutMode.Restore -> {
            Text(
                if (cutMode.value == CutMode.Erase) "Passe o dedo para apagar partes."
                else "Pinte o que quer manter (ou trazer de volta). Para escolher só um pedaço da foto, " +
                    "toque em Pintar do zero e pinte por cima dele.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (cutMode.value == CutMode.Restore) {
                OutlinedButton(onClick = { st.clearMask() }) { Text("🖌️ Pintar do zero") }
            }
            LabeledSlider("Tamanho do pincel", brushSize.floatValue, 8f..120f) { brushSize.floatValue = it }
        }
    }
    Text(
        if (st.cutZoom > 1.01f) "Zoom %.1fx · dois dedos para mover ou afastar".format(st.cutZoom)
        else "Use dois dedos para dar zoom e acertar os detalhes.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (st.cutZoom > 1.01f) {
        OutlinedButton(onClick = { st.cutZoom = 1f; st.cutPan = Offset.Zero }) { Text("🔍 Ver a foto inteira") }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onAuto) { Text("✨ Automático") }
        OutlinedButton(onClick = { st.resetMask() }) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Recomeçar")
        }
    }
}

// ---------------------------------------------------------------- Prévia 512

@Composable
private fun StickerPreview(st: EditorState, fonts: Fonts, tab: EditTab, background: Int, modifier: Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .pointerInput(tab) {
                if (tab != EditTab.Text) return@pointerInput
                // Toque: escolhe o texto sob o dedo.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val k = Render.SIZE / size.width
                    val hit = Render.hitText(st, fonts, down.position.x * k, down.position.y * k)
                    st.selected = hit
                }
            }
            .pointerInput(tab) {
                val k = Render.SIZE / size.width
                detectTransformGestures { _, pan, zoom, rot ->
                    if (tab == EditTab.Adjust) {
                        st.offset += pan * k
                        st.scale = (st.scale * zoom).coerceIn(0.1f, 8f)
                        st.rotation += rot
                    } else if (tab == EditTab.Text) {
                        st.updateSelected {
                            it.copy(
                                x = (it.x + pan.x * k).coerceIn(0f, Render.SIZE),
                                y = (it.y + pan.y * k).coerceIn(0f, Render.SIZE),
                                size = (it.size * zoom).coerceIn(12f, 300f),
                                rotation = it.rotation + rot,
                            )
                        }
                    }
                }
            }
    ) {
        when (background) {
            1 -> drawRect(Color(0xFFEFE7DE)) // papel de parede claro do WhatsApp
            2 -> drawRect(Color(0xFF0B141A)) // modo escuro do WhatsApp
            else -> checkerboard()
        }
        drawIntoCanvas { Render.draw(it.nativeCanvas, size.width, st, fonts, showSelection = tab == EditTab.Text) }
    }
}

@Composable
private fun AdjustControls(st: EditorState) {
    val context = LocalContext.current
    // Guarda a escolha para as próximas figurinhas.
    fun saveChoice() {
        context.getSharedPreferences(OUTLINE_PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("outline_on", st.outline)
            .putFloat("outline_width", st.outlineWidth)
            .putInt("outline_color", st.outlineColor)
            .apply()
    }
    Text("Borda em volta da figurinha", style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OUTLINE_PRESETS.forEach { (label, width) ->
            val selected = if (width == 0f) !st.outline else st.outline && kotlin.math.abs(st.outlineWidth - width) < 0.5f
            FilterChip(
                selected = selected,
                onClick = {
                    if (width == 0f) st.outline = false else { st.outline = true; st.outlineWidth = width }
                    saveChoice()
                },
                label = { Text(label) },
            )
        }
    }
    if (st.outline) {
        LabeledSlider("Espessura", st.outlineWidth, 2f..24f) { st.outlineWidth = it; saveChoice() }
        ColorRow(st.outlineColor) { st.outlineColor = it; saveChoice() }
    }
    Text("Arraste para mover, use dois dedos para zoom e giro.", style = MaterialTheme.typography.bodySmall)
    LabeledSlider("Zoom", st.scale, 0.2f..3f) { st.scale = it }
    LabeledSlider("Giro", st.rotation, -180f..180f) { st.rotation = it }
    OutlinedButton(onClick = { st.scale = 1f; st.offset = Offset.Zero; st.rotation = 0f }) { Text("Centralizar") }
    OutlinedTextField(
        value = st.emojis,
        onValueChange = { st.emojis = it.take(24) },
        label = { Text("Emojis (até 3, para a busca do WhatsApp)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- Texto

@Composable
private fun TextControls(st: EditorState, options: List<FontOption>, fonts: Fonts, onImportFont: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { st.addText() }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Texto")
        }
        if (st.selected in st.texts.indices) {
            OutlinedButton(onClick = { st.removeSelected() }) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Apagar")
            }
        }
    }
    val t = st.texts.getOrNull(st.selected)
    if (t == null) {
        Text("Toque num texto da figurinha para editar, ou adicione um.", style = MaterialTheme.typography.bodySmall)
        return
    }
    OutlinedTextField(
        value = t.text,
        onValueChange = { v -> st.updateSelected { it.copy(text = v) } },
        label = { Text("Texto") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
    Text("Fonte", style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.key }) { f ->
            val selected = f.key == t.font
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .border(
                        BorderStroke(if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { st.updateSelected { it.copy(font = f.key) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(f.label, fontFamily = FontFamily(fonts.typeface(f.key)), fontSize = 18.sp)
            }
        }
        item {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onImportFont)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("+ Importar fonte", fontSize = 16.sp) }
        }
    }
    LabeledSlider("Tamanho", t.size, 16f..220f) { v -> st.updateSelected { it.copy(size = v) } }
    Text("Cor da letra", style = MaterialTheme.typography.labelLarge)
    ColorRow(t.color) { c -> st.updateSelected { it.copy(color = c) } }
    Text("Cor da borda", style = MaterialTheme.typography.labelLarge)
    ColorRow(t.stroke) { c -> st.updateSelected { it.copy(stroke = c) } }
    LabeledSlider("Espessura da borda", t.strokeRatio, 0f..0.3f) { v -> st.updateSelected { it.copy(strokeRatio = v) } }
    LabeledSlider("Giro", t.rotation, -180f..180f) { v -> st.updateSelected { it.copy(rotation = v) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("CAIXA ALTA", Modifier.weight(1f))
        Switch(checked = t.allCaps, onCheckedChange = { v -> st.updateSelected { it.copy(allCaps = v) } })
    }
}

// ---------------------------------------------------------------- Peças

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PALETTE.forEach { c ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                    .border(
                        if (c == selected) 3.dp else 1.dp,
                        if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        CircleShape,
                    )
                    .clickable { onPick(c) },
            )
        }
    }
}

/** Fundo xadrez: mostra o que é transparente. */
private fun DrawScope.checkerboard() {
    val cell = 16.dp.toPx()
    val light = Color(0xFFEEEEEE)
    val dark = Color(0xFFD6D6D6)
    drawRect(light)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(dark, Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
            x += cell * 2
        }
        y += cell; row++
    }
}
