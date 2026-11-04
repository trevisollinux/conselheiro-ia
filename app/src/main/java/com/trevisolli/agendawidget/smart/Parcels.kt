package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Encomendas: códigos de rastreio achados em mensagens, prints e na conversa. */
object Parcels {
    data class Parcel(val code: String, val origin: String, val addedAt: Long = System.currentTimeMillis())

    /** Padrão internacional (UPU S10) usado pelos Correios: 2 letras, 9 dígitos, 2 letras (ex.: AB123456789BR). */
    private val CODE = Regex("\\b([A-Z]{2})\\s?(\\d{3})\\s?(\\d{3})\\s?(\\d{3})\\s?([A-Z]{2})\\b")
    private const val MAX_AGE = 45L * 86_400_000L

    fun find(text: String): List<String> =
        CODE.findAll(text.uppercase()).map { m -> m.groupValues.drop(1).joinToString("") }.distinct().toList()

    private fun file(context: Context) = File(context.filesDir, "parcels.json")

    @Synchronized
    fun all(context: Context): List<Parcel> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { arr.getJSONObject(it) }.map { Parcel(it.getString("code"), it.optString("origin"), it.optLong("addedAt")) }
        }.getOrDefault(emptyList()).filter { System.currentTimeMillis() - it.addedAt < MAX_AGE }
    }

    /** Guarda os códigos novos do texto; devolve só os que ainda não existiam. */
    @Synchronized
    fun scan(context: Context, text: String, origin: String): List<String> {
        val current = all(context)
        val fresh = find(text).filter { code -> current.none { it.code == code } }
        if (fresh.isNotEmpty()) save(context, fresh.map { Parcel(it, origin) } + current)
        return fresh
    }

    @Synchronized
    fun remove(context: Context, code: String) = save(context, all(context).filterNot { it.code == code })

    private fun save(context: Context, list: List<Parcel>) {
        file(context).writeText(
            JSONArray().apply {
                list.take(30).forEach { put(JSONObject().put("code", it.code).put("origin", it.origin).put("addedAt", it.addedAt)) }
            }.toString(),
        )
    }
}
