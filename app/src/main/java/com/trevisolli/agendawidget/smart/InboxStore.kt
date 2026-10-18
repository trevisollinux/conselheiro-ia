package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Mensagens recebidas (WhatsApp, e-mail…) vistas pelas notificações, guardadas só no aparelho por 2 dias.
 * Alimentam o resumo do dia e a conversa ("o que chegou de importante?").
 */
object InboxStore {
    data class Msg(val app: String, val sender: String, val text: String, val time: Long)

    private const val MAX = 200
    private const val KEEP_MS = 48 * 3600_000L
    private fun file(context: Context) = File(context.filesDir, "inbox.json")

    @Synchronized
    fun add(context: Context, m: Msg) {
        val now = System.currentTimeMillis()
        val list = all(context).filter { now - it.time < KEEP_MS }
        if (list.any { it.app == m.app && it.sender == m.sender && it.text == m.text }) return
        save(context, (list + m).takeLast(MAX))
    }

    @Synchronized
    fun all(context: Context): List<Msg> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Msg(o.getString("app"), o.optString("sender"), o.getString("text"), o.getLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun recent(context: Context, hours: Int): List<Msg> {
        val since = System.currentTimeMillis() - hours * 3600_000L
        return all(context).filter { it.time >= since }
    }

    /** "📨 12 no WhatsApp · 3 no Gmail" */
    fun countsLine(list: List<Msg>): String? {
        if (list.isEmpty()) return null
        return "📨 " + list.groupBy { it.app.substringBefore(" ·") }
            .entries.sortedByDescending { it.value.size }
            .joinToString(" · ") { "${it.value.size} no ${it.key}" }
    }

    /** Bloco para a IA: últimas mensagens resumidas. */
    fun promptBlock(list: List<Msg>): String {
        if (list.isEmpty()) return ""
        return "Mensagens recebidas (mais recentes por último):\n" +
            list.takeLast(30).joinToString("\n") { "- [${it.app}] ${it.sender}: ${it.text.replace('\n', ' ').take(140)}" } + "\n"
    }

    private fun save(context: Context, list: List<Msg>) {
        file(context).writeText(
            JSONArray().apply {
                list.forEach {
                    put(JSONObject().put("app", it.app).put("sender", it.sender).put("text", it.text).put("time", it.time))
                }
            }.toString(),
        )
    }
}
