package com.trevisolli.conselheirosticker.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
    ) {
        canvas.save()
        canvas.scale(sizePx / SIZE, sizePx / SIZE)
        image?.let { img ->
            val fit = (SIZE - MARGIN * 2) / max(img.width, img.height)
            canvas.save()
            canvas.translate(SIZE / 2 + st.offset.x, SIZE / 2 + st.offset.y)
            canvas.rotate(st.rotation)
            canvas.scale(fit * st.scale, fit * st.scale)
            canvas.drawBitmap(img, -img.width / 2f, -img.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
        }
        st.texts.forEachIndexed { i, t ->
            drawText(canvas, t, fonts)
            if (showSelection && i == st.selected) drawSelection(canvas, t, fonts)
        }
        canvas.restore()
    }

    fun export(st: EditorState, fonts: Fonts, image: Bitmap? = st.cutout()): Bitmap {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), SIZE, st, fonts, showSelection = false, image = image)
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
