package com.trevisolli.conselheirosticker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.trevisolli.conselheirosticker.data.PackStore
import com.trevisolli.conselheirosticker.editor.EditorScreen
import com.trevisolli.conselheirosticker.editor.EditorSource
import com.trevisolli.conselheirosticker.editor.Fonts
import com.trevisolli.conselheirosticker.ui.PackListScreen
import com.trevisolli.conselheirosticker.ui.PackScreen
import com.trevisolli.conselheirosticker.ui.StickerTheme

private sealed interface Screen {
    data object Packs : Screen
    data class OnePack(val id: String) : Screen
    data class Editor(val packId: String, val source: EditorSource) : Screen
}

class MainActivity : ComponentActivity() {
    /** Foto recebida pelo "Compartilhar" de outro app, esperando a escolha do pacote. */
    private var shared by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shared = sharedImage(intent)
        val store = PackStore(this)
        val fonts = Fonts(this)
        // Mantém o backup semanal agendado (se o usuário ligou).
        com.trevisolli.conselheirosticker.data.AutoBackup.schedule(this)

        setContent {
            StickerTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Packs) }
                var refresh by remember { mutableIntStateOf(0) }
                var pickFor by remember { mutableStateOf<String?>(null) }

                var pickAnimated by remember { mutableStateOf(false) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    val packId = pickFor
                    if (uri != null && packId != null) {
                        screen = Screen.Editor(packId, if (pickAnimated) EditorSource.Animation(uri) else EditorSource.Photo(uri))
                    }
                }

                when (val s = screen) {
                    Screen.Packs -> PackListScreen(
                        store = store,
                        refreshKey = refresh,
                        pendingPhoto = shared != null,
                        onOpen = { p ->
                            val photo = shared
                            if (photo != null) {
                                shared = null
                                screen = Screen.Editor(
                                    p.id, if (isAnimatedMedia(photo)) EditorSource.Animation(photo) else EditorSource.Photo(photo),
                                )
                            } else {
                                screen = Screen.OnePack(p.id)
                            }
                        },
                    )
                    is Screen.OnePack -> {
                        BackHandler { screen = Screen.Packs; refresh++ }
                        PackScreen(
                            store = store,
                            packId = s.id,
                            refreshKey = refresh,
                            onBack = { screen = Screen.Packs; refresh++ },
                            onAddPhoto = {
                                pickFor = s.id
                                pickAnimated = false
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onAddAnimation = { video ->
                                pickFor = s.id
                                pickAnimated = true
                                picker.launch(
                                    PickVisualMediaRequest(
                                        if (video) ActivityResultContracts.PickVisualMedia.VideoOnly
                                        else ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif")
                                    )
                                )
                            },
                            onAddText = { screen = Screen.Editor(s.id, EditorSource.TextOnly) },
                            onEdit = { file -> screen = Screen.Editor(s.id, EditorSource.Existing(file)) },
                        )
                    }
                    is Screen.Editor -> EditorScreen(
                        packId = s.packId,
                        source = s.source,
                        store = store,
                        fonts = fonts,
                        onDone = { screen = Screen.OnePack(s.packId); refresh++ },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImage(intent)?.let { shared = it }
    }

    /** GIF ou vídeo vira figurinha animada; o resto, parada. */
    private fun isAnimatedMedia(uri: Uri): Boolean {
        val type = contentResolver.getType(uri).orEmpty()
        return type == "image/gif" || type.startsWith("video/")
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
