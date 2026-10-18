package com.trevisolli.agendawidget.data

import android.content.Context
import com.trevisolli.agendawidget.smart.NotionScanner
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
        // Busca um mês inteiro; o widget e a aba Agenda filtram por dia/semana/mês.
        val days = 31
        val items = mutableListOf<AgendaItem>()
        val errors = mutableListOf<String>()

        try {
            items += CalendarSource.fetch(context, days)
        } catch (e: Exception) {
            errors += "Agenda: ${e.message?.take(120)}"
        }
        if (settings.todoistToken.isNotBlank()) {
            try {
                items += TodoistSource.fetch(settings.todoistToken, days)
            } catch (e: Exception) {
                errors += "Todoist: ${e.message?.take(120)}"
            }
        }
        if (settings.city.isNotBlank()) {
            try {
                WeatherSource.refresh(context)
            } catch (e: Exception) {
                errors += "Tempo: ${e.message?.take(80)}"
            }
        }
        if (settings.widgetTheme == WidgetTheme.ART) {
            // Falha de arte não aparece no widget: ele cai para a paisagem e o aviso fica nas Configurações.
            try {
                ArtSource.refreshIfDue(context)
            } catch (e: Exception) {
                settings.artError = e.message?.take(120).orEmpty()
            }
        }
        if (settings.notionToken.isNotBlank()) {
            try {
                NotionScanner.scan(context)
            } catch (e: Exception) {
                errors += "Notion: ${e.message?.take(120)}"
            }
        }
        if (settings.googleTasksEnabled) {
            try {
                items += GoogleTasksSource.fetch(context, days)
            } catch (e: Exception) {
                errors += "Google Tasks: ${e.message?.take(120)}"
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
