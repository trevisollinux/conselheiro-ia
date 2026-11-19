package com.trevisolli.agendawidget.smart

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale

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
        val title: String? = null,
        val sourceId: String? = null,
        val paid: Boolean = false,
    )

    /** Linha digitável: 47 (boleto) ou 48 (concessionária) dígitos, com pontos e espaços no meio. */
    private val BOLETO = Regex("(?<![\\d])(\\d[\\d .]{44,60}\\d)(?![\\d])")
    /** Preserva espaços do beneficiário que fazem parte do código copia e cola. */
    private val PIX = Regex("000201[^\\r\\n]{40,500}?6304[0-9A-Fa-f]{4}")
    private val VALUE = Regex("R\\$\\s*(\\d+(?:\\.\\d{3})*(?:,\\d{2})?)(?![\\d,])")
    private val DUE = Regex("(venc\\w*|pagar até|pagável até|vence em)\\D{0,15}(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?", RegexOption.IGNORE_CASE)
    private val RELATIVE_DUE = Regex("(?:vence|vencimento|pagar até|até)\\s+(?:em\\s+)?(hoje|amanhã|segunda|terça|quarta|quinta|sexta|sábado|domingo)", RegexOption.IGNORE_CASE)
    private val PAYMENT_REQUEST = Regex(
        "^(?:assunto:\\s*)?pagar\\s+|\\b(?:cobrança|valor (?:a pagar|em aberto)|pix pendente|pagamento pendente)\\b|" +
            "\\b(?:consegue|pode|precisa|favor|lembre de|tem que)\\s+(?:me\\s+)?(?:pagar|fazer (?:o |um )?pix)\\b|" +
            "\\b(?:pagar até|vence em|vencimento)\\b",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val PAID = Regex(
        "\\b(?:comprovante|recibo|(?:pix|pagamento|transferência)\\s+(?:já\\s+)?(?:recebid[oa]|realizad[oa]|efetuad[oa]|confirmad[oa]|concluíd[oa])|" +
            "(?:já\\s+)?(?:foi pago|já paguei|já mandei o pix)|cobrança (?:foi )?cancelada)\\b", RegexOption.IGNORE_CASE,
    )
    private val KEY = Regex(
        "(?:chave(?:\\s+(?:do\\s+)?pix)?|pix)\\s*[:=]\\s*" +
            "([a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}|" +
            "\\+?\\d[\\d .()\\-]{8,20}\\d)(?=\\s|[.,;]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE = Regex("^pagar[ \t]+[^\\n:;.,]{2,60}", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val QUOTED_HEADER = Regex("(?i)^(?:em|on) .{4,220} (?:escreveu|wrote):$")
    private val PRECISION = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    internal fun hasPaymentRequest(text: String) = PAYMENT_REQUEST.containsMatchIn(text.trim())
    internal fun hasPaymentDetails(text: String) = VALUE.containsMatchIn(text) || KEY.containsMatchIn(text)

    fun find(
        text: String, payee: String, today: LocalDate = LocalDate.now(),
        allowTextRequest: Boolean = true,
    ): List<Bill> {
        // O histórico citado em uma resposta não é uma cobrança nova.
        val freshText = text.lineSequence()
            .takeWhile { line -> !line.trimStart().startsWith('>') && !line.startsWith("-----Original Message") &&
                !line.trim().matches(QUOTED_HEADER) }
            .joinToString("\n").trim()
        // "Envie o comprovante" pode fazer parte de uma cobrança. Um comprovante em si fica de fora.
        val ban = PAID.find(freshText)
        if (ban != null && (ban.value.lowercase(Locale.ROOT) !in setOf("comprovante", "recibo") ||
                ban.range.first == 0 || !PAYMENT_REQUEST.containsMatchIn(freshText))) return emptyList()
        val value = VALUE.find(freshText)?.groupValues?.get(1)?.let { "R$ $it" }
        val due = DUE.find(freshText)?.let { m ->
            val d = m.groupValues[2].toInt()
            val mo = m.groupValues[3].toInt()
            val y = m.groupValues[4].takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it } ?: today.year
            runCatching { LocalDate.of(y, mo, d) }.getOrNull()
        } ?: RELATIVE_DUE.find(freshText)?.let { RuleParser.parse(it.groupValues[1], today).date }
        val title = TITLE.find(freshText)?.value?.substringBefore("R\$")?.trim()?.takeUnless {
            it.startsWith("pagar até", ignoreCase = true)
        }?.replaceFirstChar { it.uppercase() }
        val coded = PIX.findAll(freshText).mapNotNull { match ->
            match.value.takeIf { "br.gov.bcb.pix" in it.lowercase(Locale.ROOT) }
                ?.let { Bill(payee = payee, value = value, due = due, code = it, kind = "pix", title = title) }
        }.toList() + BOLETO.findAll(freshText).mapNotNull { match ->
            val digits = match.groupValues[1].filter { it.isDigit() }
            if (digits.length !in 47..48) null
            else Bill(payee = payee, value = value, due = due, code = digits, kind = "boleto", title = title)
        }.toList()
        if (coded.isNotEmpty()) return coded.distinctBy { it.code }
        if (!allowTextRequest || !PAYMENT_REQUEST.containsMatchIn(freshText)) return emptyList()
        val key = KEY.find(freshText)?.groupValues?.get(1)?.takeIf {
            '@' in it || it.length == 36 || it.count(Char::isDigit) in setOf(11, 14) ||
                (it.startsWith('+') && it.count(Char::isDigit) in 10..15)
        }
        if (key == null && value == null) return emptyList()
        return listOf(Bill(payee = payee, value = value, due = due, code = key.orEmpty(), kind = if (key == null) "payment" else "pix_key", title = title))
    }

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "bills.json"))

    private fun stored(context: Context): List<Bill> {
        val file = file(context)
        val raw = try {
            String(file.readFully(), Charsets.UTF_8)
        } catch (_: java.io.FileNotFoundException) {
            return emptyList()
        }
        val entries = JSONArray(raw)
        return List(entries.length()) { entries.getJSONObject(it) }.map {
            Bill(
                it.getString("id"), it.getString("payee"), it.optString("value").ifBlank { null },
                it.optString("due").ifBlank { null }?.let(LocalDate::parse), it.optString("code"), it.optString("kind"),
                it.optLong("addedAt"), it.optString("title").ifBlank { null }, it.optString("sourceId").ifBlank { null },
                it.optBoolean("paid"),
            )
        }
    }

    @Synchronized
    fun all(context: Context): List<Bill> = runCatching { stored(context).filterNot { it.paid } }.getOrDefault(emptyList())

    private fun sameBill(existing: Bill, candidate: Bill): Boolean {
        if (existing.kind != candidate.kind) return false
        if (candidate.kind == "boleto" || candidate.kind == "pix") return candidate.code.isNotBlank() && existing.code == candidate.code
        if (candidate.sourceId != null && candidate.sourceId == existing.sourceId) return true
        return !existing.paid && existing.payee.equals(candidate.payee, ignoreCase = true) &&
            existing.title == candidate.title && existing.value == candidate.value && existing.due == candidate.due &&
            existing.code == candidate.code && candidate.addedAt - existing.addedAt in 0..86_400_000L
    }

    data class Scan(val matched: Boolean, val fresh: List<Bill>)

    /** Conta já paga permanece identificável para que reprocessar a mensagem não a reabra. */
    @Synchronized
    fun scan(
        context: Context, text: String, payee: String, receivedAt: Long, sourceId: String?, allowTextRequest: Boolean,
    ): Scan {
        val today = java.time.Instant.ofEpochMilli(receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val current = stored(context)
        val detected = find(text, payee, today, allowTextRequest).map { it.copy(addedAt = receivedAt, sourceId = sourceId) }
        val fresh = detected.filter { bill -> current.none { sameBill(it, bill) } }
        if (fresh.isNotEmpty()) save(context, current + fresh)
        return Scan(detected.isNotEmpty(), fresh)
    }

    @Synchronized
    fun add(context: Context, bill: Bill) {
        val current = stored(context)
        val prior = current.firstOrNull { sameBill(it, bill) }
        if (prior?.paid == true) return
        save(context, current.filterNot { it.id == prior?.id } + if (prior != null) bill.copy(id = prior.id, sourceId = prior.sourceId) else bill)
    }

    @Synchronized
    fun remove(context: Context, id: String) = save(context, stored(context).map { if (it.id == id) it.copy(paid = true) else it })

    fun isPix(bill: Bill): Boolean = bill.kind == "pix" || bill.kind == "pix_key"

    fun label(b: Bill): String = listOfNotNull(
        b.title ?: b.payee.take(42),
        b.value,
        b.due?.let { (if (it.isBefore(LocalDate.now())) "vencida em " else "vence ") + it.format(PRECISION) }
            ?: "sem vencimento informado",
    ).joinToString(" · ")

    fun notify(context: Context, b: Bill) {
        val copy = if (isPix(b)) "Copiar Pix" else "Copiar código"
        Notify.post(
            context, "bills", "Contas a pagar", b.id.hashCode(), "💸 Conta a pagar", label(b),
            if (b.code.isBlank()) emptyList() else listOf(QuickAction(QuickAction.COPY, copy, b.code)),
        )
    }

    private fun save(context: Context, list: List<Bill>) {
        val data = JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id", it.id).put("payee", it.payee).put("value", it.value ?: "")
                    .put("due", it.due?.toString() ?: "").put("code", it.code).put("kind", it.kind)
                    .put("addedAt", it.addedAt).put("title", it.title ?: "")
                    .put("sourceId", it.sourceId ?: "").put("paid", it.paid))
            }
        }.toString()
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(data.toByteArray())
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }
}
