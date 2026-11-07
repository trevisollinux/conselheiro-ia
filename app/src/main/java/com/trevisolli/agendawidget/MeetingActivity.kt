package com.trevisolli.agendawidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.trevisolli.agendawidget.smart.MeetingService
import com.trevisolli.agendawidget.smart.MeetingState
import com.trevisolli.agendawidget.ui.ConsigliereTheme

/** Tela do modo reunião: grava a conversa e, ao parar, o Consigliere resume e cria os combinados. */
class MeetingActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) MeetingService.start(this) else {
            Toast.makeText(this, "Sem o microfone não dá para gravar", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MeetingState.state.value.recording) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                MeetingService.start(this)
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        setContent {
            ConsigliereTheme {
                Surface(Modifier.fillMaxSize()) {
                    val s by MeetingState.state.collectAsState()
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Gravando a conversa", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "%d:%02d".format(s.seconds / 60, s.seconds % 60),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text(
                            "Pode apagar a tela ou ir para outro app: continua gravando. Até ${MeetingState.MAX_SECONDS / 60} min.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.silentSeconds >= 15) {
                            Text(
                                "Não estou ouvindo nada. Durante ligações, o Android costuma bloquear o microfone para outros apps.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Box(
                            Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error).clickable {
                                MeetingService.stop(this@MeetingActivity)
                                Toast.makeText(this@MeetingActivity, "Resumindo… aviso por notificação", Toast.LENGTH_LONG).show()
                                finish()
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text("⏹", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onError) }
                        Text("Parar e resumir", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { finish() }) { Text("Continuar gravando em segundo plano") }
                    }
                }
            }
        }
    }
}
