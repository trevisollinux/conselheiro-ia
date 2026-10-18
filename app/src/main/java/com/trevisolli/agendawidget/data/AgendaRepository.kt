package com.trevisolli.agendawidget.data

import android.content.Context
import com.trevisolli.agendawidget.smart.Summary
import com.trevisolli.agendawidget.smart.SuggestionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AgendaRepository {
    private fun cacheFile(context: Context) = File(context.filesDir, "snapshot.json")

    fun load(context: Context): Snapshot =
        Snapshot.fromJson(cacheFile(context).takeIf { it.exists() }?.readText())

    /** Busca todas as fontes; uma fonte com erro não derruba as outras. */
    suspend fun refresh(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        val days = settings.days
        val items = mutableListOf<AgendaItem>()
        val errors = mutableListOf<String>()

        try {
            items += CalendarSource.fetch(context, days)
        } catch (e: Exception) {
            errors += "Agenda: ${e.message}"
        }
        if (settings.todoistToken.isNotBlank()) {
            try {
                items += TodoistSource.fetch(settings.todoistToken, days)
            } catch (e: Exception) {
                errors += "Todoist: ${e.message}"
            }
        }
        if (settings.googleTasksEnabled) {
            try {
                items += GoogleTasksSource.fetch(context, days)
            } catch (e: Exception) {
                errors += "Google Tasks: ${e.message}"
            }
        }

        // Atrasadas primeiro; depois por data, com itens de dia inteiro antes dos com hora.
        items.sortWith(compareBy<AgendaItem>({ !it.overdue }, { it.startMillis }, { !it.dateOnly }, { it.title }))
        val summary = Summary.build(context, items, errors)
        val snapshot = Snapshot(
            items = items,
            errors = errors,
            updatedAt = System.currentTimeMillis(),
            summary = summary,
            pendingSuggestions = SuggestionStore.all(context).size,
        )
        cacheFile(context).writeText(snapshot.toJson())
        snapshot
    }
}
