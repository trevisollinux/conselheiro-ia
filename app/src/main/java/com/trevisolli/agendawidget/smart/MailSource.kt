package com.trevisolli.agendawidget.smart

import android.content.Context
import com.sun.mail.imap.IMAPFolder
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import java.util.Properties

/**
 * Caixa de entrada inteira pelo IMAP, com "senha de app" (Gmail: conta Google → Segurança → Senhas de app).
 * Só lê (pasta aberta em modo leitura); cada e-mail novo passa pelo mesmo filtro das notificações.
 */
object MailSource {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun address(context: Context) = prefs(context).getString("mail_address", "").orEmpty()
    private fun password(context: Context) = prefs(context).getString("mail_password", "").orEmpty()
    fun configured(context: Context) = address(context).isNotBlank() && password(context).isNotBlank()

    fun save(context: Context, address: String, password: String) {
        prefs(context).edit()
            .putString("mail_address", address.trim())
            .putString("mail_password", password.replace(" ", ""))
            .putLong("mail_uid", 0)
            .putLong("mail_checked", 0)
            .apply()
    }

    fun clear(context: Context) = save(context, "", "")

    private fun host(address: String): String {
        val domain = address.substringAfter('@').lowercase()
        return when {
            domain in setOf("gmail.com", "googlemail.com") -> "imap.gmail.com"
            domain in setOf("outlook.com", "hotmail.com", "live.com", "msn.com") -> "outlook.office365.com"
            domain.startsWith("yahoo.") -> "imap.mail.yahoo.com"
            else -> "imap.$domain"
        }
    }

    private fun open(address: String, password: String): Pair<javax.mail.Store, IMAPFolder> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(host(address), 993, address, password)
        val inbox = store.getFolder("INBOX") as IMAPFolder
        inbox.open(Folder.READ_ONLY)
        return store to inbox
    }

    /** Testa o login; devolve null se deu certo, ou a mensagem de erro. */
    fun test(address: String, password: String): String? = runCatching {
        val (store, inbox) = open(address.trim(), password.replace(" ", ""))
        inbox.close(false)
        store.close()
        null
    }.getOrElse { e ->
        val m = e.message.orEmpty()
        if ("AUTHENTICATIONFAILED" in m || "Invalid credentials" in m) "e-mail ou senha de app incorretos" else m.take(120)
    }

    /** No máximo a cada 10 min (chamado pela atualização do widget). */
    fun poll(context: Context, force: Boolean = false) {
        if (!configured(context)) return
        val p = prefs(context)
        if (!force && System.currentTimeMillis() - p.getLong("mail_checked", 0) < 10 * 60_000L) return
        p.edit().putLong("mail_checked", System.currentTimeMillis()).apply()
        val (store, inbox) = open(address(context), password(context))
        try {
            val last = p.getLong("mail_uid", 0)
            val messages: List<Message> = if (last == 0L) {
                // Primeira vez: só o que chegou nas últimas 24 h (não reprocessa a caixa inteira).
                val since = System.currentTimeMillis() - 24 * 3600_000L
                val count = inbox.messageCount
                if (count == 0) emptyList() else inbox.getMessages(maxOf(1, count - 30), count).filter { (it.receivedDate?.time ?: 0) >= since }
            } else {
                inbox.getMessagesByUID(last + 1, UIDFolder.LASTUID).toList()
            }.takeLast(25)
            var maxUid = last
            for (m in messages) {
                val uid = inbox.getUID(m)
                if (uid <= last) continue
                maxUid = maxOf(maxUid, uid)
                val from = (m.from?.firstOrNull() as? InternetAddress)?.let { it.personal ?: it.address }.orEmpty()
                val body = (m.subject.orEmpty() + "\n" + text(m)).trim().take(3000)
                if (body.length >= 6) runCatching { Intake.process(context, "Gmail", from, body, isEmail = true) }
            }
            if (maxUid == 0L) maxUid = inbox.uidNext - 1
            p.edit().putLong("mail_uid", maxUid).apply()
        } finally {
            runCatching { inbox.close(false) }
            runCatching { store.close() }
        }
    }

    /** Texto do e-mail: prefere o texto puro; HTML vira texto sem tags. */
    private fun text(part: Part): String = runCatching {
        when {
            part.isMimeType("text/plain") -> part.content as String
            part.isMimeType("text/html") -> (part.content as String)
                .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace(Regex("\\s+"), " ")
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                (parts.firstOrNull { it.isMimeType("text/plain") } ?: parts.firstOrNull())?.let { text(it) }.orEmpty()
            }
            else -> ""
        }
    }.getOrDefault("")
}
