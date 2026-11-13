package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Diagnóstico: o que deu errado em segundo plano (IA, Todoist, e-mail, briefings…), em vez de sumir calado.
 * Fica só no aparelho; as configurações mostram "O que falhou".
 */
object FailLog {
    data class Entry(val time: Long, val where: String, val message: String)

    private const val MAX = 40
    private fun file(context: Context) = File(context.filesDir, "fail_log.json")

    @Synchronized
    fun all(context: Context): List<Entry> = runCatching {
        val arr = JSONArray(file(context).readText())
        List(arr.length()) { arr.getJSONObject(it) }.map { Entry(it.getLong("t"), it.getString("w"), it.getString("m")) }
    }.getOrDefault(emptyList())

    /** Guarda o erro (repetições seguidas do mesmo erro viram uma linha só). */
    @Synchronized
    fun add(context: Context, where: String, e: Throwable) = add(context, where, describe(e))

    @Synchronized
    fun add(context: Context, where: String, message: String) {
        val old = all(context)
        val first = old.firstOrNull()
        val list = if (first != null && first.where == where && first.message == message) {
            listOf(first.copy(time = System.currentTimeMillis())) + old.drop(1)
        } else {
            listOf(Entry(System.currentTimeMillis(), where, message.take(200))) + old
        }
        runCatching {
            file(context).writeText(
                JSONArray().apply {
                    list.take(MAX).forEach { put(JSONObject().put("t", it.time).put("w", it.where).put("m", it.message)) }
                }.toString(),
            )
        }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    /** Falhas das últimas 24 h (para o aviso nas configurações). */
    fun recentCount(context: Context): Int {
        val since = System.currentTimeMillis() - 24 * 3600_000L
        return all(context).count { it.time >= since }
    }

    /** Mensagem legível: "sem internet", "chave recusada (401)"… */
    fun describe(e: Throwable): String {
        val m = e.message.orEmpty()
        val name = e.javaClass.simpleName
        return when {
            e is java.net.UnknownHostException || "Unable to resolve host" in m -> "sem internet"
            e is java.net.SocketTimeoutException || "timeout" in m.lowercase() -> "demorou demais (tempo esgotado)"
            "401" in m || "403" in m -> "chave ou token recusado ($m)".take(160)
            "429" in m -> "limite da IA atingido (429)"
            m.isNotBlank() -> m.take(160)
            else -> name
        }
    }
}
