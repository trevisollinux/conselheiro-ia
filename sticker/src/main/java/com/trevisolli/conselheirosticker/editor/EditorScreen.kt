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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** O que abrir no editor. */
sealed interface EditorSource {
    data class Photo(val uri: Uri) : EditorSource
    data class Existing(val file: String) : EditorSource
    data object TextOnly : EditorSource
}

private enum class EditTab(val label: String) { Cut("Recortar"), Adjust("Ajustar"), Text("Texto") }
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

    fun runAuto(st: EditorState) {
        scope.launch {
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
                is EditorSource.Photo -> EditorState(Images.load(context, source.uri)).apply {
                    // Usa a última escolha de borda (com ou sem contorno branco).
                    val prefs = context.getSharedPreferences(OUTLINE_PREFS, android.content.Context.MODE_PRIVATE)
                    outline = prefs.getBoolean("outline_on", false)
                    outlineWidth = prefs.getFloat("outline_width", 10f)
                    outlineColor = prefs.getInt("outline_color", android.graphics.Color.WHITE)
                }
            }
            if (state == null) snackbar.showSnackbar("Não consegui abrir esta figurinha")
        } catch (e: Exception) {
            snackbar.showSnackbar("Não consegui abrir a imagem")
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
                val bmp = Render.export(st, fonts)
                val bytes = withContext(Dispatchers.Default) { WebpEncoder.encode(bmp) }
                bmp.recycle()
                withContext(Dispatchers.IO) {
                    val replace = (source as? EditorSource.Existing)?.file
                    val file = store.putSticker(packId, bytes, Emojis.parse(st.emojis), replace)
                    st.save(store.editDir(packId, file))
                }
                onDone(true)
            } catch (e: Exception) {
                busy = null
                snackbar.showSnackbar("Não consegui salvar: ${e.message}")
            }
        }
    }

    BackHandler { confirmExit = true }

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
                    if (st != null && tab == EditTab.Cut) {
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
            TabRow(selectedTabIndex = tab.ordinal) {
                EditTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
            if (st == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (busy != null) CircularProgressIndicator() else Text("Nada para editar")
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
                    StickerPreview(st, fonts, tab, Modifier.size(side))
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
                    EditTab.Cut -> CutControls(st, onAuto = { runAuto(st) })
                    EditTab.Adjust -> AdjustControls(st)
                    EditTab.Text -> TextControls(st, fontOptions, fonts, onImportFont = { fontPicker.launch(arrayOf("*/*")) })
                }
            }
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
    val lassoPoints = remember { mutableStateListOf<Offset>() }
    var fingerAt by remember { mutableStateOf<Offset?>(null) }
    val shape = frameShape.value
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = st.source.width.toFloat() / st.source.height
        Canvas(
            Modifier.aspectRatio(ratio).clipToBounds().pointerInput(mode, brush, shape) {
                val k = st.source.width / size.width.toFloat() // px da tela → px da foto
                if (mode == CutMode.Frame) {
                    val handle = 32.dp.toPx() * k // área de toque das alças, em px da foto
                    var grab = Grab.None
                    detectDragGestures(
                        onDragStart = { p -> grab = st.frame.grabAt(p.x * k, p.y * k, handle, shape == FrameShape.Free) },
                        onDrag = { change, d ->
                            if (grab != Grab.None) {
                                st.frame = st.frame.dragged(grab, d.x * k, d.y * k, shape != FrameShape.Free,
                                    st.source.width.toFloat(), st.source.height.toFloat())
                                change.consume()
                            }
                        },
                        onDragEnd = { grab = Grab.None },
                        onDragCancel = { grab = Grab.None },
                    )
                    return@pointerInput
                }
                var last: Offset? = null
                val path = Path()
                detectDragGestures(
                    onDragStart = { p ->
                        last = p
                        fingerAt = p
                        path.reset(); path.moveTo(p.x * k, p.y * k)
                        if (mode == CutMode.Lasso) { lassoPoints.clear(); lassoPoints += p } else st.pushUndo()
                    },
                    onDrag = { change, _ ->
                        val p = change.position
                        fingerAt = p
                        if (mode == CutMode.Lasso) {
                            path.lineTo(p.x * k, p.y * k); lassoPoints += p
                        } else {
                            val seg = Path().apply {
                                val l = last ?: p
                                moveTo(l.x * k, l.y * k); lineTo(p.x * k, p.y * k)
                            }
                            st.brush(seg, brush * k, erase = mode == CutMode.Erase)
                        }
                        last = p
                        change.consume()
                    },
                    onDragEnd = {
                        if (mode == CutMode.Lasso && lassoPoints.size > 4) st.lasso(path)
                        lassoPoints.clear(); fingerAt = null
                    },
                    onDragCancel = { lassoPoints.clear(); fingerAt = null },
                )
            }
        ) {
            checkerboard()
            @Suppress("UNUSED_VARIABLE") val v = st.maskVersion // redesenha quando a máscara muda
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
                drawPath(p, Color.White, style = Stroke(width = 6f))
                drawPath(p, Color(0xFF6C3CE9), style = Stroke(width = 3f))
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
                else "Passe o dedo para trazer de volta o que foi apagado.",
                style = MaterialTheme.typography.bodySmall,
            )
            LabeledSlider("Tamanho do pincel", brushSize.floatValue, 8f..120f) { brushSize.floatValue = it }
        }
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
private fun StickerPreview(st: EditorState, fonts: Fonts, tab: EditTab, modifier: Modifier) {
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
        checkerboard()
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
