package com.trevisolli.agendawidget.widget

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Paisagens generativas com clima de animação japonesa: colinas, nuvens enormes, mar, floresta, casinhas.
 * Sem pessoas. O céu segue a hora do dia e a cena muda a cada atualização.
 * Desenha sobre uma interface [Painter] para poder ser renderizada fora do Android (prévias).
 */
object LandscapeArt {

    /** Primitivas de desenho; cores em ARGB. */
    interface Painter {
        val width: Int
        val height: Int
        fun verticalGradient(top: Float, bottom: Float, colors: IntArray, stops: FloatArray)
        fun glow(cx: Float, cy: Float, r: Float, inner: Int, outer: Int)
        fun circle(cx: Float, cy: Float, r: Float, color: Int)
        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int)
        fun fill(shape: Shape, color: Int)
        fun fillGradient(shape: Shape, top: Float, bottom: Float, topColor: Int, bottomColor: Int)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int)
    }

    /** Caminho simples (move/line/quad/close) que cada plataforma converte. */
    class Shape {
        val ops = mutableListOf<FloatArray>()
        fun moveTo(x: Float, y: Float) = apply { ops += floatArrayOf(0f, x, y) }
        fun lineTo(x: Float, y: Float) = apply { ops += floatArrayOf(1f, x, y) }
        fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = apply { ops += floatArrayOf(2f, cx, cy, x, y) }
        fun close() = apply { ops += floatArrayOf(3f) }
    }

    data class Sky(
        val name: String,
        val gradient: IntArray,
        val light: Int,
        val cloud: Int,
        val cloudShade: Int,
        val far: Int,
        val mid: Int,
        val near: Int,
        val sea: Int,
        val dark: Boolean,
    )

    private fun c(hex: Long) = hex.toInt()

    private val DAWN = Sky(
        "Amanhecer", intArrayOf(c(0xFFF4A7A0), c(0xFFF9CDB2), c(0xFFFDEBD3)), c(0xFFFFE8C2),
        c(0xFFFFF6EE), c(0xFFE9BFC4), c(0xFFB9C6B0), c(0xFF86AB80), c(0xFF5A8A5C), c(0xFF8FB8C9), false,
    )
    private val DAY = Sky(
        "Tarde de verão", intArrayOf(c(0xFF4C9BD1), c(0xFF8CC8EA), c(0xFFDDF1F8)), c(0xFFFFFBE6),
        c(0xFFFFFFFF), c(0xFFC9DCEE), c(0xFFA9CBA4), c(0xFF77B36A), c(0xFF4A8D3F), c(0xFF4F9DC9), false,
    )
    private val GOLDEN = Sky(
        "Hora dourada", intArrayOf(c(0xFF7FA6CC), c(0xFFF2C38E), c(0xFFFBE3B6)), c(0xFFFFD98A),
        c(0xFFFFF0DC), c(0xFFE9B48F), c(0xFFB9B886), c(0xFF8EA65E), c(0xFF5E7D3B), c(0xFF6D9EB8), false,
    )
    private val DUSK = Sky(
        "Entardecer", intArrayOf(c(0xFF343B78), c(0xFF9C6A9E), c(0xFFF2A584)), c(0xFFFFB38A),
        c(0xFFF5C2C8), c(0xFF9A7BA8), c(0xFF6A5D8C), c(0xFF4A4572), c(0xFF2E2B50), c(0xFF5B5A8E), true,
    )
    private val NIGHT = Sky(
        "Noite estrelada", intArrayOf(c(0xFF0A1433), c(0xFF1B2A5C), c(0xFF34477C)), c(0xFFFFF4D2),
        c(0xFF55669A), c(0xFF2C3A66), c(0xFF2A3B5F), c(0xFF1C2B48), c(0xFF111B31), c(0xFF1E3260), true,
    )

    fun skyFor(hour: Int): Sky = when (hour) {
        in 5..7 -> DAWN
        in 8..15 -> DAY
        in 16..17 -> GOLDEN
        in 18..19 -> DUSK
        else -> NIGHT
    }

    enum class Scene(val label: String) {
        HILLS("Colinas e nuvens"),
        SEASIDE("Casa no penhasco"),
        FOREST("Bosque antigo"),
        MEADOW("Campo de flores"),
        LAKE("Lago nas montanhas"),
    }

    fun sceneFor(seed: Long): Scene = Scene.entries[Random(seed).nextInt(Scene.entries.size)]

    /** Tempo lá fora, para a cena acompanhar (chuva, nublado, calor…). */
    enum class Weather(val label: String) {
        CLEAR(""), HOT("Calor"), CLOUDY("Nublado"), FOG("Neblina"), RAIN("Chuva"), STORM("Tempestade")
    }

    /** Céu acinzentado para dias nublados/chuvosos. */
    private fun overcast(sky: Sky, amount: Float): Sky {
        val grey = if (sky.dark) 0xFF2A2F3A.toInt() else 0xFF9AA5B1.toInt()
        return sky.copy(
            gradient = sky.gradient.map { mix(it, grey, amount) }.toIntArray(),
            light = mix(sky.light, grey, amount),
            cloud = mix(sky.cloud, if (sky.dark) 0xFF4A5060.toInt() else 0xFFD3D8DE.toInt(), amount),
            cloudShade = mix(sky.cloudShade, if (sky.dark) 0xFF2E3340.toInt() else 0xFF9AA3AE.toInt(), amount),
            far = mix(sky.far, grey, amount * 0.5f),
            mid = mix(sky.mid, grey, amount * 0.3f),
            near = mix(sky.near, grey, amount * 0.2f),
            sea = mix(sky.sea, grey, amount * 0.5f),
        )
    }

    /** Desenha a cena inteira. Retorna se o resultado é escuro (texto claro por cima). */
    fun draw(p: Painter, seed: Long, hour: Int, scene: Scene = sceneFor(seed), weather: Weather = Weather.CLEAR): Boolean {
        val rnd = Random(seed * 31 + 7)
        val base = skyFor(hour)
        val sky = when (weather) {
            Weather.RAIN -> overcast(base, 0.55f)
            Weather.STORM -> overcast(base, 0.7f)
            Weather.CLOUDY -> overcast(base, 0.35f)
            Weather.FOG -> overcast(base, 0.45f)
            else -> base
        }
        val wet = weather == Weather.RAIN || weather == Weather.STORM
        val w = p.width.toFloat()
        val h = p.height.toFloat()
        val unit = min(w, h)

        p.verticalGradient(0f, h, sky.gradient, floatArrayOf(0f, 0.55f, 1f))

        if (sky.dark) stars(p, rnd, w, h, if (sky === NIGHT) 90 else 25)
        // Sol ou lua
        val lx = w * (0.62f + rnd.nextFloat() * 0.25f)
        val ly = h * (0.12f + rnd.nextFloat() * 0.18f)
        val lr = unit * (0.06f + rnd.nextFloat() * 0.03f)
        if (!wet) {
            val heat = if (weather == Weather.HOT) 1.6f else 1f
            p.glow(lx, ly, lr * 5f * heat, withAlpha(sky.light, if (weather == Weather.CLOUDY) 80 else 150), withAlpha(sky.light, 0))
            p.circle(lx, ly, lr * heat.coerceAtMost(1.3f), withAlpha(sky.light, if (weather == Weather.CLOUDY) 140 else 235))
        }

        val cloudCount = when {
            wet || weather == Weather.CLOUDY -> 5 + rnd.nextInt(3)
            weather == Weather.HOT -> 1
            base === NIGHT -> 2
            else -> 3 + rnd.nextInt(3)
        }
        repeat(cloudCount) {
            val cw = w * (0.3f + rnd.nextFloat() * 0.35f)
            cloud(p, rnd, w * (rnd.nextFloat() * 1.1f - 0.05f), h * (0.1f + rnd.nextFloat() * 0.35f), cw, sky)
        }

        when (scene) {
            Scene.HILLS -> hills(p, rnd, w, h, sky, tree = true, flowers = false)
            Scene.MEADOW -> hills(p, rnd, w, h, sky, tree = false, flowers = true)
            Scene.SEASIDE -> seaside(p, rnd, w, h, sky)
            Scene.FOREST -> forest(p, rnd, w, h, sky, rays = weather == Weather.CLEAR || weather == Weather.HOT)
            Scene.LAKE -> lake(p, rnd, w, h, sky)
        }

        if (sky.dark && !wet) fireflies(p, rnd, w, h)
        if (wet) rain(p, rnd, w, h, weather == Weather.STORM, sky.dark)
        if (weather == Weather.STORM) lightning(p, rnd, w, h)
        if (weather == Weather.FOG) {
            p.verticalGradient(h * 0.35f, h, intArrayOf(0x00FFFFFF, 0x99E6EAEE.toInt(), 0xCCE6EAEE.toInt()), floatArrayOf(0f, 0.6f, 1f))
        }
        return sky.dark
    }

    // ---------- Elementos ----------

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun stars(p: Painter, rnd: Random, w: Float, h: Float, n: Int) {
        repeat(n) {
            val r = 0.6f + rnd.nextFloat() * 1.6f
            p.circle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.6f, r, withAlpha(0xFFFFFFFF.toInt(), 110 + rnd.nextInt(145)))
        }
    }

    /** Nuvem cúmulo: sombra lavanda embaixo, corpo claro em cima, base achatada. */
    private fun cloud(p: Painter, rnd: Random, x: Float, y: Float, cw: Float, sky: Sky) {
        val puffs = 5 + rnd.nextInt(4)
        val base = cw * 0.16f
        val centers = List(puffs) { i ->
            val t = i / (puffs - 1f)
            val px = x + (t - 0.5f) * cw
            val bump = sin(t * PI).toFloat()
            val r = base * (0.6f + bump * 0.9f + rnd.nextFloat() * 0.25f)
            Triple(px, y - bump * base * 0.9f, r)
        }
        // Base: fileira de bolinhas na cor da sombra, para a nuvem "assentar" sem cantos retos.
        val baseRow = 7
        for (i in 0 until baseRow) {
            val t = i / (baseRow - 1f)
            p.circle(x + (t - 0.5f) * cw * 0.95f, y + base * 0.35f, base * (0.55f + rnd.nextFloat() * 0.15f), sky.cloudShade)
        }
        centers.forEach { (cx, cy, r) -> p.circle(cx, cy + r * 0.22f, r, sky.cloudShade) }
        centers.forEach { (cx, cy, r) -> p.circle(cx - r * 0.08f, cy - r * 0.08f, r * 0.88f, sky.cloud) }
    }

    /** Cordilheira ondulada de [top] até o fim da imagem. */
    private fun ridge(rnd: Random, w: Float, h: Float, top: Float, amp: Float, bumps: Int): Pair<Shape, Float> {
        val s = Shape().moveTo(0f, h)
        var y = top + (rnd.nextFloat() - 0.5f) * amp
        s.lineTo(0f, y)
        val step = w / bumps
        var minY = y
        for (i in 1..bumps) {
            val nx = i * step
            val ny = top + (rnd.nextFloat() - 0.5f) * amp
            val cy = min(y, ny) - amp * (0.3f + rnd.nextFloat() * 0.6f)
            s.quadTo(nx - step / 2, cy, nx, ny)
            minY = min(minY, cy)
            y = ny
        }
        s.lineTo(w, h).close()
        return s to minY
    }

    private fun hills(p: Painter, rnd: Random, w: Float, h: Float, sky: Sky, tree: Boolean, flowers: Boolean) {
        val (far, _) = ridge(rnd, w, h, h * 0.62f, h * 0.06f, 3)
        p.fillGradient(far, h * 0.55f, h, mix(sky.far, sky.gradient.last(), 0.25f), sky.far)
        distantDetails(p, rnd, w, h * 0.665f, min(w, h), sky)
        val (mid, _) = ridge(rnd, w, h, h * 0.71f, h * 0.07f, 2)
        p.fillGradient(mid, h * 0.65f, h, sky.mid, mix(sky.mid, sky.near, 0.5f))
        val nearTop = h * 0.82f
        val (near, _) = ridge(rnd, w, h, nearTop, h * 0.05f, 2)
        p.fillGradient(near, nearTop - h * 0.05f, h, mix(sky.near, sky.mid, 0.25f), sky.near)

        if (tree) {
            val tx = w * (0.6f + rnd.nextFloat() * 0.3f)
            loneTree(p, rnd, tx, h * 0.735f, min(w, h) * 0.11f, sky)
        }
        if (flowers) {
            house(p, rnd, w * (0.65f + rnd.nextFloat() * 0.2f), h * 0.705f, min(w, h) * 0.07f, sky)
            val palette = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFD3E0.toInt(), 0xFFFFE680.toInt(), 0xFFE6D3FF.toInt())
            repeat(if (sky.dark) 50 else 140) {
                val fx = rnd.nextFloat() * w
                val fy = h * (0.84f + rnd.nextFloat() * 0.16f)
                val col = if (sky.dark) withAlpha(palette[rnd.nextInt(palette.size)], 90) else palette[rnd.nextInt(palette.size)]
                p.circle(fx, fy, 1.2f + rnd.nextFloat() * 2.2f, col)
            }
        }
        grass(p, rnd, w, h, sky)
    }

    private fun grass(p: Painter, rnd: Random, w: Float, h: Float, sky: Sky) {
        val col = withAlpha(mix(sky.near, 0xFF000000.toInt(), 0.15f), 200)
        repeat(70) {
            val x = rnd.nextFloat() * w
            val y = h * (0.93f + rnd.nextFloat() * 0.07f)
            p.line(x, y, x + (rnd.nextFloat() - 0.5f) * 6f, y - 6f - rnd.nextFloat() * 10f, 1.4f, col)
        }
    }

    private fun loneTree(p: Painter, rnd: Random, x: Float, groundY: Float, size: Float, sky: Sky) {
        val trunk = mix(0xFF5B4636.toInt(), sky.near, 0.3f)
        p.rect(x - size * 0.06f, groundY - size * 0.9f, x + size * 0.06f, groundY + size * 0.05f, trunk)
        val dark = mix(sky.near, 0xFF0B2010.toInt(), 0.35f)
        val light = mix(sky.mid, sky.light, 0.2f)
        repeat(7) {
            val ox = (rnd.nextFloat() - 0.5f) * size * 0.9f
            val oy = -size * (0.95f + rnd.nextFloat() * 0.5f)
            p.circle(x + ox, groundY + oy, size * (0.3f + rnd.nextFloat() * 0.2f), dark)
        }
        repeat(5) {
            val ox = (rnd.nextFloat() - 0.6f) * size * 0.7f
            val oy = -size * (1.1f + rnd.nextFloat() * 0.45f)
            p.circle(x + ox, groundY + oy, size * (0.16f + rnd.nextFloat() * 0.12f), withAlpha(light, 170))
        }
    }

    private fun house(p: Painter, rnd: Random, x: Float, groundY: Float, size: Float, sky: Sky) {
        val wall = mix(0xFFF4EAD7.toInt(), sky.far, if (sky.dark) 0.6f else 0.1f)
        val roofs = intArrayOf(0xFFC2463A.toInt(), 0xFF3F7F8C.toInt(), 0xFFD9822B.toInt())
        val roof = mix(roofs[rnd.nextInt(roofs.size)], sky.near, if (sky.dark) 0.5f else 0.05f)
        val hw = size * 0.6f
        p.rect(x - hw, groundY - size * 0.75f, x + hw, groundY, wall)
        p.rect(x + hw * 0.35f, groundY - size * 1.35f, x + hw * 0.6f, groundY - size * 0.9f, mix(roof, 0xFF000000.toInt(), 0.2f))
        p.fill(
            Shape().moveTo(x - hw * 1.25f, groundY - size * 0.7f).lineTo(x, groundY - size * 1.35f)
                .lineTo(x + hw * 1.25f, groundY - size * 0.7f).close(),
            roof,
        )
        val window = if (sky.dark) 0xFFFFD66B.toInt() else mix(0xFF5B87A8.toInt(), wall, 0.2f)
        if (sky.dark) p.glow(x, groundY - size * 0.38f, size * 0.7f, withAlpha(0xFFFFD66B.toInt(), 110), withAlpha(0xFFFFD66B.toInt(), 0))
        p.rect(x - size * 0.18f, groundY - size * 0.52f, x + size * 0.18f, groundY - size * 0.24f, window)
    }

    private fun seaside(p: Painter, rnd: Random, w: Float, h: Float, sky: Sky) {
        val horizon = h * 0.66f
        val sea = Shape().moveTo(0f, horizon).lineTo(w, horizon).lineTo(w, h).lineTo(0f, h).close()
        p.fillGradient(sea, horizon, h, mix(sky.sea, sky.gradient.last(), 0.35f), mix(sky.sea, 0xFF0A2A44.toInt(), 0.35f))
        // Ilhas ao longe
        val (island, _) = ridge(rnd, w * 0.45f, horizon + 1f, horizon - h * 0.025f, h * 0.02f, 2)
        p.fill(island, withAlpha(sky.far, 200))
        // Brilho na água
        repeat(40) {
            val y = horizon + (h - horizon) * rnd.nextFloat()
            val x = rnd.nextFloat() * w
            val len = 6f + rnd.nextFloat() * 22f
            p.line(x, y, x + len, y, 1.6f, withAlpha(sky.light, 70 + rnd.nextInt(90)))
        }
        // Penhasco com grama à direita
        val cliffTop = h * 0.6f
        val cliff = Shape().moveTo(w * 0.52f, h).lineTo(w * 0.58f, cliffTop + h * 0.04f)
            .quadTo(w * 0.62f, cliffTop - h * 0.01f, w * 0.72f, cliffTop).lineTo(w, cliffTop - h * 0.02f).lineTo(w, h).close()
        p.fillGradient(cliff, cliffTop, h, mix(0xFFB59B7A.toInt(), sky.far, 0.3f), mix(0xFF6E5A47.toInt(), sky.near, 0.4f))
        val grassTop = Shape().moveTo(w * 0.575f, cliffTop + h * 0.045f)
            .quadTo(w * 0.62f, cliffTop - h * 0.03f, w * 0.72f, cliffTop - h * 0.015f).lineTo(w, cliffTop - h * 0.035f)
            .lineTo(w, cliffTop + h * 0.03f).quadTo(w * 0.75f, cliffTop + h * 0.035f, w * 0.575f, cliffTop + h * 0.045f).close()
        p.fill(grassTop, sky.mid)
        house(p, rnd, w * 0.82f, cliffTop - h * 0.02f, min(w, h) * 0.08f, sky)
    }

    private fun forest(p: Painter, rnd: Random, w: Float, h: Float, sky: Sky, rays: Boolean) {
        if (!sky.dark && rays) {
            repeat(4) {
                val x = w * (0.1f + rnd.nextFloat() * 0.5f)
                val spread = w * (0.08f + rnd.nextFloat() * 0.1f)
                p.fill(
                    Shape().moveTo(x, 0f).lineTo(x + spread * 0.4f, 0f).lineTo(x + spread * 2.6f, h).lineTo(x + spread * 1.2f, h).close(),
                    withAlpha(sky.light, 34),
                )
            }
        }
        val layers = listOf(
            Triple(h * 0.6f, mix(sky.far, sky.gradient.last(), 0.2f), 0.045f),
            Triple(h * 0.7f, sky.mid, 0.06f),
            Triple(h * 0.8f, mix(sky.near, 0xFF0B2010.toInt(), 0.25f), 0.08f),
        )
        layers.forEach { (top, color, scale) ->
            val r = min(w, h) * scale
            var x = -r
            while (x < w + r) {
                val th = top + (rnd.nextFloat() - 0.5f) * r * 0.8f
                // Copa arredondada em camadas, como árvores de aquarela
                p.circle(x, th, r * (0.9f + rnd.nextFloat() * 0.3f), color)
                p.circle(x + r * 0.3f, th - r * 0.7f, r * 0.7f, color)
                p.rect(x - r, th, x + r * 1.2f, h, color)
                x += r * (1.1f + rnd.nextFloat() * 0.5f)
            }
        }
        grass(p, rnd, w, h, sky)
    }

    private fun lake(p: Painter, rnd: Random, w: Float, h: Float, sky: Sky) {
        val shore = h * 0.68f
        // Duas cadeias de montanhas: a de trás mais clara (perspectiva atmosférica).
        val (back, _) = mountains(rnd, w, shore, h * 0.2f)
        p.fillGradient(back, shore - h * 0.25f, shore, mix(sky.far, sky.gradient.last(), 0.5f), mix(sky.far, sky.gradient.last(), 0.2f))
        val (front, _) = mountains(rnd, w, shore, h * 0.12f)
        p.fillGradient(front, shore - h * 0.15f, shore, sky.far, mix(sky.far, sky.mid, 0.5f))
        // Água com reflexo suave
        val water = Shape().moveTo(0f, shore).lineTo(w, shore).lineTo(w, h).lineTo(0f, h).close()
        p.fillGradient(water, shore, h, mix(sky.gradient[1], sky.sea, 0.4f), mix(sky.sea, 0xFF0A2A44.toInt(), 0.3f))
        repeat(30) {
            val y = shore + (h - shore) * rnd.nextFloat() * 0.7f
            val lx = rnd.nextFloat() * w
            p.line(lx, y, lx + 10f + rnd.nextFloat() * 30f, y, 1.4f, withAlpha(sky.light, 60 + rnd.nextInt(60)))
        }
        // Margem com grama na frente
        val (bank, _) = ridge(rnd, w, h, h * 0.9f, h * 0.04f, 3)
        p.fillGradient(bank, h * 0.86f, h, sky.mid, sky.near)
        grass(p, rnd, w, h, sky)
    }

    /** Picos largos e arredondados, terminando em [base]. */
    private fun mountains(rnd: Random, w: Float, base: Float, height: Float): Pair<Shape, Float> {
        val s = Shape().moveTo(0f, base)
        var x = -w * 0.1f
        s.lineTo(x, base - height * 0.3f)
        while (x < w) {
            val pw = w * (0.3f + rnd.nextFloat() * 0.25f)
            val ph = height * (0.6f + rnd.nextFloat() * 0.5f)
            s.quadTo(x + pw * 0.3f, base - ph * 1.05f, x + pw * 0.5f, base - ph)
            s.quadTo(x + pw * 0.7f, base - ph * 0.95f, x + pw, base - height * (0.2f + rnd.nextFloat() * 0.25f))
            x += pw
        }
        s.lineTo(w, base).close()
        return s to base - height
    }

    /** Arvorezinhas e casas pequenas sobre uma faixa de colina, para dar escala. */
    private fun distantDetails(p: Painter, rnd: Random, w: Float, y: Float, unit: Float, sky: Sky) {
        val treeColor = mix(sky.mid, 0xFF0B2010.toInt(), 0.3f)
        repeat(6 + rnd.nextInt(6)) {
            val x = rnd.nextFloat() * w
            val yy = y + (rnd.nextFloat() - 0.5f) * unit * 0.04f
            val r = unit * (0.012f + rnd.nextFloat() * 0.01f)
            p.rect(x - r * 0.15f, yy, x + r * 0.15f, yy + r * 1.2f, treeColor)
            p.circle(x, yy - r * 0.2f, r, treeColor)
            p.circle(x, yy - r * 1.1f, r * 0.75f, treeColor)
        }
        if (rnd.nextBoolean()) house(p, rnd, w * (0.15f + rnd.nextFloat() * 0.3f), y + unit * 0.01f, unit * 0.035f, sky)
    }

    private fun rain(p: Painter, rnd: Random, w: Float, h: Float, heavy: Boolean, dark: Boolean) {
        val color = if (dark) 0x66C8D6E8 else 0x80FFFFFF.toInt()
        repeat(if (heavy) 220 else 140) {
            val x = rnd.nextFloat() * (w + h * 0.3f) - h * 0.15f
            val y = rnd.nextFloat() * h
            val len = h * (0.025f + rnd.nextFloat() * 0.03f)
            p.line(x, y, x - len * 0.25f, y + len, 1.3f, color)
        }
        // Gotinhas batendo no chão
        repeat(25) {
            val x = rnd.nextFloat() * w
            val y = h * (0.9f + rnd.nextFloat() * 0.1f)
            p.line(x - 3f, y, x + 3f, y, 1f, color)
        }
    }

    private fun lightning(p: Painter, rnd: Random, w: Float, h: Float) {
        var x = w * (0.55f + rnd.nextFloat() * 0.35f)
        var y = h * 0.12f
        val color = 0xDDFFF6B0.toInt()
        p.glow(x, y, h * 0.25f, 0x55FFF6B0, 0x00FFF6B0)
        while (y < h * 0.55f) {
            val nx = x + (rnd.nextFloat() - 0.5f) * w * 0.08f
            val ny = y + h * (0.04f + rnd.nextFloat() * 0.05f)
            p.line(x, y, nx, ny, 3f, color)
            x = nx
            y = ny
        }
    }

    private fun fireflies(p: Painter, rnd: Random, w: Float, h: Float) {
        repeat(14) {
            val x = rnd.nextFloat() * w
            val y = h * (0.62f + rnd.nextFloat() * 0.35f)
            p.glow(x, y, 9f, withAlpha(0xFFFFF3A0.toInt(), 150), withAlpha(0xFFFFF3A0.toInt(), 0))
            p.circle(x, y, 1.6f, 0xFFFFF8C8.toInt())
        }
    }
}
