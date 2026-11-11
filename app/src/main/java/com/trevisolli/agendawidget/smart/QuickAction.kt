package com.trevisolli.agendawidget.smart

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Ação que o Consigliere prepara e a pessoa confirma com um toque:
 * mandar mensagem, ligar, abrir rota, rastrear encomenda. Nada é enviado sozinho.
 */
data class QuickAction(val kind: String, val label: String, val target: String = "", val text: String = "") {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("label", label).put("target", target).put("text", text)

    companion object {
        const val WHATSAPP = "whatsapp"
        const val SMS = "sms"
        const val CALL = "call"
        const val MAP = "map"
        const val TRACK = "track"
        const val COPY = "copy"
        /** Responde pela notificação do WhatsApp (sem abrir); sem ela, abre o WhatsApp com o texto. */
        const val REPLY = "reply"
        /** Apaga evento ("E:<id>") ou tarefa do Todoist ("T:<id>") depois do toque de confirmação. */
        const val DELETE = "delete"

        fun fromJson(o: JSONObject) = QuickAction(o.getString("kind"), o.getString("label"), o.optString("target"), o.optString("text"))
    }
}

object QuickActions {
    private val CORREIOS_APPS = listOf("br.com.correios.preatendimento", "br.com.correios.sro", "br.gov.correios")

    fun hasContacts(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Executa a ação (precisa vir de uma tela aberta). */
    fun launch(context: Context, a: QuickAction) {
        when (a.kind) {
            QuickAction.COPY -> {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Consigliere", a.target))
                Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                return
            }
            QuickAction.DELETE -> {
                val app = context.applicationContext
                val id = a.target.substringAfter(':')
                val isTask = a.target.startsWith("T:")
                Thread {
                    val ok = runCatching {
                        if (isTask) {
                            com.trevisolli.agendawidget.data.TodoistSource.delete(com.trevisolli.agendawidget.data.Settings(app).todoistToken, id)
                        } else {
                            check(com.trevisolli.agendawidget.data.CalendarWriter.canWrite(app)) { "sem permissão" }
                            com.trevisolli.agendawidget.data.CalendarWriter.delete(app, id.toLong())
                        }
                    }.isSuccess
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(
                            app,
                            if (ok) "Apagado 🗑️" else "Não consegui apagar (libere \"Criar eventos\" nas Configurações ou apague no Google Agenda)",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    if (ok) com.trevisolli.agendawidget.widget.RefreshWorker.runNow(app)
                }.start()
                return
            }
            QuickAction.REPLY -> {
                if (ReplyHub.send(context, a.target, a.text)) {
                    Toast.makeText(context, "Resposta enviada para ${a.target}", Toast.LENGTH_SHORT).show()
                    return
                }
                return launch(context, a.copy(kind = QuickAction.WHATSAPP))
            }
        }
        val intent: Intent? = when (a.kind) {
            QuickAction.WHATSAPP -> {
                val phone = phoneFor(context, a.target)
                if (phone != null) {
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=" + URLEncoder.encode(a.text, "UTF-8")))
                } else {
                    // Sem o número: abre o WhatsApp com o texto pronto e a pessoa escolhe o contato.
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.text).setPackage("com.whatsapp")
                }
            }
            QuickAction.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (phoneFor(context, a.target) ?: "")))
                .putExtra("sms_body", a.text)
            QuickAction.CALL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (phoneFor(context, a.target)?.let { "+$it" } ?: "")))
            QuickAction.MAP -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.target)))
            QuickAction.TRACK -> {
                // O código vai para a área de transferência; abre o app dos Correios (ou o site) para colar.
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Rastreio", a.target))
                Toast.makeText(context, "Código ${a.target} copiado: é só colar", Toast.LENGTH_LONG).show()
                CORREIOS_APPS.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://rastreamento.correios.com.br/app/index.php"))
            }
            else -> null
        }
        intent ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            if (a.kind == QuickAction.WHATSAPP) {
                // WhatsApp não instalado: escolhe outro app para mandar.
                runCatching {
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.text), "Enviar com")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } else {
                Toast.makeText(context, "Não encontrei um app para isso", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Número já no formato internacional, só dígitos (ex.: 5511999990000). Aceita nome do contato ou número. */
    fun phoneFor(context: Context, target: String): String? {
        val digits = target.filter { it.isDigit() }
        if (digits.length >= 8) return normalize(digits)
        if (target.isBlank() || !hasContacts(context)) return null
        return findContact(context, target)?.second?.let { normalize(it.filter { c -> c.isDigit() }) }
    }

    /** (nome, telefone) do contato que melhor bate com o nome falado. */
    fun findContact(context: Context, name: String): Pair<String, String>? {
        if (!hasContacts(context)) return null
        val q = norm(name)
        val found = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val display = c.getString(0).orEmpty()
                val number = c.getString(1).orEmpty()
                if (number.isNotBlank() && norm(display).contains(q)) found += display to number
            }
        }
        return found.minByOrNull { (display, _) ->
            val d = norm(display)
            when {
                d == q -> 0
                d.startsWith("$q ") -> 1
                d.startsWith(q) -> 2
                else -> 3 + d.length
            }
        }
    }

    private fun normalize(digits: String): String {
        val d = digits.trimStart('0')
        // Número brasileiro sem DDI (10 ou 11 dígitos com DDD): acrescenta 55.
        return if (d.length in 10..11) "55$d" else d
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").lowercase().trim()
}
