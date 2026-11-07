package com.trevisolli.conselheirosticker.editor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Efeitos que deixam uma figurinha parada animada. Tudo é desenhado por código em função da fase
 * t (0..1); as frequências são inteiras, então o último quadro emenda com o primeiro sem pulo.
 */
enum class Effect(val label: String) {
    Fire("🔥 Fogo"),
    PixelFire("👾 Fogo 16 bits"),
    Lightning("⚡ Raios"),
    Shake("🫨 Tremor"),
    Zoom("🔍 Zoom dramático"),
    Glitch("📺 Glitch"),
    Rainbow("🌈 Arco-íris"),
    Sparkles("✨ Brilhos"),
    Hearts("❤️ Corações"),
    Dizzy("💫 Tontura"),
    Snow("❄️ Neve"),
    Confetti("🎉 Confete"),
    EmojiRain("😂 Chuva de emoji"),
    Pulse("💓 Pulsar"),
}

object Effects {
    /** Quadros da animação feita a partir de uma figurinha parada (16 × 70 ms ≈ 1,1 s em loop). */
    const val STILL_FRAMES = 16
    const val STILL_FRAME_MS = 70

    private const val TAU = (2 * PI).toFloat()
    private fun wave(t: Float, freq: Int, seed: Float) = sin(TAU * (freq * t + seed))

    /** Pseudoaleatório fixo por índice (o mesmo em todos os quadros). */
    private fun rnd(i: Int, salt: Int): Float {
        val x = sin((i * 12.9898f + salt * 78.233f)) * 43758.547f
        return x - kotlin.math.floor(x)
    }

    /** Deslocamento, giro e escala da figura e dos textos neste quadro. */
    class Motion(val dx: Float, val dy: Float, val rotation: Float, val imageScale: Float, val textScale: Float)

    fun motion(effects: Set<Effect>, t: Float): Motion {
        var dx = 0f; var dy = 0f; var rot = 0f; var pulse = 1f
        if (Effect.Shake in effects) {
            dx = 7f * wave(t, 4, 0f)
            dy = 5f * wave(t, 6, 0.25f)
            rot = 3f * wave(t, 3, 0.1f)
        }
        if (Effect.Pulse in effects) pulse = 1f + 0.07f * wave(t, 1, 0f)
        if (Effect.Zoom in effects) pulse *= dramaticZoom(t)
        // Com fogo, a figura (não os textos) encolhe um pouco para as chamas aparecerem em volta.
        val fire = if (Effect.Fire in effects || Effect.PixelFire in effects) 0.82f else 1f
        return Motion(dx, dy, rot, pulse * fire, pulse)
    }

    /** Zoom de meme: chega rápido, segura, e volta no fim do loop. */
    private fun dramaticZoom(t: Float): Float {
        val k = when {
            t < 0.18f -> { val x = t / 0.18f; 1 - (1 - x) * (1 - x) }
            t < 0.82f -> 1f
            else -> 1 - (t - 0.82f) / 0.18f
        }
        return 1f + 0.38f * k
    }

    /** Filtro de cor da figura neste quadro (arco-íris: o matiz dá uma volta inteira). */
    fun imageFilter(effects: Set<Effect>, t: Float): ColorFilter? {
        if (Effect.Rainbow !in effects) return null
        return ColorMatrixColorFilter(hueMatrix(360f * t))
    }

    private fun hueMatrix(degrees: Float): ColorMatrix {
        val a = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(a).toFloat()
        val sin = kotlin.math.sin(a).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return ColorMatrix(floatArrayOf(
            lr + cos * (1 - lr) + sin * (-lr), lg + cos * (-lg) + sin * (-lg), lb + cos * (-lb) + sin * (1 - lb), 0f, 0f,
            lr + cos * (-lr) + sin * 0.143f, lg + cos * (1 - lg) + sin * 0.140f, lb + cos * (-lb) + sin * (-0.283f), 0f, 0f,
            lr + cos * (-lr) + sin * (-(1 - lr)), lg + cos * (-lg) + sin * lg, lb + cos * (1 - lb) + sin * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /**
     * Glitch neste quadro: deslocamento (px da figurinha) das cópias vermelha e ciano, e faixas que
     * escorregam para o lado. Null nos quadros "limpos".
     */
    class Glitch(val split: Float, val bands: List<Triple<Float, Float, Float>>)

    fun glitch(effects: Set<Effect>, t: Float): Glitch? {
        if (Effect.Glitch !in effects) return null
        val f = (t * STILL_FRAMES).toInt()
        if (rnd(f, 77) > 0.5f) return null
        val bands = (0 until 3).map { k ->
            val top = rnd(f * 3 + k, 78)
            Triple(top, (top + 0.04f + 0.08f * rnd(f * 3 + k, 79)).coerceAtMost(1f), (rnd(f * 3 + k, 80) - 0.5f) * 40f)
        }
        return Glitch(6f + 8f * rnd(f, 81), bands)
    }

    /** Camada de trás (fica atrás da figura): brilho e chamas grandes. */
    fun drawBehind(c: Canvas, effects: Set<Effect>, box: RectF, t: Float) {
        if (Effect.Fire in effects) { glow(c, box, t); fire(c, box, t, front = false) }
        if (Effect.PixelFire in effects) pixelFire(c, box, t, front = false)
    }

    /** Camada da frente. [emoji] é o emoji da figurinha (para a chuva de emoji). */
    fun drawFront(c: Canvas, effects: Set<Effect>, box: RectF, t: Float, emoji: String = "😂") {
        if (Effect.Fire in effects) { fire(c, box, t, front = true); embers(c, box, t) }
        if (Effect.PixelFire in effects) pixelFire(c, box, t, front = true)
        if (Effect.Lightning in effects) lightning(c, box, t)
        if (Effect.Sparkles in effects) sparkles(c, box, t)
        if (Effect.Hearts in effects) hearts(c, box, t)
        if (Effect.Dizzy in effects) dizzy(c, box, t)
        if (Effect.Snow in effects) snow(c, t)
        if (Effect.Confetti in effects) confetti(c, t)
        if (Effect.EmojiRain in effects) emojiRain(c, t, emoji)
    }

    // ---------------------------------------------------------------- Fogo

    private fun fire(c: Canvas, box: RectF, t: Float, front: Boolean) {
        val w = box.width().coerceAtLeast(120f)
        val h = box.height().coerceAtLeast(120f)
        val base = minOf(box.bottom + 4f, 508f)
        val count = (w / 44f).toInt().coerceIn(4, 10)
        val step = w / count
        if (!front) {
            // Atrás da figura: fileira alta na base e chamas subindo pelos dois lados.
            for (i in 0 until count) flame(c, box.left + step * (i + 0.5f), base, step * 1.35f, h * 0.5f, t, i, big = true)
            for (k in 0 until 4) {
                val y = base - h * 0.62f * k / 4f
                val fh = h * (0.36f - 0.05f * k)
                flame(c, box.left - step * 0.25f, y, step, fh, t, 20 + k, big = true)
                flame(c, box.right + step * 0.25f, y, step, fh, t, 30 + k, big = true)
            }
        } else {
            // Na frente: chamas mais baixas cobrindo a base, para a figura parecer dentro do fogo.
            for (i in 0..count) flame(c, box.left + step * i, base + 6f, step * 1.05f, h * 0.26f, t, 40 + i, big = false)
            for (k in 0 until 2) {
                val y = base - h * 0.22f * (k + 1)
                flame(c, box.left + step * 0.1f, y, step * 0.7f, h * 0.16f, t, 60 + k, big = false)
                flame(c, box.right - step * 0.1f, y, step * 0.7f, h * 0.16f, t, 70 + k, big = false)
            }
        }
    }

    private fun flame(c: Canvas, x: Float, base: Float, width: Float, height: Float, t: Float, i: Int, big: Boolean) {
        val s1 = rnd(i, 1); val s2 = rnd(i, 2); val s3 = rnd(i, 3)
        val h = height * (0.78f + 0.16f * wave(t, 2, s1) + 0.06f * wave(t, 5, s2))
        val tipX = x + width * 0.22f * wave(t, 1 + (i % 2), s3)
        val half = width / 2
        fun shape(scale: Float): Path = Path().apply {
            val hw = half * scale
            val hh = h * scale
            moveTo(x - hw, base)
            cubicTo(x - hw, base - hh * 0.45f, tipX - hw * 0.35f, base - hh * 0.75f, tipX, base - hh)
            cubicTo(tipX + hw * 0.35f, base - hh * 0.75f, x + hw, base - hh * 0.45f, x + hw, base)
            quadTo(x, base + hw * 0.5f, x - hw, base)
            close()
        }
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, base, 0f, base - h,
                intArrayOf(0xFFFFD23F.toInt(), 0xFFFF7A00.toInt(), 0xCCE53935.toInt(), 0x00E53935),
                floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP,
            )
            alpha = if (big) 235 else 250
        }
        c.drawPath(shape(1f), outer)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, base, 0f, base - h * 0.6f,
                intArrayOf(0xFFFFFFF0.toInt(), 0xFFFFE066.toInt(), 0x00FFB300),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawPath(shape(0.5f), inner)
    }

    // ---------------------------------------------------------------- Brilhos

    private fun sparkles(c: Canvas, box: RectF, t: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 8) {
            // Em volta da figura, numa elipse um pouco maior que ela.
            val ang = TAU * (i / 8f + rnd(i, 4) * 0.08f)
            val rx = box.width() / 2 + 18f + 20f * rnd(i, 5)
            val ry = box.height() / 2 + 14f + 20f * rnd(i, 6)
            val cx = (box.centerX() + rx * kotlin.math.cos(ang)).coerceIn(24f, 488f)
            val cy = (box.centerY() + ry * sin(ang)).coerceIn(24f, 488f)
            val k = max(0f, wave(t, 1, rnd(i, 7)))
            if (k < 0.05f) continue
            val r = (14f + 14f * rnd(i, 8)) * k
            glow.color = 0x55FFD23F
            c.drawCircle(cx, cy, r * 0.9f, glow)
            paint.color = if (i % 3 == 0) 0xFFFFD23F.toInt() else 0xFFFFFFFF.toInt()
            c.drawPath(star(cx, cy, r), paint)
        }
    }

    private fun star(cx: Float, cy: Float, r: Float) = Path().apply {
        val k = r * 0.22f
        moveTo(cx, cy - r)
        quadTo(cx + k, cy - k, cx + r, cy)
        quadTo(cx + k, cy + k, cx, cy + r)
        quadTo(cx - k, cy + k, cx - r, cy)
        quadTo(cx - k, cy - k, cx, cy - r)
        close()
    }

    // ---------------------------------------------------------------- Corações

    private fun hearts(c: Canvas, box: RectF, t: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val travel = box.height() + 70f
        for (i in 0 until 7) {
            val p = (t + rnd(i, 9)) % 1f
            val side = if (i % 2 == 0) box.left - 6f + 40f * rnd(i, 10) else box.right + 6f - 40f * rnd(i, 10)
            val x = (side + 10f * wave(t, 2, rnd(i, 11))).coerceIn(20f, 492f)
            val y = box.bottom + 20f - p * travel
            val a = sin(PI.toFloat() * p)
            val size = 22f + 14f * rnd(i, 12)
            paint.color = if (i % 3 == 0) 0xFFFF4D8D.toInt() else 0xFFE53935.toInt()
            paint.alpha = (a * 255).toInt().coerceIn(0, 255)
            c.drawPath(heart(x, y, size), paint)
        }
    }

    private fun heart(cx: Float, cy: Float, s: Float) = Path().apply {
        val top = cy - s * 0.3f
        moveTo(cx, cy + s * 0.55f)
        cubicTo(cx - s * 0.9f, cy, cx - s * 0.55f, top - s * 0.5f, cx, top)
        cubicTo(cx + s * 0.55f, top - s * 0.5f, cx + s * 0.9f, cy, cx, cy + s * 0.55f)
        close()
    }

    // ---------------------------------------------------------------- Fogo: brilho e faíscas

    private fun glow(c: Canvas, box: RectF, t: Float) {
        val r = box.width() * (0.75f + 0.05f * wave(t, 2, 0.3f))
        val cy = minOf(box.bottom, 500f)
        c.drawCircle(box.centerX(), cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(box.centerX(), cy, r, intArrayOf(0x88FF7A00.toInt(), 0x00FF7A00), null, Shader.TileMode.CLAMP)
        })
    }

    private fun embers(c: Canvas, box: RectF, t: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val travel = box.height() * 0.9f + 60f
        for (i in 0 until 14) {
            val p = (t * (1 + i % 2) + rnd(i, 13)) % 1f
            val x = box.left - 10f + (box.width() + 20f) * rnd(i, 14) + 8f * wave(t, 3, rnd(i, 15))
            val y = box.bottom - p * travel
            paint.color = if (i % 3 == 0) 0xFFFFF0A0.toInt() else 0xFFFFB300.toInt()
            paint.alpha = (255 * (1 - p)).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, 2.5f + 2.5f * rnd(i, 16), paint)
        }
    }

    // ---------------------------------------------------------------- Fogo 16 bits

    private val PIXEL_FIRE = intArrayOf(
        0xFF5A0A0A.toInt(), 0xFFB3200E.toInt(), 0xFFE8501A.toInt(),
        0xFFF59A23.toInt(), 0xFFFFD23F.toInt(), 0xFFFFF6C2.toInt(),
    )

    /** Ruído suave que se repete a cada [pi] × [pj] células (assim o loop emenda). */
    private fun noise(x: Float, y: Float, pi: Int, pj: Int, salt: Int): Float {
        val i = kotlin.math.floor(x).toInt(); val j = kotlin.math.floor(y).toInt()
        fun lat(a: Int, b: Int) = rnd(Math.floorMod(a, pi) * 131 + Math.floorMod(b, pj) * 7 + salt * 1000, salt)
        fun sm(v: Float) = v * v * (3 - 2 * v)
        val fx = sm(x - i); val fy = sm(y - j)
        val top = lat(i, j) * (1 - fx) + lat(i + 1, j) * fx
        val bottom = lat(i, j + 1) * (1 - fx) + lat(i + 1, j + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun pixelFire(c: Canvas, box: RectF, t: Float, front: Boolean) {
        val cell = 8
        val w = box.width().coerceAtLeast(120f)
        val h = box.height().coerceAtLeast(120f)
        val base = minOf(box.bottom + 4f, 508f)
        val bandH = h * 0.5f
        val sideH = h * 0.85f
        val sideW = w * 0.16f
        val x0 = maxOf(0f, box.left - sideW * 0.8f)
        val x1 = minOf(512f, box.right + sideW * 0.8f)
        val y0 = maxOf(0f, base - sideH)
        val paint = Paint() // sem suavização: pixels de verdade
        var y = (y0 / cell).toInt() * cell
        while (y < base) {
            var x = (x0 / cell).toInt() * cell
            while (x < x1) {
                val cx = x + cell / 2f
                val cy = y + cell / 2f
                val side = max(0f, 1f - minOf(kotlin.math.abs(cx - box.left), kotlin.math.abs(cx - box.right)) / sideW)
                // Línguas de fogo: a altura muda de coluna para coluna e com o tempo.
                val tongue = noise(cx / 26f, t * 4f, 64, 4, 3)
                val fh = (bandH + (sideH - bandH) * side) * (0.45f + 0.95f * tongue)
                val v = (base - cy) / fh
                if (v <= 1.15f && !(front && v > 0.3f)) {
                    val n = 0.65f * noise(cx / 40f, cy / 40f + t * 8f, 32, 8, 1) +
                        0.35f * noise(cx / 20f, cy / 20f + t * 16f, 64, 16, 2)
                    var heat = (1 - v) * 1.05f + (n - 0.5f) * 1.1f - 0.05f
                    if (front) heat -= 0.3f
                    if (heat > 0.06f) {
                        paint.color = PIXEL_FIRE[(heat.coerceAtMost(0.999f) * PIXEL_FIRE.size).toInt()]
                        c.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
                    }
                }
                x += cell
            }
            y += cell
        }
    }

    // ---------------------------------------------------------------- Raios

    private fun lightning(c: Canvas, box: RectF, t: Float) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 22f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            color = 0x9966CCFF.toInt()
        }
        val mid = Paint(glow).apply { strokeWidth = 11f; color = 0xCCB8E6FF.toInt() }
        val core = Paint(glow).apply { strokeWidth = 5f; color = 0xFFFFFFFF.toInt() }
        var any = false
        for (b in 0 until 3) {
            // Cada raio aparece em 2 de cada 8 quadros, em momentos diferentes.
            val slot = ((t * 8).toInt() + b * 3) % 8
            if (slot > 1) continue
            any = true
            val flash = (t * 8).toInt() * 7 + b
            val startX = when (b) { 0 -> box.left + 10f; 1 -> box.right - 10f; else -> box.centerX() }
            var x = startX + (rnd(flash, 20) - 0.5f) * 40f
            var y = maxOf(4f, box.top - 30f)
            val path = Path().apply { moveTo(x, y) }
            val segs = 8
            val drop = (box.height() * 0.8f) / segs
            for (k in 0 until segs) {
                x += (rnd(flash * 11 + k, 21) - 0.5f) * 80f
                y += drop
                path.lineTo(x, y)
                // Galho curto no meio do raio.
                if (k == 3) {
                    val bx = x + (if (rnd(flash, 22) > 0.5f) 1 else -1) * (40f + 30f * rnd(flash, 23))
                    path.lineTo(bx, y + drop * 1.2f)
                    path.moveTo(x, y)
                }
            }
            c.drawPath(path, glow)
            c.drawPath(path, mid)
            c.drawPath(path, core)
        }
        if (any) c.drawColor(0x26B0D8FF)
    }

    // ---------------------------------------------------------------- Tontura

    private fun dizzy(c: Canvas, box: RectF, t: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = box.centerX()
        val cy = maxOf(40f, box.top + 22f)
        val rx = box.width() * 0.3f
        for (i in 0 until 5) {
            val a = TAU * (t + i / 5f)
            val x = cx + rx * kotlin.math.cos(a)
            val y = cy + 26f * sin(a)
            val r = 22f + 7f * sin(a) // maior quando passa na frente
            paint.color = if (i % 2 == 0) 0xFFFFD23F.toInt() else 0xFFFFFFFF.toInt()
            c.drawPath(star(x, y, r), paint)
        }
    }

    // ---------------------------------------------------------------- Neve, confete e chuva de emoji

    private fun fall(i: Int, salt: Int, t: Float, speed: Int) = ((rnd(i, salt) + t * speed) % 1f) * 560f - 24f

    private fun snow(c: Canvas, t: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 34) {
            val y = fall(i, 30, t, 1 + i % 2)
            val x = 512f * rnd(i, 31) + 10f * wave(t, 2, rnd(i, 32))
            paint.color = 0xFFFFFFFF.toInt()
            paint.alpha = 170 + (85 * rnd(i, 33)).toInt()
            c.drawCircle(x, y, 2.5f + 4f * rnd(i, 34), paint)
        }
    }

    private val CONFETTI = intArrayOf(
        0xFFEC407A.toInt(), 0xFFFFD23F.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(),
        0xFF6C3CE9.toInt(), 0xFFFF7A00.toInt(),
    )

    private fun confetti(c: Canvas, t: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 40) {
            val y = fall(i, 40, t, 1)
            val x = 512f * rnd(i, 41) + 14f * wave(t, 2, rnd(i, 42))
            paint.color = CONFETTI[i % CONFETTI.size]
            c.save()
            c.translate(x, y)
            c.rotate(360f * (t * (1 + i % 3) + rnd(i, 43)))
            c.scale(1f, 0.35f + 0.65f * kotlin.math.abs(wave(t, 2, rnd(i, 44)))) // vira no ar
            c.drawRect(-7f, -13f, 7f, 13f, paint)
            c.restore()
        }
    }

    private fun emojiRain(c: Canvas, t: Float, emoji: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        for (i in 0 until 9) {
            val y = fall(i, 50, t, 1)
            val x = 30f + 452f * rnd(i, 51)
            paint.textSize = 34f + 16f * rnd(i, 52)
            c.save()
            c.translate(x, y)
            c.rotate(18f * wave(t, 1, rnd(i, 53)))
            c.drawText(emoji, 0f, paint.textSize * 0.35f, paint)
            c.restore()
        }
    }
}
