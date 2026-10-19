package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.Http
import com.trevisolli.agendawidget.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Lê as páginas do Notion editadas desde a última varredura e sugere os compromissos encontrados.
 * Só enxerga as páginas que o usuário conectou à integração (••• → Conexões).
 */
object NotionScanner {
    private const val API = "https://api.notion.com/v1"
    private const val VERSION = "2022-06-28"
    private const val MAX_PAGES = 15
    private val TEXT_BLOCKS = setOf(
        "paragraph", "bulleted_list_item", "numbered_list_item", "to_do", "toggle",
        "heading_1", "heading_2", "heading_3", "callout", "quote",
    )

    private data class Page(val id: String, val title: String, val editedAt: Long)

    private fun headers(token: String) = mapOf("Authorization" to "Bearer $token", "Notion-Version" to VERSION)

    /** Retorna quantas sugestões novas foram criadas. */
    fun scan(context: Context): Int {
        val settings = Settings(context)
        val token = settings.notionToken
        if (token.isBlank()) return 0
        val startedAt = System.currentTimeMillis()
        val since = settings.notionLastScan.takeIf { it > 0 } ?: (startedAt - 7L * 24 * 3600 * 1000)
        val today = LocalDate.now()
        var created = 0

        for (page in recentPages(token, since)) {
            for (line in lines(token, page.id)) {
                val r = RuleParser.parse(line)
                // Notas são intencionais: basta ter uma data (que não seja passada) ou um sinal forte.
                if (r.date == null && !RuleParser.isStrong(r)) continue
                if (r.date != null && r.date.isBefore(today)) continue
                if (!settings.markSeen((page.id + line).hashCode().toString())) continue

                val origin = "Notion · ${page.title}".take(60)
                val suggestion = (
                    if (settings.aiLightReady) {
                        try {
                            Brain.ai(context, line, origin, light = true)
                        } catch (e: Exception) {
                            Brain.rules(line, origin)
                        }
                    } else {
                        Brain.rules(line, origin)
                    }
                    ) ?: continue
                if (SuggestionStore.add(context, suggestion)) {
                    NotificationWatcher.notifySuggestion(context, suggestion)
                    created++
                }
            }
        }
        settings.notionLastScan = startedAt
        return created
    }

    private fun recentPages(token: String, since: Long): List<Page> {
        val body = JSONObject()
            .put("filter", JSONObject().put("property", "object").put("value", "page"))
            .put("sort", JSONObject().put("direction", "descending").put("timestamp", "last_edited_time"))
            .put("page_size", 25)
        val results = Http.postJson("$API/search", headers(token), body).optJSONArray("results") ?: return emptyList()
        val pages = mutableListOf<Page>()
        for (i in 0 until results.length()) {
            val p = results.getJSONObject(i)
            val edited = runCatching { OffsetDateTime.parse(p.getString("last_edited_time")).toInstant().toEpochMilli() }
                .getOrDefault(0L)
            if (edited < since) break
            pages += Page(p.getString("id"), titleOf(p), edited)
            if (pages.size >= MAX_PAGES) break
        }
        return pages
    }

    private fun titleOf(page: JSONObject): String {
        val props = page.optJSONObject("properties") ?: return "Sem título"
        for (key in props.keys()) {
            val prop = props.getJSONObject(key)
            if (prop.optString("type") == "title") {
                return richText(prop.optJSONArray("title")).ifBlank { "Sem título" }
            }
        }
        return "Sem título"
    }

    /** Texto de cada bloco da página (1º nível). Tarefas marcadas como feitas são ignoradas. */
    private fun lines(token: String, pageId: String): List<String> {
        val out = mutableListOf<String>()
        var cursor: String? = null
        var pagesRead = 0
        do {
            var url = "$API/blocks/" + URLEncoder.encode(pageId, "UTF-8") + "/children?page_size=100"
            if (cursor != null) url += "&start_cursor=" + URLEncoder.encode(cursor, "UTF-8")
            val res = Http.get(url, headers(token))
            val blocks = res.optJSONArray("results") ?: break
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                val type = block.optString("type")
                if (type !in TEXT_BLOCKS) continue
                val data = block.optJSONObject(type) ?: continue
                if (type == "to_do" && data.optBoolean("checked")) continue
                val text = richText(data.optJSONArray("rich_text")).trim()
                if (text.length >= 4) out += text
            }
            cursor = if (res.optBoolean("has_more")) res.optString("next_cursor").ifBlank { null } else null
            pagesRead++
        } while (cursor != null && pagesRead < 3)
        return out
    }

    /** Junta o texto; menções de data do Notion viram "dd/MM/yyyy HH:mm" para o parser entender. */
    private fun richText(arr: JSONArray?): String {
        if (arr == null) return ""
        val zone = ZoneId.systemDefault()
        return buildString {
            for (i in 0 until arr.length()) {
                val part = arr.getJSONObject(i)
                val date = part.optJSONObject("mention")?.optJSONObject("date")
                val start = date?.optString("start").orEmpty()
                if (start.isNotEmpty()) {
                    append(
                        if (start.length == 10) {
                            LocalDate.parse(start).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        } else {
                            runCatching {
                                OffsetDateTime.parse(start).atZoneSameInstant(zone)
                                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                            }.getOrDefault(part.optString("plain_text"))
                        },
                    )
                } else {
                    append(part.optString("plain_text"))
                }
            }
        }
    }
}
