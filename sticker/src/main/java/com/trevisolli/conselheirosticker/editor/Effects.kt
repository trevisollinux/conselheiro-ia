package com.trevisolli.conselheirosticker.editor

import android.graphics.Canvas
import android.graphics.LinearGradient
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
    Shake("🫨 Tremor"),
    Sparkles("✨ Brilhos"),
    Hearts("❤️ Corações"),
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
        // Com fogo, a figura (não os textos) encolhe um pouco para as chamas aparecerem em volta.
        val fire = if (Effect.Fire in effects) 0.82f else 1f
        return Motion(dx, dy, rot, pulse * fire, pulse)
    }

    /** Camada de trás (fica atrás da figura): as chamas grandes. */
    fun drawBehind(c: Canvas, effects: Set<Effect>, box: RectF, t: Float) {
        if (Effect.Fire in effects) fire(c, box, t, front = false)
    }

    /** Camada da frente: chamas baixas, brilhos e corações. */
    fun drawFront(c: Canvas, effects: Set<Effect>, box: RectF, t: Float) {
        if (Effect.Fire in effects) fire(c, box, t, front = true)
        if (Effect.Sparkles in effects) sparkles(c, box, t)
        if (Effect.Hearts in effects) hearts(c, box, t)
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
}
