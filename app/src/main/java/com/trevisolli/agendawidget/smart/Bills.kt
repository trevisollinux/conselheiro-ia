package com.trevisolli.agendawidget.smart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Contas a pagar: boleto (linha digitável) ou PIX copia e cola achados em e-mails, mensagens e prints. */
object Bills {
    data class Bill(
        val id: String = UUID.randomUUID().toString(),
        val payee: String,
        val value: String?,
        val due: LocalDate?,
        val code: String,
        val kind: String, // "boleto" ou "pix"
        val addedAt: Long = System.currentTimeMillis(),
    )

    /** Linha digitável: 47 (boleto) ou 48 (concessionária) dígitos, com pontos e espaços no meio. */
    private val BOLETO = Regex("(?<![\\d])(\\d[\\d .]{44,60}\\d)(?![\\d])")
    /** PIX copia e cola (BR Code EMV): começa com 000201 e tem o domínio br.gov.bcb.pix. */
    private val PIX = Regex("000201\\S{40,}?6304[0-9A-Fa-f]{4}")
    private val VALUE = Regex("R\\$\\s?(\\d{1,3}(?:\\.\\d{3})*,\\d{2})")
    private val DUE = Regex("(venc\\w*|pagar até|pagável até|vence em)\\D{0,15}(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?", RegexOption.IGNORE_CASE)

    fun find(text: String, payee: String): List<Bill> {
        val out = mutableListOf<Bill>()
        val value = VALUE.find(text)?.groupValues?.get(1)?.let { "R$ $it" }
        val due = DUE.find(text)?.let { m ->
            val d = m.groupValues[2].toInt()
            val mo = m.groupValues[3].toInt()
            val y = m.groupValues[4].takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it } ?: LocalDate.now().year
            runCatching { LocalDate.of(y, mo, d) }.getOrNull()
        }
        PIX.findAll(text).forEach { if ("br.gov.bcb.pix" in it.value.lowercase()) out += Bill(payee = payee, value = value, due = due, code = it.value, kind = "pix") }
        BOLETO.findAll(text).forEach { m ->
            val digits = m.groupValues[1].filter { it.isDigit() }
            if (digits.length == 47 || digits.length == 48) out += Bill(payee = payee, value = value, due = due, code = digits, kind = "boleto")
        }
        return out.distinctBy { it.code }
    }

    private fun file(context: Context) = File(context.filesDir, "bills.json")

    @Synchronized
    fun all(context: Context): List<Bill> = runCatching {
        val arr = JSONArray(file(context).readText())
        List(arr.length()) { arr.getJSONObject(it) }.map {
            Bill(
                it.getString("id"), it.getString("payee"), it.optString("value").ifBlank { null },
                it.optString("due").ifBlank { null }?.let { d -> LocalDate.parse(d) }, it.getString("code"), it.getString("kind"), it.optLong("addedAt"),
            )
        }
    }.getOrDefault(emptyList()).filter { b -> (b.due ?: LocalDate.now()).isAfter(LocalDate.now().minusDays(20)) }

    /** Guarda as novas; devolve só as que ainda não existiam. */
    @Synchronized
    fun scan(context: Context, text: String, payee: String): List<Bill> {
        val current = all(context)
        val fresh = find(text, payee).filter { b -> current.none { it.code == b.code } }
        if (fresh.isNotEmpty()) save(context, current + fresh)
        return fresh
    }

    @Synchronized
    fun add(context: Context, b: Bill) = save(context, all(context).filterNot { it.code == b.code && b.code.isNotBlank() } + b)

    @Synchronized
    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.id == id })

    fun label(b: Bill): String = listOfNotNull(
        b.payee.take(30),
        b.value,
        b.due?.let { "vence " + it.format(DateTimeFormatter.ofPattern("dd/MM")) },
    ).joinToString(" · ")

    fun notify(context: Context, b: Bill) = Notify.post(
        context, "bills", "Contas a pagar", ("bill" + b.code).hashCode(),
        "💸 Conta: " + (b.value ?: b.kind),
        label(b) + "\nToque em Copiar ${if (b.kind == "pix") "PIX" else "código"} para pagar no app do banco.",
        listOf(QuickAction(QuickAction.COPY, if (b.kind == "pix") "📋 Copiar PIX" else "📋 Copiar código", b.code)),
    )

    private fun save(context: Context, list: List<Bill>) = file(context).writeText(
        JSONArray().apply {
            list.forEach {
                put(
                    JSONObject().put("id", it.id).put("payee", it.payee).put("value", it.value ?: "").put("due", it.due?.toString() ?: "")
                        .put("code", it.code).put("kind", it.kind).put("addedAt", it.addedAt),
                )
            }
        }.toString(),
    )
}
