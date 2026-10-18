package com.trevisolli.agendawidget.ui

import androidx.compose.foundation.background
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
        }
        if (groups.isEmpty() && doneNow.isEmpty()) {
            item { Text("Nada neste período 🍃", modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyLarge) }
        }
        groups.forEach { (label, list) ->
            item {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (label == "Atrasadas") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(list) { item ->
                if (item.taskId != null || item.dateOnly && item.source.name != "CALENDAR") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        Spacer(Modifier.width(18.dp))
                        Box(Modifier.size(10.dp).background(Color(item.color), CircleShape))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (item.dateOnly) "dia todo" else Instant.ofEpochMilli(item.startMillis).atZone(zone).format(hhmm),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
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
    }
}
