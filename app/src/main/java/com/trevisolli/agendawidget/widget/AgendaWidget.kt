package com.trevisolli.agendawidget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.layout.ContentScale
import java.net.URLEncoder
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.trevisolli.agendawidget.MainActivity
import com.trevisolli.agendawidget.R
import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.TodoistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.trevisolli.agendawidget.data.Snapshot
import com.trevisolli.agendawidget.data.Source

private val SNAPSHOT_KEY = stringPreferencesKey("snapshot")
private val BACKGROUND = Color(0xFF202124)
private val PRIMARY_TEXT = Color(0xFFFFFFFF)
private val SECONDARY_TEXT = Color(0xFFBDC1C6)
private val OVERDUE_TEXT = Color(0xFFF28B82)
private val SUMMARY_TEXT = Color(0xFFF2C14E)
private val TRACK_TEXT = Color(0xCCEEE6D3)
private val SUGGESTION_TEXT = Color(0xFFFDD663)

class AgendaWidget : GlanceAppWidget() {

    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Widget recém-adicionado: começa com o cache e pede uma atualização.
        updateAppWidgetState(context, id) { prefs ->
            if (prefs[SNAPSHOT_KEY] == null) prefs[SNAPSHOT_KEY] = AgendaRepository.load(context).toJson()
        }
        RefreshWorker.schedule(context)

        provideContent {
            val snapshot = Snapshot.fromJson(currentState<Preferences>()[SNAPSHOT_KEY])
            Content(snapshot)
        }
    }

    companion object {
        suspend fun push(context: Context, snapshot: Snapshot) {
            val json = snapshot.toJson()
            val widget = AgendaWidget()
            GlanceAppWidgetManager(context).getGlanceIds(AgendaWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { it[SNAPSHOT_KEY] = json }
                widget.update(context, id)
            }
        }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

private val TASK_ID = ActionParameters.Key<String>("taskId")

/** Toque no círculo: conclui no Todoist e some da lista na hora. */
class CompleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TASK_ID] ?: return
        val token = Settings(context).todoistToken
        if (token.isBlank()) return
        val ok = withContext(Dispatchers.IO) { runCatching { TodoistSource.close(token, id) }.isSuccess }
        val current = AgendaRepository.load(context)
        if (ok) current.items.firstOrNull { it.taskId == id }?.let { Settings(context).logCompletion(it.title) }
        val next = if (ok) {
            current.copy(items = current.items.filterNot { it.taskId == id })
        } else {
            current.copy(errors = current.errors + "Todoist: não consegui concluir a tarefa")
        }
        AgendaWidget.push(context, next)
        RefreshWorker.runNow(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.runNow(context)
    }
}

@Composable
private fun Content(snapshot: Snapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val settings = Settings(context)
    val opacity = settings.widgetOpacity / 100f
    Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp)) {
        if (settings.jazzBackground) {
            // Arte nova a cada atualização (semente = meia hora corrente), paleta conforme a hora do dia.
            val density = context.resources.displayMetrics.density
            val scale = (720f / (size.width.value * density)).coerceAtMost(1f)
            val w = (size.width.value * density * scale).toInt()
            val h = (size.height.value * density * scale).toInt()
            val seed = System.currentTimeMillis() / 1_800_000L + snapshot.updatedAt / 1_800_000L
            val art = JazzArt.render(w, h, seed, java.time.LocalTime.now().hour, opacity, 24 * density * scale)
            Image(
                provider = ImageProvider(art),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
        } else {
            Box(modifier = GlanceModifier.fillMaxSize().background(BACKGROUND.copy(alpha = opacity))) {}
        }
        Body(snapshot)
    }
}

@Composable
private fun Body(snapshot: Snapshot) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_compass),
                contentDescription = null,
                modifier = GlanceModifier.size(18.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = "ConsigliereIA",
                style = TextStyle(color = ColorProvider(SECONDARY_TEXT), fontSize = 14.sp),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
            )
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Atualizar",
                modifier = GlanceModifier.size(20.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        if (snapshot.summary.isNotBlank()) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = snapshot.summary,
                style = TextStyle(color = ColorProvider(SUMMARY_TEXT), fontSize = 13.sp),
                maxLines = 2,
            )
        }
        if (snapshot.pendingSuggestions > 0) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = "📌 ${snapshot.pendingSuggestions} " +
                    if (snapshot.pendingSuggestions == 1) "sugestão para revisar" else "sugestões para revisar",
                style = TextStyle(color = ColorProvider(SUGGESTION_TEXT), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        }
        Spacer(GlanceModifier.height(6.dp))

        if (snapshot.items.isEmpty()) {
            Text(
                text = if (snapshot.updatedAt == 0L) "Toque para configurar" else "Nada nos próximos dias",
                style = TextStyle(color = ColorProvider(PRIMARY_TEXT), fontSize = 16.sp),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(snapshot.items) { item -> ItemRow(item) }
            }
        }

        val track = JazzArt.trackOfTheDay()
        Text(
            text = "♪ $track",
            style = TextStyle(color = ColorProvider(TRACK_TEXT), fontSize = 11.sp),
            maxLines = 1,
            modifier = GlanceModifier.clickable(
                actionStartActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + URLEncoder.encode(track, "UTF-8").replace("+", "%20")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ),
            ),
        )

        if (snapshot.errors.isNotEmpty()) {
            Text(
                text = snapshot.errors.joinToString("; "),
                style = TextStyle(color = ColorProvider(OVERDUE_TEXT), fontSize = 11.sp),
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ItemRow(item: AgendaItem) {
    var modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp)
    openIntent(item)?.let { modifier = modifier.clickable(actionStartActivity(it)) }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.taskId != null) {
                // Tarefa do Todoist: círculo tocável para concluir.
                Image(
                    provider = ImageProvider(R.drawable.ic_task_circle),
                    contentDescription = "Concluir",
                    modifier = GlanceModifier.size(18.dp)
                        .clickable(actionRunCallback<CompleteAction>(actionParametersOf(TASK_ID to item.taskId))),
                )
                Spacer(GlanceModifier.width(6.dp))
            } else {
                Box(
                    modifier = GlanceModifier.size(10.dp).cornerRadius(5.dp).background(Color(item.color)),
                ) {}
                Spacer(GlanceModifier.width(8.dp))
            }
            Text(
                text = item.title,
                style = TextStyle(
                    color = ColorProvider(PRIMARY_TEXT),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
        Text(
            text = Format.subtitle(item),
            style = TextStyle(
                color = ColorProvider(if (item.overdue) OVERDUE_TEXT else SECONDARY_TEXT),
                fontSize = 13.sp,
            ),
            maxLines = 1,
            modifier = GlanceModifier.padding(start = if (item.taskId != null) 24.dp else 18.dp),
        )
    }
}

private fun openIntent(item: AgendaItem): Intent? {
    val uri = item.openUri ?: return null
    return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (item.source == Source.CALENDAR) {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.startMillis)
            item.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        }
    }
}
