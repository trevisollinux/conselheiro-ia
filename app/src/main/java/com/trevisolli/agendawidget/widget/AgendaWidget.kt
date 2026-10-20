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
import androidx.glance.ColorFilter
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
import com.trevisolli.agendawidget.data.Range
import com.trevisolli.agendawidget.data.WidgetTheme
import com.trevisolli.agendawidget.smart.ChatStore
import com.trevisolli.agendawidget.ChatActivity
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.TodoistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.trevisolli.agendawidget.data.Snapshot
import com.trevisolli.agendawidget.data.Source

private val SNAPSHOT_KEY = stringPreferencesKey("snapshot")
private val BACKGROUND = Color(0xFF202124)

/** Cores do texto: escuras sobre cenas claras (dia), claras sobre cenas escuras (noite). */
private data class Ink(
    val primary: Color,
    val secondary: Color,
    val summary: Color,
    val overdue: Color,
    val suggestion: Color,
    val footer: Color,
)

private val LIGHT_INK = Ink(
    primary = Color(0xFFFFFFFF), secondary = Color(0xFFD6DEE5), summary = Color(0xFFE8C877),
    overdue = Color(0xFFFF9C93), suggestion = Color(0xFFFFE08A), footer = Color(0xCCE8EEF2),
)
private val DARK_INK = Ink(
    primary = Color(0xFF2A2124), secondary = Color(0xFF6A5A5D), summary = Color(0xFF8A2B3D),
    overdue = Color(0xFFB3261E), suggestion = Color(0xFF8A5A00), footer = Color(0xCC3D4E47),
)

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
            val range = Settings(context).widgetRange.name
            GlanceAppWidgetManager(context).getGlanceIds(AgendaWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) {
                    it[SNAPSHOT_KEY] = json
                    it[RANGE_KEY] = range
                }
                widget.update(context, id)
            }
        }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

private val TASK_ID = ActionParameters.Key<String>("taskId")
private val MOVE_KEY = ActionParameters.Key<String>("moveKey")
private val RANGE_PARAM = ActionParameters.Key<String>("range")
private val RANGE_KEY = stringPreferencesKey("range")

/** Toque em Hoje/Semana/Mês no widget. */
class RangeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val range = Range.entries.firstOrNull { it.name == parameters[RANGE_PARAM] } ?: return
        Settings(context).widgetRange = range
        updateAppWidgetState(context, glanceId) { it[RANGE_KEY] = range.name }
        AgendaWidget().update(context, glanceId)
    }
}

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

/** » no widget: atrasada vem para hoje, o resto vai para o dia seguinte. */
class MoveAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val key = parameters[MOVE_KEY] ?: return
        val current = AgendaRepository.load(context)
        val item = current.items.firstOrNull { it.moveKey == key } ?: return
        val to = com.trevisolli.agendawidget.data.Mover.nextDay(item)
        val result = withContext(Dispatchers.IO) {
            runCatching { com.trevisolli.agendawidget.data.Mover.move(context, item, to) }
        }
        val next = if (result.isSuccess) {
            val moved = com.trevisolli.agendawidget.data.Mover.shifted(item, to)
            current.copy(items = (current.items - item + moved).sortedBy { it.startMillis })
        } else {
            current.copy(errors = current.errors + "Mover: ${result.exceptionOrNull()?.message?.take(80)}")
        }
        AgendaWidget.push(context, next)
        RefreshWorker.runNow(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Atualiza compromissos e troca a arte: obra nova ou outra paisagem.
        val s = Settings(context)
        s.artFetchedAt = 0
        s.sceneNonce = s.sceneNonce + 1
        RefreshWorker.runNow(context)
    }
}

@Composable
private fun Content(snapshot: Snapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val settings = Settings(context)
    Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp)) {
        // Fundo do tema escolhido; a paisagem muda a cada atualização (semente = meia hora corrente).
        val density = context.resources.displayMetrics.density
        val scale = (720f / (size.width.value * density)).coerceAtMost(1f)
        val w = (size.width.value * density * scale).toInt()
        val h = (size.height.value * density * scale).toInt()
        val art = Backgrounds.render(context, w, h, java.time.LocalTime.now().hour, 24 * density * scale)
        Image(
            provider = ImageProvider(art.bitmap),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize(),
        )
        // Legenda só para obras de arte (título — artista).
        Body(
            snapshot, if (art.dark) LIGHT_INK else DARK_INK,
            art.label.takeIf { settings.widgetTheme == WidgetTheme.ART && it.isNotBlank() },
            frosted = settings.widgetTheme == WidgetTheme.ART || settings.widgetTheme == WidgetTheme.LANDSCAPE,
        )
    }
}

@Composable
private fun Body(snapshot: Snapshot, ink: Ink, footer: String?, frosted: Boolean = false) {
    val panel = if (ink === LIGHT_INK) Color(0x59141114) else Color(0x80FFFBF4)
    val context = LocalContext.current
    val settings = Settings(context)
    val ranges = settings.visibleRanges
    val range = Range.entries.firstOrNull { it.name == currentState<Preferences>()[RANGE_KEY] }?.takeIf { it in ranges } ?: settings.widgetRange
    val hour = java.time.LocalTime.now().hour
    // Saudação que cabe: "Buon pomeriggio, Gustavo" → "Ciao, Gustavo" → "Buon pomeriggio".
    val salute = when (hour) {
        in 5..11 -> "Buongiorno"
        in 12..17 -> "Buon pomeriggio"
        else -> "Buonasera"
    }
    val name = settings.userName.trim().substringBefore(' ')
    val room = (LocalSize.current.width.value - 32 - 40) / 11.5f
    val greeting = listOf("$salute, $name", "Ciao, $name", salute)
        .filter { name.isNotBlank() || !it.contains(",") }
        .firstOrNull { it.length <= room } ?: if (name.isNotBlank()) "Ciao, $name" else salute
    val locale = java.util.Locale("pt", "BR")
    val dateLine = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMM", locale))
        .replace(".", "") + (com.trevisolli.agendawidget.data.WeatherSource.load(context)?.label?.lowercase()?.let { " · $it" } ?: "")
    val openChat = chatIntent(context)
    val visible = snapshot.items.filter { range.includes(it) }

    // Glance desenha no máximo 10 filhos por coluna: as partes ficam em colunas próprias.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Topo: saudação grande + resumo do dia (atualiza sozinho)
        Column(modifier = GlanceModifier.fillMaxWidth()) {
        // Linha pequena: data e tempo, com ↻ sempre visível à direita.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dateLine,
                style = TextStyle(color = ColorProvider(ink.secondary), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Atualizar",
                colorFilter = ColorFilter.tint(ColorProvider(ink.secondary)),
                modifier = GlanceModifier.size(18.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        // Mascote + saudação grande em serifa.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.mascot),
                contentDescription = null,
                modifier = GlanceModifier.size(32.dp).clickable(actionStartActivity<MainActivity>()),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = greeting,
                style = TextStyle(
                    color = ColorProvider(ink.primary), fontSize = 21.sp, fontWeight = FontWeight.Bold,
                    fontFamily = androidx.glance.text.FontFamily.Serif,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
            )
        }
        if (snapshot.summary.isNotBlank()) {
            // O conselho do dia em destaque: balão com barra colorida à esquerda.
            Spacer(GlanceModifier.height(6.dp))
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ink.summary.copy(alpha = 0.16f))
                    .cornerRadius(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier.width(4.dp).height(56.dp).background(ink.summary).cornerRadius(2.dp),
                ) {}
                Text(
                    text = snapshot.summary,
                    style = TextStyle(color = ColorProvider(ink.summary), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 4,
                    modifier = GlanceModifier.defaultWeight().padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        if (snapshot.pendingSuggestions > 0) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = "📌 ${snapshot.pendingSuggestions} " +
                    if (snapshot.pendingSuggestions == 1) "sugestão para revisar" else "sugestões para revisar",
                style = TextStyle(color = ColorProvider(ink.suggestion), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        }

        }

        // Meio: período + lista (em painel fosco quando o fundo é imagem)
        Spacer(GlanceModifier.height(8.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxWidth()
                .background(if (frosted) panel else Color.Transparent).cornerRadius(18.dp)
                .padding(horizontal = if (frosted) 10.dp else 0.dp, vertical = if (frosted) 8.dp else 0.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ranges.forEach { r ->
                val selected = r == range
                Text(
                    text = r.label,
                    style = TextStyle(
                        color = ColorProvider(if (selected) ink.primary else ink.secondary),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    ),
                    modifier = GlanceModifier
                        .background(if (selected) ink.primary.copy(alpha = 0.16f) else Color.Transparent)
                        .cornerRadius(12.dp)
                        .padding(horizontal = if (ranges.size > 4) 7.dp else 10.dp, vertical = 3.dp)
                        .clickable(actionRunCallback<RangeAction>(actionParametersOf(RANGE_PARAM to r.name))),
                )
                Spacer(GlanceModifier.width(4.dp))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        if (visible.isEmpty()) {
            Box(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                Text(
                    text = if (snapshot.updatedAt == 0L) "Toque para configurar" else "Nada neste período 🍃",
                    style = TextStyle(color = ColorProvider(ink.primary), fontSize = 15.sp),
                    modifier = GlanceModifier.padding(top = 8.dp).clickable(actionStartActivity<MainActivity>()),
                )
            }
        } else {
            val rows = groupRows(visible)
            LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(rows) { row ->
                    when (row) {
                        is Row_.Header -> HeaderRow(row, ink)
                        is Row_.Item -> Column {
                            ItemRow(row.item, ink, row.today)
                            Spacer(GlanceModifier.height(2.dp))
                        }
                    }
                }
            }
        }

        }

        // Embaixo: avisos, última resposta e a barra de conversa
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            if (snapshot.errors.isNotEmpty()) {
                Text(
                    text = snapshot.errors.joinToString("; "),
                    style = TextStyle(color = ColorProvider(ink.overdue), fontSize = 11.sp),
                    maxLines = 1,
                )
            }
            if (footer != null) {
                Text(
                    text = footer,
                    style = TextStyle(
                        color = ColorProvider(ink.footer), fontSize = 9.sp,
                        textAlign = androidx.glance.text.TextAlign.End,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            ChatBar(context, ink.primary.copy(alpha = 0.14f), ink.secondary)
        }
    }
}

/** Linha da lista do widget: cabeçalho de dia ou item. */
private sealed class Row_ {
    data class Header(val label: String, val today: Boolean, val overdue: Boolean) : Row_()
    data class Item(val item: AgendaItem, val today: Boolean) : Row_()
}

private fun groupRows(items: List<AgendaItem>): List<Row_> {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val rows = mutableListOf<Row_>()
    val overdue = items.filter { it.overdue }
    if (overdue.isNotEmpty()) {
        rows += Row_.Header("ATRASADAS", today = false, overdue = true)
        overdue.forEach { rows += Row_.Item(it, today = false) }
    }
    items.filterNot { it.overdue }
        .groupBy { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
        .toSortedMap()
        .forEach { (date, list) ->
            val isToday = date == today
            rows += Row_.Header(Format.dayHeader(date), today = isToday, overdue = false)
            list.forEach { rows += Row_.Item(it, today = isToday) }
        }
    return rows
}

@Composable
private fun HeaderRow(h: Row_.Header, ink: Ink) {
    // Título do dia com filete fino ao lado, como no app.
    val color = if (h.overdue) ink.overdue else if (h.today) ink.summary else ink.secondary
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = h.label,
            style = TextStyle(color = ColorProvider(color), fontSize = if (h.today) 13.sp else 11.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(8.dp))
        Box(GlanceModifier.defaultWeight().height(1.dp).background(color.copy(alpha = 0.3f))) {}
    }
}

@Composable
private fun ItemRow(item: AgendaItem, ink: Ink, today: Boolean = false) {
    // Uma linha: horário fixo à esquerda, marcador, título e » na ponta direita.
    val highlight = when {
        item.overdue -> ink.overdue.copy(alpha = 0.12f)
        today -> ink.summary.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.Instant.ofEpochMilli(item.startMillis).atZone(zone)
    val timeText = when {
        item.overdue -> start.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"))
        item.dateOnly -> if (item.source == Source.CALENDAR) "dia" else ""
        else -> start.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
    var modifier = GlanceModifier.fillMaxWidth().background(highlight).cornerRadius(12.dp)
        .padding(start = 4.dp, end = 2.dp, top = 5.dp, bottom = 5.dp)
    openIntent(item)?.let { modifier = modifier.clickable(actionStartActivity(it)) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = timeText,
            style = TextStyle(
                color = ColorProvider(if (item.overdue) ink.overdue else ink.secondary),
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            modifier = GlanceModifier.width(40.dp),
        )
        Box(modifier = GlanceModifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
            if (item.taskId != null) {
                // Tarefa do Todoist: círculo tocável para concluir.
                Image(
                    provider = ImageProvider(R.drawable.ic_task_circle),
                    contentDescription = "Concluir",
                    modifier = GlanceModifier.size(16.dp)
                        .clickable(actionRunCallback<CompleteAction>(actionParametersOf(TASK_ID to item.taskId))),
                )
            } else {
                Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(Color(item.color))) {}
            }
        }
        Text(
            text = item.title,
            style = TextStyle(
                color = ColorProvider(ink.primary),
                fontSize = if (today || item.overdue) 15.sp else 14.sp,
                fontWeight = if (today || item.overdue) FontWeight.Bold else FontWeight.Medium,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (item.moveKey != null) {
            // » joga para o próximo dia (atrasada vem para hoje).
            Text(
                text = "»",
                style = TextStyle(color = ColorProvider(ink.secondary.copy(alpha = 0.7f)), fontSize = 15.sp),
                modifier = GlanceModifier.padding(start = 8.dp, end = 4.dp)
                    .clickable(actionRunCallback<MoveAction>(actionParametersOf(MOVE_KEY to item.moveKey))),
            )
        }
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
