package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Diagnóstico da leitura de notificações: o que chegou e o que o Consigliere fez com cada uma. */
object NotifLog {
    data class Entry(val time: Long, val from: String, val text: String, val result: String)

    private const val MAX = 30
    private fun file(context: Context) = File(context.filesDir, "notif_log.json")

    @Synchronized
    fun all(context: Context): List<Entry> = runCatching {
        val arr = JSONArray(file(context).readText())
        List(arr.length()) { arr.getJSONObject(it) }.map { Entry(it.getLong("t"), it.getString("f"), it.getString("x"), it.getString("r")) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, from: String, text: String, result: String) {
        val list = listOf(Entry(System.currentTimeMillis(), from, text.take(120), result)) + all(context)
        file(context).writeText(
            JSONArray().apply {
                list.take(MAX).forEach { put(JSONObject().put("t", it.time).put("f", it.from).put("x", it.text).put("r", it.result)) }
            }.toString(),
        )
    }
}
