package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Memória pessoal: "lembra que a senha do Wi-Fi da mãe é…". Fica só no celular e vai para a IA como contexto. */
object Memory {
    data class Fact(val id: String = UUID.randomUUID().toString(), val text: String, val time: Long = System.currentTimeMillis())

    private const val MAX = 200
    private fun file(context: Context) = File(context.filesDir, "memory.json")

    @Synchronized
    fun all(context: Context): List<Fact> = runCatching {
        val arr = JSONArray(file(context).readText())
        List(arr.length()) { arr.getJSONObject(it) }.map { Fact(it.getString("id"), it.getString("text"), it.optLong("time")) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, text: String): Fact {
        val f = Fact(text = text.trim().replaceFirstChar { it.uppercase() })
        save(context, (all(context).filterNot { it.text.equals(f.text, ignoreCase = true) } + f).takeLast(MAX))
        return f
    }

    @Synchronized
    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.id == id })

    /** Apaga o que mais parece com o texto (para "esquece a senha do Wi-Fi"). */
    @Synchronized
    fun forget(context: Context, about: String): Fact? {
        val words = Places.norm(about).split(" ").filter { it.length > 2 }
        val hit = all(context).maxByOrNull { f -> words.count { Places.norm(f.text).contains(it) } }
            ?.takeIf { f -> words.count { Places.norm(f.text).contains(it) } > 0 } ?: return null
        remove(context, hit.id)
        return hit
    }

    /** Sem IA: o fato que mais compartilha palavras com a pergunta. */
    fun search(context: Context, question: String): Fact? {
        val words = Places.norm(question).split(Regex("\\W+")).filter { it.length > 3 }
        return all(context).maxByOrNull { f -> words.count { Places.norm(f.text).contains(it) } }
            ?.takeIf { f -> words.count { Places.norm(f.text).contains(it) } >= 1 }
    }

    fun promptBlock(context: Context): String {
        val facts = all(context)
        if (facts.isEmpty()) return ""
        return "Memória do usuário (fatos que ele pediu para guardar):\n" + facts.joinToString("\n") { "- ${it.text}" } + "\n"
    }

    private fun save(context: Context, list: List<Fact>) = file(context).writeText(
        JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time)) } }.toString(),
    )
}
