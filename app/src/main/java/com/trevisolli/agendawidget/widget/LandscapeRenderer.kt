package com.trevisolli.agendawidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/** Renderiza [LandscapeArt] num Bitmap do Android, com cantos arredondados e transparência. */
object LandscapeRenderer {

    data class Result(val bitmap: Bitmap, val dark: Boolean, val label: String)

    fun render(
        width: Int,
        height: Int,
        seed: Long,
        hour: Int,
        opacity: Float,
        cornerPx: Float,
        scene: LandscapeArt.Scene = LandscapeArt.sceneFor(seed),
        weather: LandscapeArt.Weather = LandscapeArt.Weather.CLEAR,
        darkMode: Boolean = false,
    ): Result {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cornerPx, cornerPx, Path.Direction.CW) })
        canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (opacity.coerceIn(0f, 1f) * 255).toInt())
        // No tema escuro a cena ganha véu escuro e o texto fica claro.
        val dark = LandscapeArt.draw(AndroidPainter(canvas, w, h), seed, hour, scene, weather) || darkMode
        // Véu suave atrás do texto (mais forte em cima, onde ficam título e lista).
        val veil = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    withAlpha(veil, if (darkMode) 170 else if (dark) 90 else 120),
                    withAlpha(veil, if (darkMode) 120 else if (dark) 40 else 60),
                ),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        canvas.restore()
        val label = listOf(scene.label, LandscapeArt.skyFor(hour).name, weather.label).filter { it.isNotBlank() }.joinToString(" · ")
        return Result(bmp, dark, label)
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    private class AndroidPainter(private val c: Canvas, override val width: Int, override val height: Int) : LandscapeArt.Painter {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun solid(color: Int): Paint = paint.apply {
            reset()
            isAntiAlias = true
            style = Paint.Style.FILL
            this.color = color
        }

        private fun path(s: LandscapeArt.Shape) = Path().apply {
            s.ops.forEach { o ->
                when (o[0].toInt()) {
                    0 -> moveTo(o[1], o[2])
                    1 -> lineTo(o[1], o[2])
                    2 -> quadTo(o[1], o[2], o[3], o[4])
                    else -> close()
                }
            }
        }

        override fun verticalGradient(top: Float, bottom: Float, colors: IntArray, stops: FloatArray) {
            val p = solid(0)
            p.shader = LinearGradient(0f, top, 0f, bottom, colors, stops, Shader.TileMode.CLAMP)
            c.drawRect(0f, top, width.toFloat(), bottom, p)
        }

        override fun glow(cx: Float, cy: Float, r: Float, inner: Int, outer: Int) {
            val p = solid(0)
            p.shader = RadialGradient(cx, cy, r, inner, outer, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }

        override fun circle(cx: Float, cy: Float, r: Float, color: Int) = c.drawCircle(cx, cy, r, solid(color))

        override fun rect(l: Float, t: Float, r: Float, b: Float, color: Int) = c.drawRect(l, t, r, b, solid(color))

        override fun fill(shape: LandscapeArt.Shape, color: Int) = c.drawPath(path(shape), solid(color))

        override fun fillGradient(shape: LandscapeArt.Shape, top: Float, bottom: Float, topColor: Int, bottomColor: Int) {
            val p = solid(0)
            p.shader = LinearGradient(0f, top, 0f, bottom, topColor, bottomColor, Shader.TileMode.CLAMP)
            c.drawPath(path(shape), p)
        }

        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
            val p = solid(color)
            p.style = Paint.Style.STROKE
            p.strokeWidth = width
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(x1, y1, x2, y2, p)
        }
    }
}
