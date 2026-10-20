package com.trevisolli.agendawidget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.Range
import com.trevisolli.agendawidget.data.Snapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Lista por período (hoje, semana, mês), agrupada por dia, com caixinhas nas tarefas. */
@Composable
fun AgendaTab(
    snapshot: Snapshot,
    range: Range,
    onRange: (Range) -> Unit,
    doneNow: List<AgendaItem>,
    toggling: String?,
    onToggle: (AgendaItem, Boolean) -> Unit,
    onMove: (AgendaItem, LocalDate) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val locale = Locale("pt", "BR")
    val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    val doneIds = doneNow.mapNotNull { it.taskId }.toSet()
    val visible = snapshot.items.filter { range.includes(it, zone) && it.taskId !in doneIds }

    fun dayLabel(d: LocalDate): String = when (d) {
        today -> "Hoje"
        today.plusDays(1) -> "Amanhã"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, locale).removeSuffix("-feira")
            .replaceFirstChar { it.uppercase() } + ", " + d.format(DateTimeFormatter.ofPattern("dd/MM"))
    }

    val groups: List<Pair<String, List<AgendaItem>>> = buildList {
        val overdue = visible.filter { it.overdue }
        if (overdue.isNotEmpty()) add("Atrasadas" to overdue)
        visible.filterNot { it.overdue }
            .groupBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
            .toSortedMap()
            .forEach { (d, list) -> add(dayLabel(d) to list) }
    }

    // Arrastar: segura o item, aparece a barra de dias embaixo; solta num dia (ou toca nele).
    var moving by remember { mutableStateOf<AgendaItem?>(null) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val rowPos = remember { mutableMapOf<String, Offset>() }
    val chipRects = remember { mutableStateMapOf<LocalDate, Rect>() }
    val hovered = dragPos?.let { p -> chipRects.entries.firstOrNull { it.value.contains(p) }?.key }
    val context = LocalContext.current

    fun finish(target: LocalDate?) {
        val item = moving
        if (item != null && target != null) {
            onMove(item, target)
            moving = null
        }
        dragPos = null
    }

    fun movable(item: AgendaItem): Modifier {
        val key = item.moveKey ?: return Modifier
        return Modifier
            .onGloballyPositioned { rowPos[key] = it.positionInRoot() }
            .pointerInput(item) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { touch ->
                        moving = item
                        dragPos = (rowPos[key] ?: Offset.Zero) + touch
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragPos = dragPos?.plus(amount)
                    },
                    // Soltou fora de um dia: a barra continua aberta para tocar.
                    onDragEnd = { finish(dragPos?.let { p -> chipRects.entries.firstOrNull { it.value.contains(p) }?.key }) },
                    onDragCancel = { dragPos = null },
                )
            }
            .alpha(if (moving?.moveKey == key) 0.35f else 1f)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.entries.forEach { r ->
                    FilterChip(selected = range == r, onClick = { onRange(r) }, label = { Text(r.label) })
                }
            }
            if (visible.any { it.moveKey != null }) {
                Text(
                    "Segure um item para mudar de dia",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (groups.isEmpty() && doneNow.isEmpty()) {
            item { Text("Nada neste período 🍃", modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyLarge) }
        }
        groups.forEach { (label, list) ->
            item {
                Text(
                    label,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (label == "Atrasadas") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(list) { item ->
                if (item.taskId != null || item.dateOnly && item.source.name != "CALENDAR") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = movable(item).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(if (item.overdue) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        if (item.taskId != null) {
                            Checkbox(checked = false, enabled = toggling != item.taskId, onCheckedChange = { onToggle(item, true) })
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                        Column {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                item.source.label + if (!item.dateOnly) " · " + Instant.ofEpochMilli(item.startMillis).atZone(zone).format(hhmm) else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = movable(item).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(Color(item.color).copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Box(
                            Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(14.dp))
                                .background(Color(item.color).copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (item.dateOnly) "dia" else Instant.ofEpochMilli(item.startMillis).atZone(zone).format(hhmm),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        if (doneNow.isNotEmpty()) {
            item {
                Text("Concluídas agora", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            }
            items(doneNow) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, enabled = toggling != t.taskId, onCheckedChange = { onToggle(t, false) })
                    Text(
                        t.title,
                        textDecoration = TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
        if (moving != null) item { Spacer(Modifier.height(120.dp)) }
    }

    val current = moving
    if (current != null) {
        val days = (0L..13L).map { today.plusDays(it) }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Mover \"${current.title}\" para…",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { moving = null; dragPos = null }) { Text("Cancelar") }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(days) { d ->
                        val selected = hovered == d
                        Surface(
                            onClick = { finish(d) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.onGloballyPositioned { chipRects[d] = it.boundsInRoot() },
                        ) {
                            Column(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val top = when (d) {
                                    today -> "Hoje"
                                    today.plusDays(1) -> "Amanhã"
                                    else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".").replaceFirstChar { it.uppercase() }
                                }
                                val ink = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                Text(top, style = MaterialTheme.typography.labelLarge, color = ink)
                                Text(d.format(DateTimeFormatter.ofPattern("dd/MM")), style = MaterialTheme.typography.bodySmall, color = ink)
                            }
                        }
                    }
                    item {
                        Surface(
                            onClick = {
                                val start = maxOf(today, Instant.ofEpochMilli(current.startMillis).atZone(zone).toLocalDate())
                                android.app.DatePickerDialog(context, { _, y, m, day -> finish(LocalDate.of(y, m + 1, day)) }, start.year, start.monthValue - 1, start.dayOfMonth).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📅", style = MaterialTheme.typography.labelLarge)
                                Text("Outra", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // O item "flutuando" sob o dedo.
        dragPos?.let { p ->
            val density = LocalDensity.current
            Surface(
                modifier = Modifier.offset {
                    IntOffset((p.x - origin.x).roundToInt() - with(density) { 40.dp.roundToPx() }, (p.y - origin.y).roundToInt() - with(density) { 28.dp.roundToPx() })
                }.widthIn(max = 260.dp),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 10.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
    }
}
