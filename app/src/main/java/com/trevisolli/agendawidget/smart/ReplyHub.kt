package com.trevisolli.agendawidget.smart

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Guarda o botão "Responder" das notificações do WhatsApp (e afins) para o Consigliere responder sem abrir o app.
 * Fica só na memória: some se o Android encerrar o app (aí cai no plano B, abrir o WhatsApp com o texto).
 */
object ReplyHub {
    private data class Entry(val action: Notification.Action, val time: Long)

    private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private const val TTL = 12 * 3600_000L

    fun key(sender: String) = Places.norm(sender).substringBefore(" (").trim()

    fun remember(sender: String, n: Notification) {
        val action = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return
        if (sender.isBlank()) return
        map[key(sender)] = Entry(action, System.currentTimeMillis())
    }

    /** Conversa que dá para responder pelo nome (exato ou parcial). */
    fun has(name: String): Boolean = find(name) != null

    private fun find(name: String): Entry? {
        val q = key(name)
        val now = System.currentTimeMillis()
        map.entries.removeIf { now - it.value.time > TTL }
        return map[q] ?: map.entries.firstOrNull { it.key.contains(q) || q.contains(it.key) }?.value
    }

    /** Envia a resposta pela própria notificação. Devolve false se não tem como (aí use o plano B). */
    fun send(context: Context, name: String, text: String): Boolean {
        val e = find(name) ?: return false
        return runCatching {
            val inputs = e.action.remoteInputs
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            e.action.actionIntent.send(context, 0, intent)
            true
        }.getOrDefault(false)
    }
}
