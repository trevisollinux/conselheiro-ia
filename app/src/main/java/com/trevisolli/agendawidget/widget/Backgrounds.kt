package com.trevisolli.agendawidget.widget

import android.content.Context
import android.content.res.Configuration
import com.trevisolli.agendawidget.ui.isDarkTheme
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.trevisolli.agendawidget.data.ArtSource
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.WeatherSource
import com.trevisolli.agendawidget.data.WidgetTheme

/** Fundo do widget conforme o tema escolhido: paisagem, cor lisa ou papel. */
object Backgrounds {

    /** Semente da cena: muda no intervalo escolhido (30 min, 1 h, 3 h…). */
    fun seed(settings: Settings): Long =
        System.currentTimeMillis() / (settings.rotateMinutes.coerceAtLeast(30) * 60_000L) + settings.sceneNonce * 7919

    fun weatherFor(context: Context): LandscapeArt.Weather = when (WeatherSource.load(context)?.mood) {
        WeatherSource.Mood.RAIN -> LandscapeArt.Weather.RAIN
        WeatherSource.Mood.STORM -> LandscapeArt.Weather.STORM
        WeatherSource.Mood.CLOUDY -> LandscapeArt.Weather.CLOUDY
        WeatherSource.Mood.FOG -> LandscapeArt.Weather.FOG
        WeatherSource.Mood.HOT -> LandscapeArt.Weather.HOT
        else -> LandscapeArt.Weather.CLEAR
    }

    fun render(context: Context, width: Int, height: Int, hour: Int, cornerPx: Float, seedOverride: Long? = null): LandscapeRenderer.Result {
        val settings = Settings(context)
        val opacity = settings.widgetOpacity / 100f
        val seed = seedOverride ?: seed(settings)
        val theme = settings.widgetTheme
        val night = darkMode(context, settings)
        if (theme == WidgetTheme.ART) {
            artwork(context, settings, width.coerceAtLeast(64), height.coerceAtLeast(64), opacity, cornerPx, night)?.let { return it }
        }
        return when (theme) {
            WidgetTheme.LANDSCAPE, WidgetTheme.ART -> {
                val fixed = LandscapeArt.Scene.entries.firstOrNull { it.name == settings.sceneChoice }
                LandscapeRenderer.render(
                    width, height, seed, hour, opacity, cornerPx,
                    fixed ?: LandscapeArt.sceneFor(seed), weatherFor(context), night,
                )
            }
            else -> paper(theme, width.coerceAtLeast(64), height.coerceAtLeast(64), opacity, cornerPx, night)
        }
    }

    /** Obra baixada, recortada para preencher o widget, com véu para o texto. Null se ainda não há obra. */
    /** O widget segue o tema do app (claro, escuro ou o do celular). */
    fun darkMode(context: Context, settings: Settings): Boolean {
        val systemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return isDarkTheme(settings.appTheme, systemNight)
    }

    private fun artwork(
        context: Context,
        settings: Settings,
        w: Int,
        h: Int,
        opacity: Float,
        corner: Float,
        night: Boolean,
    ): LandscapeRenderer.Result? {
        val file = ArtSource.imageFile(context)
        if (!file.exists()) return null
        val src = BitmapFactory.decodeFile(file.path) ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Path.Direction.CW) })
        c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (opacity.coerceIn(0f, 1f) * 255).toInt())
        // Recorte central (como "center crop").
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val sw = w / scale
        val sh = h / scale
        val left = (src.width - sw) / 2
        val top = (src.height - sh) / 2
        c.drawBitmap(src, Rect(left.toInt(), top.toInt(), (left + sw).toInt(), (top + sh).toInt()), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val dark = night || luminance(src) < 0.55f
        val veil = Paint().apply { color = if (night) 0x8C000000.toInt() else if (dark) 0x40000000 else 0x4DFFFFFF }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), veil)
        c.restore()
        src.recycle()
        return LandscapeRenderer.Result(bmp, dark = dark, label = settings.artCaption)
    }

    /** Brilho médio (0..1) por amostragem. */
    private fun luminance(b: Bitmap): Float {
        var sum = 0.0
        var n = 0
        val stepX = (b.width / 24).coerceAtLeast(1)
        val stepY = (b.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val px = b.getPixel(x, y)
                sum += (0.299 * (px shr 16 and 0xFF) + 0.587 * (px shr 8 and 0xFF) + 0.114 * (px and 0xFF)) / 255.0
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0.5f else (sum / n).toFloat()
    }

    private fun paper(theme: WidgetTheme, w: Int, h: Int, opacity: Float, corner: Float, night: Boolean): LandscapeRenderer.Result {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Path.Direction.CW) })
        c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (opacity.coerceIn(0f, 1f) * 255).toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val unit = w / 360f
        when (theme) {
            WidgetTheme.LIGHT, WidgetTheme.DARK -> {
                // Creme (papel quente) ou Noir (grafite com brilho dourado no alto), com filete dourado.
                val noir = night || theme == WidgetTheme.DARK
                p.shader = if (noir) {
                    LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), 0xFF2A2226.toInt(), 0xFF141114.toInt(), Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFFFFBF4.toInt(), 0xFFF6EEE1.toInt(), Shader.TileMode.CLAMP)
                }
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.2f * unit
                p.color = if (noir) 0x55E8C877 else 0x55C9A24A
                val inset = 5 * unit
                c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), corner - inset, corner - inset, p)
                p.style = Paint.Style.FILL
            }
            WidgetTheme.NOTEBOOK -> {
                // Bloco amarelo pautado: faixa no topo, linhas azuis e margem vermelha.
                p.color = if (night) 0xFF26241C.toInt() else 0xFFFFF4B0.toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.color = if (night) 0xFF4A4428.toInt() else 0xFFEAD77A.toInt()
                c.drawRect(0f, 0f, w.toFloat(), 10 * unit, p)
                p.color = if (night) 0x3374A9D6 else 0x5574A9D6
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
                p.shader = if (night) {
                    LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1F2E25.toInt(), 0xFF18241D.toInt(), Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFDDF5E3.toInt(), 0xFFC9EBD2.toInt(), Shader.TileMode.CLAMP)
                }
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                val fold = 34 * unit
                p.color = if (night) 0xFF2C4235.toInt() else 0xFFB2D9BD.toInt()
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
        return LandscapeRenderer.Result(bmp, dark = night || theme == WidgetTheme.DARK, label = theme.label)
    }
}
