package com.trevisolli.agendawidget.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.smart.ChatStore
import androidx.glance.layout.padding

/** Barra de conversa: escreva ou toque no microfone. Estreito, vira só o botão redondo. */
class VoiceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val size = LocalSize.current
            val dark = Backgrounds.darkMode(context, Settings(context))
            val panel = if (dark) Color(0xE61E2422) else Color(0xEBF7F5EF)
            val field = if (dark) Color(0x22FFFFFF) else Color(0x14000000)
            val hint = if (dark) Color(0xFFD6DEE5) else Color(0xFF3D4E47)
            if (size.width.value < 150) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MicButton(context, (minOf(size.width, size.height).value * 0.8f).dp)
                }
            } else {
                val last = ChatStore.all(context).lastOrNull { !it.fromUser }
                    ?.takeIf { System.currentTimeMillis() - it.time < 12 * 3600_000L && size.height.value >= 100 }
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(panel).cornerRadius(28.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (last != null) {
                        Text(
                            "🎩 " + last.text.lineSequence().first(),
                            style = TextStyle(color = ColorProvider(hint), fontSize = 12.sp),
                            maxLines = 2,
                            modifier = GlanceModifier.padding(start = 6.dp, bottom = 6.dp)
                                .clickable(actionStartActivity(chatIntent(context))),
                        )
                    }
                    ChatBar(context, field, hint, height = 44.dp)
                }
            }
        }
    }
}

class VoiceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VoiceWidget()
}
