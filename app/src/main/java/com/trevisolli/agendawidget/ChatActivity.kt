package com.trevisolli.agendawidget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.ui.APP_COLORS
import com.trevisolli.agendawidget.ui.APP_TYPE
import com.trevisolli.agendawidget.ui.ChatPanel

/** Janela de conversa aberta pelo widget (barra "Fale comigo" ou 🎤), por cima da tela inicial. */
class ChatActivity : ComponentActivity() {
    companion object {
        const val EXTRA_VOICE = "voice"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voice = intent.getBooleanExtra(EXTRA_VOICE, false)
        setContent {
            MaterialTheme(colorScheme = APP_COLORS, typography = APP_TYPE) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                ) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.72f)
                            .imePadding()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "🧭 ConsigliereIA",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    startActivity(Intent(this@ChatActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    finish()
                                }) { Text("Abrir app") }
                            }
                            ChatPanel(modifier = Modifier.weight(1f), autoVoice = voice)
                        }
                    }
                }
            }
        }
    }
}
