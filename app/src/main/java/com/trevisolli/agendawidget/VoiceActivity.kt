package com.trevisolli.agendawidget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.smart.AiProvider
import com.trevisolli.agendawidget.smart.Assistant
import com.trevisolli.agendawidget.smart.ChatMessage
import com.trevisolli.agendawidget.smart.VoiceRecorder
import com.trevisolli.agendawidget.ui.ConsigliereTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gravador aberto pelo widget: já começa gravando; toque para enviar e veja o que foi criado. */
class VoiceActivity : ComponentActivity() {
    companion object {
        /** Sem IA que ouça áudio, cai na conversa com o reconhecimento do sistema. */
        fun intent(context: Context): Intent =
            if (Assistant.canHear(context)) {
                Intent(context, VoiceActivity::class.java).setData(Uri.parse("consigliere://voice"))
            } else {
                Intent(context, ChatActivity::class.java)
                    .setData(Uri.parse("consigliere://chat/voice")).putExtra(ChatActivity.EXTRA_VOICE, true)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var recorder: VoiceRecorder? = null
    private var phase by mutableStateOf("rec") // rec, think, done
    private var seconds by mutableIntStateOf(0)
    private var answer by mutableStateOf<ChatMessage?>(null)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else {
            Toast.makeText(this, "Sem o microfone não dá para gravar", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { ConsigliereTheme { Screen() } }
    }

    private fun start() {
        val r = VoiceRecorder(this, forOpenAi = Settings(this).aiProvider == AiProvider.OPENAI)
        try {
            r.start()
            recorder = r
            seconds = 0
        } catch (e: Exception) {
            r.cancel()
            Toast.makeText(this, "Não consegui usar o microfone", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        recorder?.cancel()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()

        fun send() {
            val r = recorder ?: return
            recorder = null
            val audio = r.stop()
            if (audio == null) {
                Toast.makeText(this, "Áudio muito curto", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            phase = "think"
            scope.launch {
                answer = Assistant.handleAudio(this@VoiceActivity, audio, r.mime)
                phase = "done"
            }
        }

        // Cronômetro; para sozinho em 60 s.
        LaunchedEffect(phase) {
            while (phase == "rec") {
                delay(1000)
                if (recorder != null) seconds++
                if (seconds >= 60) send()
            }
        }
        // Resultado some sozinho depois de um tempo.
        LaunchedEffect(phase, answer) {
            if (phase == "done") {
                delay(if (answer?.undo?.isNotEmpty() == true) 15_000 else 6_000)
                finish()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (phase != "think") finish()
                },
        ) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (phase) {
                        "rec" -> {
                            Text("Fale… ex.: \"amanhã médico às 15h para a Helena\"", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                                initialValue = 1f, targetValue = 1.12f,
                                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
                            )
                            Box(
                                Modifier.size(96.dp).scale(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.error).clickable { send() },
                                contentAlignment = Alignment.Center,
                            ) { Text("➤", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onError) }
                            Text("🔴 0:%02d · toque para enviar".format(seconds), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { finish() }) { Text("Cancelar") }
                        }
                        "think" -> {
                            CircularProgressIndicator()
                            Text("Entendendo…", style = MaterialTheme.typography.titleMedium)
                        }
                        else -> {
                            val a = answer
                            Text(a?.text ?: "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (a != null && a.undo.isNotEmpty()) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            Assistant.undo(this@VoiceActivity, a)
                                            finish()
                                        }
                                    }) { Text("Desfazer") }
                                }
                                TextButton(onClick = {
                                    // Gravar outro na sequência.
                                    answer = null
                                    phase = "rec"
                                    start()
                                }) { Text("🎤 Outro") }
                                TextButton(onClick = { finish() }) { Text("OK") }
                            }
                        }
                    }
                }
            }
        }
    }
}
