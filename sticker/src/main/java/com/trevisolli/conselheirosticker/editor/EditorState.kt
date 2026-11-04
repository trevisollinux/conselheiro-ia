package com.trevisolli.conselheirosticker.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma camada de texto, em coordenadas da figurinha (0..512). */
data class TextLayer(
    val text: String = "TEXTO",
    val font: String = Fonts.DEFAULT,
    val size: Float = 64f,
    val color: Int = Color.WHITE,
    val stroke: Int = Color.BLACK,
    /** Espessura da borda em relação ao tamanho da letra. */
    val strokeRatio: Float = 0.14f,
    val x: Float = 256f,
    val y: Float = 70f,
    val rotation: Float = 0f,
    val allCaps: Boolean = true,
) {
    val shown: String get() = if (allCaps) text.uppercase() else text
}

/**
 * Tudo que dá para editar numa figurinha.
 * - source: a foto (até 1024 px), nunca alterada
 * - mask: o que fica (opaco) e o que sai (transparente) — recorte automático, laço, borracha e pincel
 * - imagem posicionada no quadro 512×512 com zoom, giro e deslocamento
 * - contorno (o "adesivo" branco em volta) e textos
 */
class EditorState(val source: Bitmap, mask: Bitmap? = null) {
    val mask: Bitmap = mask ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ALPHA_8).apply {
        eraseColor(Color.BLACK) // ALPHA_8: preto = alfa 255 = tudo visível
    }

    /** Muda a cada edição da máscara, para o Compose redesenhar. */
    var maskVersion by mutableIntStateOf(0)
        private set

    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var rotation by mutableFloatStateOf(0f)

    var outline by mutableStateOf(true)
    var outlineWidth by mutableFloatStateOf(10f)
    var outlineColor by mutableIntStateOf(Color.WHITE)

    val texts = mutableStateListOf<TextLayer>()
    var selected by mutableIntStateOf(-1)

    var emojis by mutableStateOf("😂")

    private val undo = ArrayDeque<Bitmap>()
    var canUndo by mutableStateOf(false)
        private set

    // ---------- Recorte ----------

    fun pushUndo() {
        undo.addLast(mask.copy(Bitmap.Config.ALPHA_8, true))
        while (undo.size > 15) undo.removeFirst().recycle()
        canUndo = true
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        Canvas(mask).drawBitmap(prev, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        prev.recycle()
        canUndo = undo.isNotEmpty()
        touched()
    }

    fun resetMask() {
        pushUndo()
        mask.eraseColor(Color.BLACK)
        touched()
    }

    /** Moldura de corte (retângulo com alças), em pixels da foto. Começa com 8% de folga em volta. */
    var frame by mutableStateOf(
        ComposeRect(source.width * 0.08f, source.height * 0.08f, source.width * 0.92f, source.height * 0.92f)
    )

    /** Fica só o que está dentro da moldura (retângulo ou círculo); o resto vira transparente. */
    fun cropTo(r: ComposeRect, oval: Boolean) {
        pushUndo()
        mask.eraseColor(Color.TRANSPARENT)
        val rf = android.graphics.RectF(r.left, r.top, r.right, r.bottom)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        Canvas(mask).apply { if (oval) drawOval(rf, paint) else drawRect(rf, paint) }
        touched()
    }

    /** Mantém só o que está dentro do contorno desenhado com o dedo. */
    fun lasso(path: Path) {
        pushUndo()
        val p = Path(path).apply { close(); fillType = Path.FillType.INVERSE_WINDING }
        Canvas(mask).drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        })
        touched()
    }

    /** Borracha (apaga) ou pincel (devolve o que foi apagado). */
    fun brush(path: Path, width: Float, erase: Boolean) {
        Canvas(mask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
            if (erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        })
        touched()
    }

    /** Resultado do recorte automático: confiança 0..1 por pixel. */
    fun applyConfidence(conf: FloatArray) {
        pushUndo()
        val px = IntArray(conf.size) { i ->
            val a = ((conf[i] - 0.35f) / 0.3f).coerceIn(0f, 1f)
            (a * 255).toInt() shl 24
        }
        mask.setPixels(px, 0, mask.width, 0, 0, mask.width, mask.height)
        touched()
    }

    private fun touched() {
        maskVersion++
        cutoutCache = null
    }

    // ---------- Imagem recortada (com contorno) ----------

    private var cutoutCache: Pair<String, Bitmap?>? = null

    /** A foto já recortada, aparada nas bordas e com o contorno; null se não sobrou nada. */
    fun cutout(): Bitmap? {
        val key = "$maskVersion|$outline|$outlineWidth|$outlineColor"
        cutoutCache?.let { if (it.first == key) return it.second }
        val result = buildCutout()
        cutoutCache?.second?.let { old -> if (old != result) old.recycle() }
        cutoutCache = key to result
        return result
    }

    private fun buildCutout(): Bitmap? {
        val w = source.width
        val h = source.height
        val alpha = IntArray(w * h)
        mask.getPixels(alpha, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((alpha[y * w + x] ushr 24) > 12) {
                if (x < l) l = x; if (x > r) r = x
                if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < 0) return null
        val crop = Rect(l, t, r + 1, b + 1)
        // A figurinha final tem 512 px: acima de ~600 só deixaria o contorno lento.
        val k = minOf(1f, 600f / maxOf(crop.width(), crop.height()))
        val cw = (crop.width() * k).toInt().coerceAtLeast(1)
        val ch = (crop.height() * k).toInt().coerceAtLeast(1)
        val cut = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        Canvas(cut).apply {
            drawBitmap(source, crop, Rect(0, 0, cw, ch), filter)
            drawBitmap(mask, crop, Rect(0, 0, cw, ch),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        if (!outline || outlineWidth < 1f) return cut

        // Contorno em pixels da foto: proporcional ao tamanho, para ficar igual no quadro 512.
        val big = maxOf(cut.width, cut.height).toFloat()
        val rad = (outlineWidth * big / 460f).coerceAtLeast(1f)
        val pad = rad.toInt() + 2
        val out = Bitmap.createBitmap(cut.width + pad * 2, cut.height + pad * 2, Bitmap.Config.ARGB_8888)
        val sil = cut.extractAlpha()
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = outlineColor }
        val steps = 32
        for (ring in listOf(rad, rad * 0.66f, rad * 0.33f)) {
            for (i in 0 until steps) {
                val a = Math.PI * 2 * i / steps
                c.drawBitmap(sil, pad + (Math.cos(a) * ring).toFloat(), pad + (Math.sin(a) * ring).toFloat(), paint)
            }
        }
        c.drawBitmap(sil, pad.toFloat(), pad.toFloat(), paint)
        c.drawBitmap(cut, pad.toFloat(), pad.toFloat(), null)
        sil.recycle(); cut.recycle()
        return out
    }

    // ---------- Textos ----------

    fun addText() {
        val y = when (texts.size) { 0 -> 70f; 1 -> 450f; else -> 256f }
        texts += TextLayer(y = y)
        selected = texts.lastIndex
    }

    fun updateSelected(change: (TextLayer) -> TextLayer) {
        val i = selected
        if (i in texts.indices) texts[i] = change(texts[i])
    }

    fun removeSelected() {
        val i = selected
        if (i in texts.indices) texts.removeAt(i)
        selected = -1
    }

    // ---------- Guardar para reeditar ----------

    fun save(dir: File) {
        dir.mkdirs()
        File(dir, "source.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // A máscara vai como PNG em tons de cinza (alfa → branco), que qualquer decodificador lê.
        val vis = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(vis).apply { drawColor(Color.BLACK); drawBitmap(mask, 0f, 0f, Paint().apply { color = Color.WHITE }) }
        File(dir, "mask.png").outputStream().use { vis.compress(Bitmap.CompressFormat.PNG, 100, it) }
        vis.recycle()
        val json = JSONObject()
            .put("scale", scale.toDouble()).put("ox", offset.x.toDouble()).put("oy", offset.y.toDouble())
            .put("rotation", rotation.toDouble())
            .put("outline", outline).put("outlineWidth", outlineWidth.toDouble()).put("outlineColor", outlineColor)
            .put("emojis", emojis)
            .put("texts", JSONArray().apply {
                texts.forEach { t ->
                    put(JSONObject().put("text", t.text).put("font", t.font).put("size", t.size.toDouble())
                        .put("color", t.color).put("stroke", t.stroke).put("strokeRatio", t.strokeRatio.toDouble())
                        .put("x", t.x.toDouble()).put("y", t.y.toDouble()).put("rotation", t.rotation.toDouble())
                        .put("allCaps", t.allCaps))
                }
            })
        File(dir, "state.json").writeText(json.toString())
    }

    companion object {
        fun load(dir: File): EditorState? = runCatching {
            val src = BitmapFactory.decodeFile(File(dir, "source.png").path, mutableOpts())!!
            val visMask = BitmapFactory.decodeFile(File(dir, "mask.png").path)!!
            val px = IntArray(visMask.width * visMask.height)
            visMask.getPixels(px, 0, visMask.width, 0, 0, visMask.width, visMask.height)
            for (i in px.indices) px[i] = (px[i] and 0xFF) shl 24
            val mask = Bitmap.createBitmap(visMask.width, visMask.height, Bitmap.Config.ALPHA_8)
            mask.setPixels(px, 0, mask.width, 0, 0, mask.width, mask.height)
            visMask.recycle()
            val st = EditorState(src, mask)
            val j = JSONObject(File(dir, "state.json").readText())
            st.scale = j.optDouble("scale", 1.0).toFloat()
            st.offset = Offset(j.optDouble("ox", 0.0).toFloat(), j.optDouble("oy", 0.0).toFloat())
            st.rotation = j.optDouble("rotation", 0.0).toFloat()
            st.outline = j.optBoolean("outline", true)
            st.outlineWidth = j.optDouble("outlineWidth", 10.0).toFloat()
            st.outlineColor = j.optInt("outlineColor", Color.WHITE)
            st.emojis = j.optString("emojis", "😂")
            val arr = j.optJSONArray("texts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                st.texts += TextLayer(
                    text = t.optString("text"), font = t.optString("font", Fonts.DEFAULT),
                    size = t.optDouble("size", 64.0).toFloat(), color = t.optInt("color", Color.WHITE),
                    stroke = t.optInt("stroke", Color.BLACK), strokeRatio = t.optDouble("strokeRatio", 0.14).toFloat(),
                    x = t.optDouble("x", 256.0).toFloat(), y = t.optDouble("y", 70.0).toFloat(),
                    rotation = t.optDouble("rotation", 0.0).toFloat(), allCaps = t.optBoolean("allCaps", true),
                )
            }
            st
        }.getOrNull()

        /** Figurinha só de texto: fundo transparente, sem foto. */
        fun blank(): EditorState {
            val src = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val mask = Bitmap.createBitmap(512, 512, Bitmap.Config.ALPHA_8) // tudo transparente
            return EditorState(src, mask).apply {
                outline = false
                texts += TextLayer(text = "SEU TEXTO", y = 256f, size = 90f)
                selected = 0
            }
        }

        fun mutableOpts() = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    }
}
