package com.trevisolli.agendawidget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val HOLD_MS = 230L

internal enum class MicSwipe { NONE, CONVERSATION, CANCEL }

internal fun micSwipe(dx: Float, dy: Float, threshold: Float): MicSwipe = when {
    dy <= -threshold && abs(dy) > abs(dx) -> MicSwipe.CONVERSATION
    dx <= -threshold && abs(dx) > abs(dy) -> MicSwipe.CANCEL
    else -> MicSwipe.NONE
}

@Composable
internal fun ChatMicrophone(
    enabled: Boolean, recording: Boolean, onStart: () -> Unit, onRelease: () -> Unit,
    onCancel: () -> Unit, onTap: () -> Unit, onConversation: () -> Unit,
) {
    val start by rememberUpdatedState(onStart)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    val tap by rememberUpdatedState(onTap)
    val conversation by rememberUpdatedState(onConversation)
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(when {
                !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
                recording -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            })
            .semantics {
                role = Role.Button
                contentDescription = "Microfone. Segure para gravar. Arraste para cima para conversar."
                if (enabled) {
                    onClick("Ditar mensagem") { tap(); true }
                    customActions = listOf(CustomAccessibilityAction("Conversar por voz") { conversation(); true })
                } else disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val threshold = 60.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var held = false
                    var wait = HOLD_MS
                    try {
                        while (true) {
                            val event = if (held) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
                            if (event == null) {
                                held = true
                                start()
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            val delta = change.position - down.position
                            val swipe = micSwipe(delta.x, delta.y, threshold)
                            change.consume()
                            if (swipe != MicSwipe.NONE) {
                                cancel()
                                if (swipe == MicSwipe.CONVERSATION) conversation()
                                break
                            }
                            if (!change.pressed) {
                                if (held) release() else tap()
                                break
                            }
                            wait = (HOLD_MS - (change.uptimeMillis - down.uptimeMillis)).coerceAtLeast(1)
                        }
                    } finally {
                        cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (recording) "🔴" else "🎤", style = MaterialTheme.typography.titleLarge)
    }
}
