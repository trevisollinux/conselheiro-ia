package com.trevisolli.agendawidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.WidgetTheme

/** Fundo do widget conforme o tema escolhido: paisagem, cor lisa ou papel. */
object Backgrounds {

    fun render(settings: Settings, width: Int, height: Int, seed: Long, hour: Int, cornerPx: Float): LandscapeRenderer.Result {
        val opacity = settings.widgetOpacity / 100f
        return when (val theme = settings.widgetTheme) {
            WidgetTheme.LANDSCAPE -> {
                val fixed = LandscapeArt.Scene.entries.firstOrNull { it.name == settings.sceneChoice }
                LandscapeRenderer.render(width, height, seed, hour, opacity, cornerPx, fixed ?: LandscapeArt.sceneFor(seed))
            }
            else -> paper(theme, width.coerceAtLeast(64), height.coerceAtLeast(64), opacity, cornerPx)
        }
    }

    private fun paper(theme: WidgetTheme, w: Int, h: Int, opacity: Float, corner: Float): LandscapeRenderer.Result {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Path.Direction.CW) })
        c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (opacity.coerceIn(0f, 1f) * 255).toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val unit = w / 360f
        when (theme) {
            WidgetTheme.LIGHT -> {
                p.color = 0xFFFAFAF7.toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
            WidgetTheme.DARK -> {
                p.color = 0xFF1E1F22.toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
            WidgetTheme.NOTEBOOK -> {
                // Bloco amarelo pautado: faixa no topo, linhas azuis e margem vermelha.
                p.color = 0xFFFFF4B0.toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.color = 0xFFEAD77A.toInt()
                c.drawRect(0f, 0f, w.toFloat(), 10 * unit, p)
                p.color = 0x5574A9D6
                p.strokeWidth = 1.2f * unit
                var y = 64 * unit
                while (y < h) {
                    c.drawLine(0f, y, w.toFloat(), y, p)
                    y += 26 * unit
                }
                p.color = 0x88E57373.toInt()
                c.drawLine(30 * unit, 10 * unit, 30 * unit, h.toFloat(), p)
            }
            else -> {
                // Post-it verde-menta com dobra no canto.
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFDDF5E3.toInt(), 0xFFC9EBD2.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                val fold = 34 * unit
                p.color = 0xFFB2D9BD.toInt()
                c.drawPath(
                    Path().apply {
                        moveTo(w - fold, h.toFloat())
                        lineTo(w.toFloat(), h - fold)
                        lineTo(w - fold, h - fold)
                        close()
                    },
                    p,
                )
            }
        }
        c.restore()
        return LandscapeRenderer.Result(bmp, dark = theme == WidgetTheme.DARK, label = theme.label)
    }
}
