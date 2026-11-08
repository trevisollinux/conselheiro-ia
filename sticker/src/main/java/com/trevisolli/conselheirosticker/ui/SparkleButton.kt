package com.trevisolli.conselheirosticker.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/** Posição (fração da largura e altura do botão) e atraso de cada brilho. */
private val SPARKS = listOf(
    Triple(0.05f, 0.18f, 0.00f), Triple(0.18f, 0.92f, 0.55f), Triple(0.36f, 0.06f, 0.25f),
    Triple(0.55f, 0.95f, 0.80f), Triple(0.72f, 0.08f, 0.40f), Triple(0.88f, 0.90f, 0.10f),
    Triple(0.97f, 0.30f, 0.65f),
)

/** Botão principal com brilhinhos ✨ piscando em volta, para chamar atenção quando há algo a fazer. */
@Composable
fun SparkleButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "brilhos")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "fase",
    )
    Box(modifier) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
        if (enabled) {
            // Sem toque próprio: os toques passam para o botão.
            Canvas(Modifier.matchParentSize()) {
                SPARKS.forEachIndexed { i, (fx, fy, delay) ->
                    val k = sin(2 * PI * (t + delay)).toFloat()
                    if (k <= 0.05f) return@forEachIndexed
                    val r = (5f + 4f * (i % 3)) * k * density
                    val c = Offset(size.width * fx, size.height * fy)
                    drawPath(star(c, r), if (i % 2 == 0) Color(0xFFFFD23F) else Color.White)
                }
            }
        }
    }
}

private fun star(c: Offset, r: Float) = Path().apply {
    val k = r * 0.22f
    moveTo(c.x, c.y - r)
    quadraticBezierTo(c.x + k, c.y - k, c.x + r, c.y)
    quadraticBezierTo(c.x + k, c.y + k, c.x, c.y + r)
    quadraticBezierTo(c.x - k, c.y + k, c.x - r, c.y)
    quadraticBezierTo(c.x - k, c.y - k, c.x, c.y - r)
    close()
}

