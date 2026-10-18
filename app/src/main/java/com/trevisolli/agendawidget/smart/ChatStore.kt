package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Algo que a conversa criou/alterou e pode ser desfeito. */
data class Undo(val kind: String, val id: String) {
    companion object {
        const val EVENT = "event"
        const val TASK = "task"
        const val REOPEN = "reopen"
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val undo: List<Undo> = emptyList(),
    val undone: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("user", fromUser)
        .put("text", text)
        .put("time", time)
        .put("undone", undone)
        .put("undo", JSONArray().apply { undo.forEach { put(JSONObject().put("kind", it.kind).put("id", it.id)) } })

    companion object {
        fun fromJson(o: JSONObject): ChatMessage {
            val u = o.optJSONArray("undo") ?: JSONArray()
            return ChatMessage(
                id = o.getString("id"),
                fromUser = o.getBoolean("user"),
                text = o.getString("text"),
                time = o.optLong("time"),
                undo = List(u.length()) { Undo(u.getJSONObject(it).getString("kind"), u.getJSONObject(it).getString("id")) },
                undone = o.optBoolean("undone"),
            )
        }
    }
}

object ChatStore {
    private const val MAX = 120
    private fun file(context: Context) = File(context.filesDir, "chat.json")

    @Synchronized
    fun all(context: Context): List<ChatMessage> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { ChatMessage.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(context: Context, m: ChatMessage) = save(context, (all(context) + m).takeLast(MAX))

    @Synchronized
    fun markUndone(context: Context, id: String) =
        save(context, all(context).map { if (it.id == id) it.copy(undone = true) else it })

    @Synchronized
    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<ChatMessage>) {
        file(context).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }
}
