package com.trevisolli.conselheirosticker.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Desenha a figurinha no quadro 512×512. A prévia e o arquivo final usam o mesmo código,
 * então o que se vê é o que vai para o WhatsApp.
 */
object Render {
    const val SIZE = 512f
    /** Margem de segurança que o WhatsApp recomenda (16 px em volta). */
    const val MARGIN = 16f

    fun draw(
        canvas: Canvas, sizePx: Float, st: EditorState, fonts: Fonts, showSelection: Boolean,
        image: Bitmap? = st.cutout(),
        phase: Float = st.effectPhase,
    ) {
        canvas.save()
        canvas.scale(sizePx / SIZE, sizePx / SIZE)
        val fx = st.effects
        val m = Effects.motion(fx, phase)
        val box = effectBox(st, image, m)
        if (fx.isNotEmpty()) Effects.drawBehind(canvas, fx, box, phase)
        image?.let { img ->
            val fit = (SIZE - MARGIN * 2) / max(img.width, img.height)
            canvas.save()
            applyMotion(canvas, m, m.imageScale)
            canvas.translate(SIZE / 2 + st.offset.x, SIZE / 2 + st.offset.y)
            canvas.rotate(st.rotation)
            canvas.scale(fit * st.scale, fit * st.scale)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = Effects.imageFilter(fx, phase)
            }
            val g = Effects.glitch(fx, phase)
            if (g == null) {
                canvas.drawBitmap(img, -img.width / 2f, -img.height / 2f, paint)
            } else {
                drawGlitched(canvas, img, g, 1f / (fit * st.scale * m.imageScale))
            }
            canvas.restore()
        }
        canvas.save()
        applyMotion(canvas, m, m.textScale)
        st.texts.forEachIndexed { i, t ->
            drawText(canvas, t, fonts)
            if (showSelection && i == st.selected) drawSelection(canvas, t, fonts)
        }
        canvas.restore()
        if (fx.isNotEmpty()) {
            val emoji = com.trevisolli.conselheirosticker.data.Emojis.parse(st.emojis).first()
            Effects.drawFront(canvas, fx, box, phase, emoji)
        }
        canvas.restore()
    }

    /**
     * Glitch: cópia vermelha e cópia verde-azul deslocadas (somadas dão a imagem de volta, com
     * as bordas separadas) e faixas horizontais escorregando. [px] = 1 px da figurinha em px da imagem.
     */
    private fun drawGlitched(canvas: Canvas, img: Bitmap, g: Effects.Glitch, px: Float) {
        val left = -img.width / 2f
        val top = -img.height / 2f
        val split = g.split * px
        val bounds = RectF(left - split, top, left + img.width + split, top + img.height)
        val layer = canvas.saveLayer(bounds, null)
        val red = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
            )))
        }
        val cyan = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
            )))
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        canvas.drawBitmap(img, left - split, top, red)
        canvas.drawBitmap(img, left + split, top, cyan)
        canvas.restoreToCount(layer)
        // Faixas que escorregam para o lado.
        val plain = Paint(Paint.FILTER_BITMAP_FLAG)
        g.bands.forEach { (a, b, dx) ->
            val y0 = top + img.height * a
            val y1 = top + img.height * b
            canvas.save()
            canvas.clipRect(left - 40f * px, y0, left + img.width + 40f * px, y1)
            canvas.drawBitmap(img, left + dx * px, top, plain)
            canvas.restore()
        }
    }

    /** Tremor, pulso e encolhimento do fogo, em volta do centro do quadro. */
    private fun applyMotion(canvas: Canvas, m: Effects.Motion, scale: Float) {
        canvas.translate(SIZE / 2 + m.dx, SIZE / 2 + m.dy)
        canvas.rotate(m.rotation)
        canvas.scale(scale, scale)
        canvas.translate(-SIZE / 2, -SIZE / 2)
    }

    /** Onde a figura está no quadro (já com o movimento), para os efeitos ficarem em volta dela. */
    private fun effectBox(st: EditorState, image: Bitmap?, m: Effects.Motion): RectF {
        if (image == null) return RectF(96f, 96f, 416f, 416f)
        val fit = (SIZE - MARGIN * 2) / max(image.width, image.height) * st.scale * m.imageScale
        val rad = Math.toRadians(st.rotation.toDouble())
        val w = image.width * fit
        val h = image.height * fit
        val hw = (kotlin.math.abs(w * cos(rad)) + kotlin.math.abs(h * sin(rad))).toFloat() / 2
        val hh = (kotlin.math.abs(w * sin(rad)) + kotlin.math.abs(h * cos(rad))).toFloat() / 2
        val cx = SIZE / 2 + st.offset.x * m.imageScale + m.dx
        val cy = SIZE / 2 + st.offset.y * m.imageScale + m.dy
        return RectF(
            (cx - hw).coerceAtLeast(8f), (cy - hh).coerceAtLeast(8f),
            (cx + hw).coerceAtMost(SIZE - 8f), (cy + hh).coerceAtMost(SIZE - 8f),
        )
    }

    fun export(st: EditorState, fonts: Fonts, image: Bitmap? = st.cutout(), phase: Float = 0f): Bitmap {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), SIZE, st, fonts, showSelection = false, image = image, phase = phase)
        return bmp
    }

    private fun paints(t: TextLayer, fonts: Fonts): Pair<Paint, Paint> {
        val tf = fonts.typeface(t.font)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = t.size; color = t.color; textAlign = Paint.Align.CENTER
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = t.size * t.strokeRatio
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 10f
            color = t.stroke
        }
        return fill to stroke
    }

    private fun lines(t: TextLayer) = t.shown.split('\n').ifEmpty { listOf("") }

    fun drawText(canvas: Canvas, t: TextLayer, fonts: Fonts) {
        if (t.text.isBlank()) return
        val (fill, stroke) = paints(t, fonts)
        val ls = lines(t)
        val lh = t.size * 1.08f
        val fm = fill.fontMetrics
        // Centraliza o bloco de linhas no ponto (x, y).
        val top = -lh * ls.size / 2f
        canvas.save()
        canvas.translate(t.x, t.y)
        canvas.rotate(t.rotation)
        ls.forEachIndexed { i, line ->
            val baseline = top + lh * i + (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent
            if (t.strokeRatio > 0f) canvas.drawText(line, 0f, baseline, stroke)
            canvas.drawText(line, 0f, baseline, fill)
        }
        canvas.restore()
    }

    /** Caixa do texto sem giro, relativa ao centro (x, y). */
    fun textBox(t: TextLayer, fonts: Fonts): RectF {
        val (fill, _) = paints(t, fonts)
        val w = lines(t).maxOf { fill.measureText(it) } + t.size * t.strokeRatio + 12f
        val h = t.size * 1.08f * lines(t).size + 12f
        return RectF(-w / 2, -h / 2, w / 2, h / 2)
    }

    private fun drawSelection(canvas: Canvas, t: TextLayer, fonts: Fonts) {
        val box = textBox(t, fonts)
        canvas.save()
        canvas.translate(t.x, t.y)
        canvas.rotate(t.rotation)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(230, 108, 60, 233)
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        canvas.drawRoundRect(box, 10f, 10f, p)
        canvas.restore()
    }

    /** Índice do texto sob o ponto (coordenadas 512), do de cima para o de baixo. */
    fun hitText(st: EditorState, fonts: Fonts, x: Float, y: Float): Int {
        for (i in st.texts.indices.reversed()) {
            val t = st.texts[i]
            if (t.text.isBlank()) continue
            val rad = Math.toRadians(-t.rotation.toDouble())
            val dx = x - t.x
            val dy = y - t.y
            val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
            val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
            val box = textBox(t, fonts).apply { inset(-16f, -16f) }
            if (box.contains(lx, ly)) return i
        }
        return -1
    }
}
