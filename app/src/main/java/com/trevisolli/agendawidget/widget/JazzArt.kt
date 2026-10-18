package com.trevisolli.agendawidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.time.LocalDate
import kotlin.random.Random

/**
 * Fundo generativo do widget, inspirado em capas de discos de jazz.
 * A paleta segue a hora do dia e o desenho muda a cada atualização (semente).
 */
object JazzArt {

    data class Palette(val name: String, val bg1: Int, val bg2: Int, val accent: Int, val accent2: Int, val ink: Int)

    private val MORNING = listOf(
        Palette("Café com Coltrane", 0xFF2E1F14.toInt(), 0xFF7A4E2D.toInt(), 0xFFE0B354.toInt(), 0xFFF2E3C6.toInt(), 0xFF140D08.toInt()),
        Palette("Bossa ao sol", 0xFF0F4C5C.toInt(), 0xFF2A7F62.toInt(), 0xFFF4A259.toInt(), 0xFFF6E7C1.toInt(), 0xFF072A33.toInt()),
    )
    private val AFTERNOON = listOf(
        Palette("Blue Note", 0xFF0B2545.toInt(), 0xFF13315C.toInt(), 0xFFE2B33C.toInt(), 0xFFEEE6D3.toInt(), 0xFF050F1F.toInt()),
        Palette("Kind of Blue", 0xFF102A43.toInt(), 0xFF2F6690.toInt(), 0xFF9BC1E0.toInt(), 0xFFF0F4F8.toInt(), 0xFF061523.toInt()),
        Palette("Sax ao entardecer", 0xFF5C1A1B.toInt(), 0xFFB5651D.toInt(), 0xFFF2C14E.toInt(), 0xFFF7E8D0.toInt(), 0xFF2B0B0C.toInt()),
    )
    private val NIGHT = listOf(
        Palette("Clube enfumaçado", 0xFF1B1035.toInt(), 0xFF3A1C5C.toInt(), 0xFFE94F64.toInt(), 0xFFF7C548.toInt(), 0xFF0B0618.toInt()),
        Palette("Piano da madrugada", 0xFF0D0D0D.toInt(), 0xFF262626.toInt(), 0xFFC9A227.toInt(), 0xFFEDE6D6.toInt(), 0xFF000000.toInt()),
        Palette("Neon da Rua 52", 0xFF0A1931.toInt(), 0xFF1A1A40.toInt(), 0xFF00C2C7.toInt(), 0xFFFF5DA2.toInt(), 0xFF040A14.toInt()),
    )

    fun palette(hour: Int, seed: Long): Palette {
        val list = when (hour) {
            in 5..11 -> MORNING
            in 12..17 -> AFTERNOON
            else -> NIGHT
        }
        return list[Random(seed).nextInt(list.size)]
    }

    /** [opacity] de 0.0 (transparente) a 1.0 (sólido). */
    fun render(width: Int, height: Int, seed: Long, hour: Int, opacity: Float, cornerPx: Float): Bitmap {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(seed)
        val p = palette(hour, seed)

        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cornerPx, cornerPx, Path.Direction.CW) })
        c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (opacity.coerceIn(0f, 1f) * 255).toInt())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p.bg1, p.bg2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        when (rnd.nextInt(4)) {
            0 -> vinyl(c, w, h, p, rnd)
            1 -> blocks(c, w, h, p, rnd)
            2 -> piano(c, w, h, p, rnd)
            else -> smoke(c, w, h, p, rnd)
        }
        notes(c, w, h, p, rnd)

        // Sombra à esquerda para o texto continuar legível sobre qualquer desenho.
        paint.shader = LinearGradient(0f, 0f, w * 0.8f, 0f, 0xA6000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        c.restore()
        return bmp
    }

    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun vinyl(c: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val cx = w * (0.78f + rnd.nextFloat() * 0.12f)
        val cy = h * (0.35f + rnd.nextFloat() * 0.4f)
        val r = h * (0.55f + rnd.nextFloat() * 0.3f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ink }
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = alpha(p.accent2, 38)
        var gr = r * 0.4f
        while (gr < r * 0.97f) {
            c.drawCircle(cx, cy, gr, paint)
            gr += 5f + rnd.nextFloat() * 4f
        }
        paint.style = Paint.Style.FILL
        paint.color = p.accent
        c.drawCircle(cx, cy, r * 0.32f, paint)
        paint.color = p.bg1
        c.drawCircle(cx, cy, r * 0.04f, paint)
        // Reflexo
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.06f
        paint.color = alpha(p.accent2, 30)
        c.drawArc(RectF(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f), 200f, 50f, false, paint)
    }

    private fun blocks(c: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var x = w * 0.45f
        while (x < w) {
            val bw = w * (0.04f + rnd.nextFloat() * 0.12f)
            paint.color = alpha(if (rnd.nextBoolean()) p.accent else p.accent2, 150 + rnd.nextInt(90))
            val top = h * rnd.nextFloat() * 0.5f
            c.drawRect(x, top, x + bw, h.toFloat(), paint)
            x += bw + w * (0.01f + rnd.nextFloat() * 0.05f)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.04f
        paint.color = alpha(p.accent2, 200)
        c.drawCircle(w * 0.7f, h * 0.3f, h * 0.22f, paint)
    }

    private fun piano(c: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val top = h * (0.62f + rnd.nextFloat() * 0.12f)
        val keyW = w / 18f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var i = 0
        var x = w * 0.3f
        while (x < w) {
            paint.color = alpha(p.accent2, 225)
            c.drawRect(x + 1.5f, top, x + keyW - 1.5f, h.toFloat(), paint)
            x += keyW
            i++
        }
        x = w * 0.3f
        i = 0
        while (x < w) {
            if (i % 7 != 2 && i % 7 != 6) {
                paint.color = p.ink
                c.drawRect(x + keyW * 0.68f, top, x + keyW * 1.32f, top + (h - top) * 0.6f, paint)
            }
            x += keyW
            i++
        }
        paint.shader = RadialGradient(w * 0.85f, 0f, h * 0.9f, alpha(p.accent, 120), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), top, paint)
    }

    private fun smoke(c: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(w * 0.8f, h * 0.1f, h * 1.1f, alpha(p.accent, 110), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        repeat(5) {
            paint.strokeWidth = 3f + rnd.nextFloat() * 9f
            paint.color = alpha(p.accent2, 35 + rnd.nextInt(45))
            val path = Path()
            var x = w * (0.45f + rnd.nextFloat() * 0.5f)
            var y = h.toFloat()
            path.moveTo(x, y)
            while (y > 0) {
                val nx = x + (rnd.nextFloat() - 0.5f) * w * 0.25f
                val ny = y - h * (0.2f + rnd.nextFloat() * 0.2f)
                path.quadTo(x + (rnd.nextFloat() - 0.5f) * w * 0.3f, (y + ny) / 2, nx, ny)
                x = nx
                y = ny
            }
            c.drawPath(path, paint)
        }
    }

    private fun notes(c: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        val glyphs = listOf("♪", "♫", "♩", "♬")
        repeat(3 + rnd.nextInt(4)) {
            paint.textSize = h * (0.08f + rnd.nextFloat() * 0.12f)
            paint.color = alpha(if (rnd.nextBoolean()) p.accent else p.accent2, 70 + rnd.nextInt(90))
            c.drawText(glyphs[rnd.nextInt(glyphs.size)], w * (0.5f + rnd.nextFloat() * 0.45f), h * (0.15f + rnd.nextFloat() * 0.8f), paint)
        }
    }

    /** Uma faixa por dia, para a linha "Trilha do dia". */
    private val TRACKS = listOf(
        "Take Five — Dave Brubeck", "So What — Miles Davis", "Blue in Green — Bill Evans", "My Favorite Things — John Coltrane",
        "Round Midnight — Thelonious Monk", "Moanin' — Art Blakey", "Song for My Father — Horace Silver",
        "Desafinado — Stan Getz & João Gilberto", "Autumn Leaves — Cannonball Adderley", "Footprints — Wayne Shorter",
        "Cantaloupe Island — Herbie Hancock", "A Night in Tunisia — Dizzy Gillespie", "Summertime — Ella & Louis",
        "In a Sentimental Mood — Duke Ellington & Coltrane", "Waltz for Debby — Bill Evans", "Blue Train — John Coltrane",
        "Corcovado — Tom Jobim", "Strange Fruit — Billie Holiday", "Maiden Voyage — Herbie Hancock", "Naima — John Coltrane",
        "Watermelon Man — Herbie Hancock", "Freddie Freeloader — Miles Davis", "St. Thomas — Sonny Rollins",
        "Mercy, Mercy, Mercy — Cannonball Adderley", "Chega de Saudade — João Gilberto", "Lush Life — Johnny Hartman",
        "Peace — Horace Silver", "Giant Steps — John Coltrane", "Feeling Good — Nina Simone", "Água de Beber — Astrud Gilberto",
    )

    fun trackOfTheDay(date: LocalDate = LocalDate.now()): String = TRACKS[date.dayOfYear % TRACKS.size]
}
