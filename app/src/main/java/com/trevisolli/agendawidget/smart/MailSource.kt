package com.trevisolli.agendawidget.smart

import android.content.Context
import com.sun.mail.imap.IMAPFolder
import android.text.Html
import javax.mail.Folder
import javax.mail.FetchProfile
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm
import java.util.Date
import java.util.concurrent.locks.ReentrantLock
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
    private fun password(context: Context) = com.trevisolli.agendawidget.data.Secrets.get(prefs(context), "mail_password")
    fun configured(context: Context) = address(context).isNotBlank() && password(context).isNotBlank()

    private val accountLock = Any()
    private val pollLock = ReentrantLock()

    fun save(context: Context, address: String, password: String) {
        synchronized(accountLock) {
            val state = prefs(context)
            com.trevisolli.agendawidget.data.Secrets.put(state, "mail_password", password.replace(" ", ""))
            state.edit().putString("mail_address", address.trim())
                .putLong("mail_generation", state.getLong("mail_generation", 0) + 1)
                .remove("mail_uid").remove("mail_uid_validity").remove("mail_peeked")
                .remove("mail_ok").remove("mail_error").remove("mail_count").apply()
        }
        MailWorker.schedule(context)
    }

    fun clear(context: Context) = save(context, "", "")

    private fun origin(address: String): String = when (host(address)) {
        "imap.gmail.com" -> "Gmail"
        "outlook.office365.com" -> "Outlook"
        else -> "E-mail"
    }

    fun coversGmailNotification(context: Context, account: String?): Boolean {
        if (!configured(context)) return false
        val connected = address(context)
        return if (account?.contains('@') == true) account.equals(connected, ignoreCase = true)
        else origin(connected) == "Gmail"
    }

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
        try {
            store.connect(host(address), 993, address, password)
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            return store to inbox
        } catch (e: Exception) {
            runCatching { store.close() }
            throw e
        }
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

    data class Found(val from: String, val subject: String, val time: Long, val text: String)

    /**
     * Busca na caixa (até [days] dias): remetente, assunto ou corpo com [query].
     * Para a conversa abrir um e-mail inteiro ou um mais antigo do que a lista recente.
     */
    fun search(context: Context, query: String, days: Int = 45, max: Int = 4): List<Found> {
        if (query.isBlank()) return emptyList()
        val (account, pass) = synchronized(accountLock) { address(context) to password(context) }
        if (account.isBlank() || pass.isBlank()) return emptyList()
        val (store, inbox) = open(account, pass)
        try {
            val since = java.util.Date(System.currentTimeMillis() - days * 86_400_000L)
            val words = query.trim().split(Regex("\\s+")).filter { it.length >= 3 }.take(4).ifEmpty { listOf(query.trim()) }
            // Cada palavra precisa aparecer no remetente, no assunto ou no corpo.
            val terms = words.map { w ->
                javax.mail.search.OrTerm(
                    arrayOf(javax.mail.search.FromStringTerm(w), javax.mail.search.SubjectTerm(w), javax.mail.search.BodyTerm(w)),
                ) as javax.mail.search.SearchTerm
            } + javax.mail.search.ReceivedDateTerm(javax.mail.search.ComparisonTerm.GE, since)
            val found = inbox.search(javax.mail.search.AndTerm(terms.toTypedArray()))
            return found.sortedByDescending { it.receivedDate?.time ?: 0 }.take(max).map { m ->
                Found(
                    from = (m.from?.firstOrNull() as? InternetAddress)?.let { it.personal ?: it.address }.orEmpty(),
                    subject = m.subject.orEmpty(),
                    time = m.receivedDate?.time ?: 0,
                    text = text(m).replace(Regex("\\s+"), " ").trim().take(2500),
                )
            }
        } finally {
            runCatching { inbox.close(false) }
            runCatching { store.close() }
        }
    }

    /** "Última checagem 14:40 ✅ 3 novos" ou "❌ motivo" — para as configurações e para a conversa. */
    fun statusLine(context: Context): String {
        val p = prefs(context)
        val t = p.getLong("mail_ok", 0)
        val err = p.getString("mail_error", "").orEmpty()
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val count = p.getInt("mail_count", 0)
        val ok = if (t > 0) "última triagem ${hhmm.format(Date(t))} · $count novos" else "aguardando primeira triagem"
        return if (err.isNotBlank()) "❌ $err ($ok)" else "✅ $ok"
    }

    /**
     * Leitura rápida para a conversa: baixa os e-mails novos agora e põe só na lista de mensagens
     * (não avança o marcador, então a leitura completa em segundo plano ainda analisa cada um depois).
     * No máximo 1 vez por minuto.
     */
    fun peek(context: Context) {
        val p = prefs(context)
        val (account, pass, generation) = synchronized(accountLock) {
            if (System.currentTimeMillis() - p.getLong("mail_peeked", 0) < 60_000L) return
            if (!configured(context)) return
            p.edit().putLong("mail_peeked", System.currentTimeMillis()).apply()
            Triple(address(context), password(context), p.getLong("mail_generation", 0))
        }
        runCatching {
            val (store, inbox) = open(account, pass)
            try {
                val since = System.currentTimeMillis() - 24 * 3600_000L
                val count = inbox.messageCount
                if (count == 0) return@runCatching
                inbox.getMessages(maxOf(1, count - 40), count)
                    .filter { (it.receivedDate?.time ?: 0) >= since }
                    .forEach { m ->
                        val from = (m.from?.firstOrNull() as? InternetAddress)?.let { it.personal ?: it.address }.orEmpty()
                        val body = (m.subject.orEmpty() + "\n" + text(m)).trim().take(3000)
                        if (body.length >= 6 && p.getLong("mail_generation", 0) == generation) {
                            InboxStore.add(context, InboxStore.Msg(origin(account), from, body, m.receivedDate?.time ?: System.currentTimeMillis()))
                        }
                    }
            } finally {
                runCatching { inbox.close(false) }
                runCatching { store.close() }
            }
        }.onFailure {
            if (p.getLong("mail_generation", 0) == generation) FailLog.add(context, "Consulta rápida de e-mail", friendly(it))
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "AUTHENTICATIONFAILED" in m || "Invalid credentials" in m -> "senha de app recusada — crie outra e reconecte"
            "UnknownHost" in e.toString() || "connect" in m.lowercase() -> "sem conexão com o servidor de e-mail"
            else -> m.take(100).ifBlank { e.javaClass.simpleName }
        }
    }

    /** Retorna null quando outra leitura está em andamento. Só confirma o UID após a triagem. */
    fun poll(context: Context): Int? {
        if (!pollLock.tryLock()) return null
        try {
            return pollInbox(context)
        } finally {
            pollLock.unlock()
        }
    }

    private fun pollInbox(context: Context): Int {
        val p = prefs(context)
        val (account, pass, generation) = synchronized(accountLock) {
            Triple(address(context), password(context), p.getLong("mail_generation", 0))
        }
        if (account.isBlank() || pass.isBlank()) return 0
        fun stillConnected() = p.getLong("mail_generation", 0) == generation
        try {
            val (store, inbox) = open(account, pass)
            try {
                val uidValidity = inbox.uidValidity
                val savedValidity = p.getLong("mail_uid_validity", 0)
                var last = p.getLong("mail_uid", 0).takeIf { savedValidity == 0L || savedValidity == uidValidity } ?: 0L
                val since = System.currentTimeMillis() - 24 * 3_600_000L
                val initial = last == 0L
                val initialHighWater = if (initial) inbox.uidNext - 1 else last
                val pending = if (initial) {
                    inbox.search(ReceivedDateTerm(ComparisonTerm.GE, Date(since)))
                } else {
                    inbox.getMessagesByUID(last + 1, UIDFolder.LASTUID)
                }
                var processed = 0
                val profile = FetchProfile().apply {
                    add(UIDFolder.FetchProfileItem.UID)
                    add(FetchProfile.Item.ENVELOPE)
                }
                // IMAP retorna a ordem da caixa. Faz lotes pequenos sem pular os primeiros.
                for (batch in pending.filterNotNull().sortedBy { it.messageNumber }.chunked(25)) {
                    if (Thread.currentThread().isInterrupted || !stillConnected()) return processed
                    inbox.fetch(batch.toTypedArray(), profile)
                    for (message in batch) {
                        if (Thread.currentThread().isInterrupted || !stillConnected()) return processed
                        val uid = inbox.getUID(message)
                        if (uid <= last) continue
                        val receivedAt = message.receivedDate?.time ?: System.currentTimeMillis()
                        if (!initial || receivedAt >= since) {
                            val sender = (message.from?.firstOrNull() as? InternetAddress)?.let {
                                it.personal ?: it.address
                            }.orEmpty()
                            val body = (message.subject.orEmpty() + "\n" + text(message)).trim().take(12_000)
                            val sourceId = "imap:" + java.util.UUID.nameUUIDFromBytes("$account:$uidValidity:$uid".toByteArray())
                            if (!stillConnected()) return processed
                            if (body.isNotBlank()) {
                                Intake.process(context, origin(account), sender, body, isEmail = true, sourceId = sourceId, receivedAt = receivedAt)
                            } else {
                                NotifLog.add(context, origin(account), "", "e-mail sem texto legível")
                            }
                            processed++
                        }
                        synchronized(accountLock) {
                            if (!stillConnected()) return processed
                            check(p.edit().putLong("mail_uid", uid).putLong("mail_uid_validity", uidValidity).commit()) {
                                "não consegui salvar o progresso da leitura"
                            }
                        }
                        last = uid
                    }
                }
                synchronized(accountLock) {
                    if (stillConnected()) {
                        p.edit().putInt("mail_count", processed).putLong("mail_ok", System.currentTimeMillis())
                            .remove("mail_error").putLong("mail_uid_validity", uidValidity)
                            .putLong("mail_uid", maxOf(last, initialHighWater)).apply()
                    }
                }
                return processed
            } finally {
                runCatching { inbox.close(false) }
                runCatching { store.close() }
            }
        } catch (e: Exception) {
            synchronized(accountLock) {
                if (stillConnected()) {
                    p.edit().putString("mail_error", friendly(e)).apply()
                    FailLog.add(context, "E-mail", friendly(e))
                }
            }
            throw e
        }
    }

    /** Alternativas MIME representam o mesmo conteúdo; partes mistas podem conter textos diferentes. */
    private fun text(part: Part): String {
        if (part.disposition.equals(Part.ATTACHMENT, ignoreCase = true) || part.fileName != null) return ""
        return when {
            part.isMimeType("text/plain") -> part.content as? String ?: ""
            part.isMimeType("text/html") ->
                Html.fromHtml(
                    (part.content as? String ?: "").replace(Regex("(?is)<blockquote[^>]*>.*?</blockquote>"), ""),
                    Html.FROM_HTML_MODE_LEGACY,
                ).toString()
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                val parts = (0 until multipart.count).map { multipart.getBodyPart(it) }
                if (part.isMimeType("multipart/alternative")) {
                    (parts.filter { it.isMimeType("text/plain") } + parts.filterNot { it.isMimeType("text/plain") })
                        .firstNotNullOfOrNull { text(it).takeIf(String::isNotBlank) }.orEmpty()
                } else {
                    parts.map(::text).filter(String::isNotBlank).joinToString("\n")
                }
            }
            else -> ""
        }
    }
}
