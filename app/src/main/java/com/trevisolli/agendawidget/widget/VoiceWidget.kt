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
import com.trevisolli.agendawidget.VoiceActivity

/** Widget-gravador: um botão redondo; toque, fale e o Consigliere anota. */
class VoiceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val intent = VoiceActivity.intent(context)
        provideContent {
            val size = LocalSize.current
            val side = minOf(size.width, size.height)
            val circle = (side.value * 0.86f).dp
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = GlanceModifier
                        .size(circle)
                        .cornerRadius(circle / 2)
                        .background(Color(0xFF3E7C59))
                        .clickable(actionStartActivity(intent)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_mic),
                            contentDescription = "Falar",
                            colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                            modifier = GlanceModifier.size((circle.value * 0.42f).dp),
                        )
                        if (side.value >= 90) {
                            Text("Falar", style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
        }
    }
}

class VoiceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VoiceWidget()
}
