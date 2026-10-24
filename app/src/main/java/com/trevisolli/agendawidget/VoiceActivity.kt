package com.trevisolli.agendawidget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.async

/** Gravador aberto pelo widget: já começa gravando; toque para enviar e veja o que foi criado. */
class VoiceActivity : ComponentActivity() {
    companion object {
        /** Reconhecimento do aparelho (o mesmo do teclado) ou, sem ele, áudio para a IA. */
        fun available(context: Context) = SpeechRecognizer.isRecognitionAvailable(context) || Assistant.canHear(context)

        fun intent(context: Context): Intent =
            if (available(context)) {
                Intent(context, VoiceActivity::class.java).setData(Uri.parse("consigliere://voice"))
            } else {
                Intent(context, ChatActivity::class.java)
                    .setData(Uri.parse("consigliere://chat/voice")).putExtra(ChatActivity.EXTRA_VOICE, true)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var recorder: VoiceRecorder? = null
    private var speech: SpeechRecognizer? = null
    private var phase by mutableStateOf("rec") // rec (gravando áudio), live (transcrevendo), think, done
    private var seconds by mutableIntStateOf(0)
    private var heard by mutableStateOf("")
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

    /** Primeiro tenta transcrever no aparelho (grátis, sem IA); se não houver, grava o áudio. */
    private fun start() {
        answer = null
        heard = ""
        seconds = 0
        if (!startLive()) startRecorder()
    }

    private fun startRecorder() {
        if (!Assistant.canHear(this)) {
            Toast.makeText(this, "Sem reconhecimento de voz no aparelho e sem IA para ouvir o áudio", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val r = VoiceRecorder(this, forOpenAi = Settings(this).aiProvider == AiProvider.OPENAI)
        try {
            r.start()
            recorder = r
            phase = "rec"
        } catch (e: Exception) {
            r.cancel()
            Toast.makeText(this, "Não consegui usar o microfone", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startLive(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            return false
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it }
            }

            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: heard
                sendText(text)
            }

            override fun onError(error: Int) {
                if (phase != "live") return
                stopLive()
                when {
                    heard.isNotBlank() -> sendText(heard)
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Toast.makeText(this@VoiceActivity, "Não ouvi nada", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    // Serviço do aparelho falhou: grava o áudio para a IA.
                    else -> startRecorder()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        return try {
            speech = sr
            phase = "live"
            sr.startListening(intent)
            true
        } catch (e: Exception) {
            stopLive()
            false
        }
    }

    private fun stopLive() {
        speech?.let { runCatching { it.cancel() }; it.destroy() }
        speech = null
    }

    /** Texto transcrito no aparelho: vai para a IA se estiver no ar, senão para as regras. */
    private fun sendText(text: String) {
        if (phase != "live") return
        stopLive()
        if (text.isBlank()) {
            finish()
            return
        }
        think { Assistant.handleSpoken(it, text) }
    }

    private fun sendAudio() {
        val r = recorder ?: return
        recorder = null
        val audio = r.stop()
        if (audio == null) {
            Toast.makeText(this, "Áudio muito curto", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        think { Assistant.handleAudio(it, audio, r.mime) }
    }

    private var work: kotlinx.coroutines.Deferred<ChatMessage>? = null

    /** Roda fora da tela: se a pessoa fechar antes, o pedido continua e o resultado chega por notificação. */
    private fun think(block: suspend (android.content.Context) -> ChatMessage) {
        phase = "think"
        val app = applicationContext
        val job = com.trevisolli.agendawidget.smart.Background.scope.async { block(app) }
        work = job
        lifecycleScope.launch {
            answer = runCatching { job.await() }.getOrNull()
            phase = "done"
            work = null
        }
    }

    override fun onDestroy() {
        work?.let { job ->
            val app = applicationContext
            job.invokeOnCompletion { e ->
                if (e == null) runCatching { com.trevisolli.agendawidget.smart.VoiceQueue.notify(app, job.getCompleted().text) }
            }
        }
        recorder?.cancel()
        stopLive()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()

        // Cronômetro; para sozinho em 60 s.
        LaunchedEffect(phase) {
            while (phase == "rec") {
                delay(1000)
                if (recorder != null) seconds++
                if (seconds >= 60) sendAudio()
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
                    finish()
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
                        "live" -> {
                            Text(
                                heard.ifBlank { "Fale… ex.: \"amanhã médico às 15h para a Helena\"" },
                                style = if (heard.isBlank()) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                                initialValue = 1f, targetValue = 1.12f,
                                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
                            )
                            Box(
                                Modifier.size(96.dp).scale(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.error)
                                    .clickable { speech?.stopListening() },
                                contentAlignment = Alignment.Center,
                            ) { Text("➤", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onError) }
                            Text("🔴 Ouvindo · envia quando você parar de falar", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { finish() }) { Text("Cancelar") }
                        }
                        "rec" -> {
                            Text("Fale… ex.: \"amanhã médico às 15h para a Helena\"", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                                initialValue = 1f, targetValue = 1.12f,
                                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
                            )
                            Box(
                                Modifier.size(96.dp).scale(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.error).clickable { sendAudio() },
                                contentAlignment = Alignment.Center,
                            ) { Text("➤", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onError) }
                            Text("🔴 0:%02d · toque para enviar".format(seconds), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { finish() }) { Text("Cancelar") }
                        }
                        "think" -> {
                            CircularProgressIndicator()
                            Text("Entendendo…", style = MaterialTheme.typography.titleMedium)
                            var slow by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                delay(8000)
                                slow = true
                            }
                            if (slow) {
                                Text(
                                    "A IA está demorando. Pode fechar: eu termino e aviso por notificação.",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = { finish() }) { Text("Fechar e avisar depois") }
                            }
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
                                TextButton(onClick = { start() }) { Text("🎤 Outro") }
                                if (a != null) TextButton(onClick = { Support.report(this@VoiceActivity, a.text) }) { Text("⚑") }
                                TextButton(onClick = { finish() }) { Text("OK") }
                            }
                        }
                    }
                }
            }
        }
    }
}
