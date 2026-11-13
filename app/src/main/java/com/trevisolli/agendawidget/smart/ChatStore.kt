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
        /** Recria um item apagado; o id é a cópia guardada (JSON). */
        const val RESTORE = "restore"
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val undo: List<Undo> = emptyList(),
    val undone: Boolean = false,
    /** Botões de um toque (mandar mensagem, ligar, rota, rastrear). */
    val actions: List<QuickAction> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("user", fromUser)
        .put("text", text)
        .put("time", time)
        .put("undone", undone)
        .put("undo", JSONArray().apply { undo.forEach { put(JSONObject().put("kind", it.kind).put("id", it.id)) } })
        .put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })

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
                actions = o.optJSONArray("actions")?.let { a -> List(a.length()) { QuickAction.fromJson(a.getJSONObject(it)) } }.orEmpty(),
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

    /** Histórico que vai para a IA: até [max] mensagens, parando numa pausa de 2 h ("nova conversa"). */
    fun contextHistory(context: Context, max: Int = 20): List<ChatMessage> {
        val list = all(context)
        val out = ArrayDeque<ChatMessage>()
        for (m in list.asReversed()) {
            val next = out.firstOrNull()
            if (next != null && next.time - m.time > 2 * 3600_000L) break
            out.addFirst(m)
            if (out.size >= max) break
        }
        return out.toList()
    }

    @Synchronized
    fun add(context: Context, m: ChatMessage) = save(context, (all(context) + m).takeLast(MAX))

    @Synchronized
    fun markUndone(context: Context, id: String) =
        save(context, all(context).map { if (it.id == id) it.copy(undone = true) else it })

    /** Tira um botão já usado (ex.: Apagar) para não repetir a ação. */
    @Synchronized
    fun dropAction(context: Context, messageId: String, action: QuickAction) =
        save(context, all(context).map { if (it.id == messageId) it.copy(actions = it.actions - action) else it })

    @Synchronized
    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<ChatMessage>) {
        file(context).writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }
}
