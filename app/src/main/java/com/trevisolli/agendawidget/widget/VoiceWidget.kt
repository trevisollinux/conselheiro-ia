package com.trevisolli.agendawidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.VoiceActivity
import com.trevisolli.agendawidget.data.Settings

/**
 * Barra no estilo da busca do Google: mascote + "Consigliere" (na fonte do app), microfone e atalho para o app.
 * Tocar na barra abre a conversa para escrever. Estreito, vira só o botão do microfone.
 */
class VoiceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val size = LocalSize.current
            val dark = Backgrounds.darkMode(context, Settings(context))
            if (size.width.value < 120) {
                // Pequeno: o mascote num círculo (dá para saber de quem é), tocar abre o gravador.
                val d = (minOf(size.width, size.height).value * 0.86f).dp
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = GlanceModifier.size(d).cornerRadius(d / 2).background(Color(0xFF8A2B3D))
                            .clickable(actionStartActivity(VoiceActivity.intent(context))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(provider = ImageProvider(R.drawable.mascot), contentDescription = "Falar com o Consigliere", modifier = GlanceModifier.size(d * 0.78f))
                    }
                }
                return@provideContent
            }
            // Barra no estilo do Chrome: pílula externa, campo interno com mascote e nome, ícones ao lado.
            val outer = if (dark) Color(0xFF1E1A1D) else Color(0xFFF3EADC)
            val field = if (dark) Color(0xFF332B30) else Color(0xFFFFFBF4)
            val accent = if (dark) Color(0xFFE8C877) else Color(0xFF8A2B3D)
            val barHeight = minOf(size.height.value - 4, 60f).coerceAtLeast(40f).dp
            val inner = barHeight - 12.dp
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().height(barHeight)
                        .background(outer).cornerRadius(barHeight / 2)
                        .padding(start = 6.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = GlanceModifier.defaultWeight().height(inner)
                            .background(field).cornerRadius(inner / 2)
                            .padding(start = 6.dp, end = 12.dp)
                            .clickable(actionStartActivity(chatIntent(context))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.mascot),
                            contentDescription = null,
                            modifier = GlanceModifier.size(inner * 0.86f),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Image(
                            provider = ImageProvider(wordmark(context, accent)),
                            contentDescription = "Consigliere",
                            modifier = GlanceModifier.height(inner * 0.5f).width(inner * 0.5f * 5),
                        )
                    }
                    Spacer(GlanceModifier.width(10.dp))
                    Image(
                        provider = ImageProvider(R.drawable.ic_mic),
                        contentDescription = "Falar",
                        colorFilter = ColorFilter.tint(ColorProvider(accent)),
                        modifier = GlanceModifier.size(barHeight * 0.5f)
                            .clickable(actionStartActivity(VoiceActivity.intent(context))),
                    )
                    Spacer(GlanceModifier.width(14.dp))
                    Image(
                        provider = ImageProvider(R.drawable.ic_app_grid),
                        contentDescription = "Abrir o app",
                        modifier = GlanceModifier.size(barHeight * 0.42f).clickable(actionStartActivity<MainActivity>()),
                    )
                }
            }
        }
    }

    /** "Consigliere" desenhado com a fonte do app (o widget só conhece as fontes do sistema). */
    private fun wordmark(context: Context, color: Color): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.fraunces_bold)
            textSize = 72f
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
            )
        }
        val text = "Consigliere"
        val fm = paint.fontMetrics
        // Largura fixa (proporção 5:1) e texto à esquerda: a imagem estica sem deformar o nome.
        val h = (fm.descent - fm.ascent).toInt() + 4
        val w = maxOf(paint.measureText(text).toInt() + 8, h * 5)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 2f, -fm.ascent + 2, paint)
        return bmp
    }
}

class VoiceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VoiceWidget()
}
