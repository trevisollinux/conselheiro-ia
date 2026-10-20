package com.trevisolli.agendawidget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.trevisolli.agendawidget.ChatActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.VoiceActivity

internal val MIC_GREEN = Color(0xFF8A2B3D)

internal fun chatIntent(context: Context): Intent = Intent(context, ChatActivity::class.java)
    .setData(Uri.parse("consigliere://chat/text")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Barra "escreva ou fale" usada nos dois widgets: caixa de texto abre a conversa, o microfone abre o gravador. */
@Composable
internal fun ChatBar(context: Context, field: Color, hint: Color, height: Dp = 42.dp) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .background(field)
                .cornerRadius(height / 2)
                .padding(horizontal = 16.dp, vertical = ((height.value - 18) / 2).coerceAtLeast(6f).dp)
                .clickable(actionStartActivity(chatIntent(context))),
        ) {
            Text(
                text = "Escreva ou fale com o Consigliere…",
                style = TextStyle(color = ColorProvider(hint), fontSize = 13.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        MicButton(context, height)
    }
}

@Composable
internal fun MicButton(context: Context, size: Dp) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .background(MIC_GREEN)
            .cornerRadius(size / 2)
            .clickable(actionStartActivity(VoiceActivity.intent(context))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_mic),
            contentDescription = "Falar",
            colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
            modifier = GlanceModifier.size(size * 0.5f),
        )
    }
}
