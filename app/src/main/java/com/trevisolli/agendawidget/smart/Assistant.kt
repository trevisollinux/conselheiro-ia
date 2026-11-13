package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.AgendaItem
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.CalendarWriter
import com.trevisolli.agendawidget.data.Format
import com.trevisolli.agendawidget.data.Settings
import com.trevisolli.agendawidget.data.TodoistSource
import com.trevisolli.agendawidget.data.WeatherSource
import com.trevisolli.agendawidget.widget.AgendaWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A conversa: entende pedidos ("coloca dentista quinta 15h na agenda", "lembrar de pagar o MEI",
 * "terminei a ração da Amelie", "o que tenho amanhã?"), executa e responde.
 */
/** Trabalho que não pode morrer junto com a tela (ex.: o gravador fechado no meio). */
object Background {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
}

object Assistant {
    private val locale = Locale("pt", "BR")
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /** Ação pedida, antes de executar. */
    private sealed class Action {
        data class Event(val title: String, val date: LocalDate, val time: LocalTime?, val minutes: Int) : Action()
        data class Task(
            val title: String, val date: LocalDate?, val time: LocalTime?, val repeat: String? = null,
            /** Itens de uma lista (compras, mala…): viram subtarefas no Todoist. */
            val subtasks: List<String> = emptyList(),
        ) : Action()
        data class Complete(val title: String) : Action()
        /** Apagar evento da agenda ou tarefa do Todoist: só prepara o botão; a pessoa confirma. */
        data class Delete(val title: String, val date: LocalDate?, val isTask: Boolean) : Action()
        /** Pedido da IA para ver e-mails/mensagens inteiros; o app busca e pergunta de novo com o texto. */
        data class ReadEmail(val query: String) : Action()
        /** Mudar título, dia, hora ou duração de um item existente (executa e deixa Desfazer). */
        data class Edit(
            val title: String, val date: LocalDate?, val isTask: Boolean,
            val newTitle: String?, val newDate: LocalDate?, val newTime: LocalTime?, val minutes: Int?,
        ) : Action()
        /** Ações de um toque: o app prepara, a pessoa confirma. */
        data class Message(val to: String, val text: String, val app: String) : Action()
        data class Call(val to: String) : Action()
        data class Route(val place: String) : Action()
        data class Track(val code: String) : Action()
        data class RemindAt(val place: String, val text: String) : Action()
        data class Remember(val fact: String) : Action()
        data class Forget(val about: String) : Action()
        data class Reply(val to: String, val text: String) : Action()
        data class Bill(val payee: String, val value: String?, val due: LocalDate?, val code: String?) : Action()
        data class SavePlace(val name: String) : Action()
    }

    private data class Plan(val reply: String?, val actions: List<Action>, val heard: String? = null)

    /** Quem entende áudio: Gemini ouve direto; OpenAI transcreve antes. Claude não recebe áudio. */
    fun canHear(context: Context): Boolean {
        val s = Settings(context)
        return s.useAi && s.aiKey.isNotBlank() && s.aiProvider != AiProvider.CLAUDE
    }

    /** Áudio gravado no app: a IA transcreve e já executa. Se a IA estiver fora, guarda e tenta depois. */
    suspend fun handleAudio(context: Context, audio: ByteArray, mime: String): ChatMessage = withContext(Dispatchers.IO) {
        try {
            hear(context, audio, mime)
        } catch (e: java.io.IOException) {
            VoiceQueue.add(context, audio, mime)
            ChatStore.add(context, ChatMessage(fromUser = true, text = "🎤 (áudio guardado)"))
            val answer = ChatMessage(
                fromUser = false,
                text = "⏳ Não deu agora: ${AiClient.friendly(e)}. Guardei o áudio e anoto sozinho assim que der; aviso por notificação.",
            )
            ChatStore.add(context, answer)
            answer
        } catch (e: Exception) {
            ChatStore.add(context, ChatMessage(fromUser = true, text = "🎤 (áudio)"))
            val answer = ChatMessage(fromUser = false, text = "⚠️ Não consegui ouvir o áudio: ${AiClient.friendly(e)}")
            ChatStore.add(context, answer)
            answer
        }
    }

    /** Sem rede de segurança: o erro sobe (usado também pela fila de áudios). */
    suspend fun hear(context: Context, audio: ByteArray, mime: String): ChatMessage = withContext(Dispatchers.IO) {
        val s = Settings(context)
        if (s.aiProvider == AiProvider.OPENAI) {
            val text = AiClient.transcribeOpenAi(s.aiKey, audio, "voz.m4a", mime)
            if (text.isBlank()) throw IllegalStateException("não ouvi nada")
            process(context, "🎤 $text", text, null)
        } else {
            process(context, null, null, audio to mime)
        }
    }

    /** Gravação de conversa: resumo + combinados. Se a IA estiver fora, a gravação espera na fila. */
    suspend fun handleMeeting(context: Context, audio: ByteArray, mime: String): ChatMessage = withContext(Dispatchers.IO) {
        try {
            summarizeMeeting(context, audio, mime)
        } catch (e: Exception) {
            VoiceQueue.add(context, audio, mime, meeting = true)
            val answer = ChatMessage(fromUser = false, text = "⏳ Não deu para resumir agora: ${AiClient.friendly(e)}. Guardei a gravação e resumo assim que der.")
            ChatStore.add(context, answer)
            answer
        }
    }

    /** Sem rede de segurança (usado também pela fila). */
    suspend fun summarizeMeeting(context: Context, audio: ByteArray, mime: String): ChatMessage = withContext(Dispatchers.IO) {
        val s = Settings(context)
        if (!s.useAi || s.aiKey.isBlank() || s.aiProvider != AiProvider.GEMINI) {
            throw IllegalStateException("o resumo de conversas precisa da IA Gemini ligada")
        }
        process(context, "🎙️ Conversa gravada", "", audio to mime, meeting = true)
    }

    /** Texto longo compartilhado (ex.: conversa exportada do WhatsApp): extrai compromissos, combinados e endereços. */
    suspend fun handleDocument(context: Context, name: String, content: String): ChatMessage = withContext(Dispatchers.IO) {
        val tail = content.lines().takeLast(400).joinToString("\n").takeLast(20_000)
        val ask = "Conversa/arquivo compartilhado (\"$name\"). Extraia os compromissos, combinados e prazos que envolvem o usuário " +
            "(crie eventos/tarefas só do que ainda vai acontecer) e resuma em até 5 linhas o que importa:\n$tail"
        process(context, "📄 $name", ask, null)
    }

    /** Fala já transcrita no aparelho: IA se estiver no ar, senão as regras. */
    suspend fun handleSpoken(context: Context, text: String): ChatMessage = withContext(Dispatchers.IO) {
        process(context, "🎤 $text", text, null)
    }

    /**
     * Foto (lista escrita à mão, print, convite, receita…): a IA lê e executa como se fosse texto.
     * Sem IA, o texto é lido no aparelho (ML Kit) e passa pelas regras; várias linhas viram uma lista.
     */
    suspend fun handleImage(context: Context, jpeg: ByteArray, ocr: String, caption: String = ""): ChatMessage = withContext(Dispatchers.IO) {
        val shown = "📷 Imagem" + if (caption.isNotBlank()) ": $caption" else ""
        val settings = Settings(context)
        if (settings.aiReady) {
            try {
                return@withContext process(context, shown, caption, null, jpeg)
            } catch (e: Exception) {
                // segue para o modo automático abaixo
            }
        }
        val text = ocr.trim()
        if (text.isBlank()) {
            ChatStore.add(context, ChatMessage(fromUser = true, text = shown))
            val answer = ChatMessage(fromUser = false, text = "Não consegui ler texto nessa imagem. Tente uma foto mais nítida e de frente.")
            ChatStore.add(context, answer)
            return@withContext answer
        }
        process(context, shown, text, null)
    }

    /** Processa uma mensagem do usuário e devolve a resposta (já salva no histórico). */
    suspend fun handle(context: Context, text: String): ChatMessage = withContext(Dispatchers.IO) {
        process(context, text, text, null)
    }

    /** [shown]: o que aparece no balão do usuário; com áudio, vem da transcrição. */
    private suspend fun process(
        context: Context, shown: String?, text: String?, audio: Pair<ByteArray, String>?, image: ByteArray? = null,
        meeting: Boolean = false,
    ): ChatMessage {
        val history = ChatStore.contextHistory(context, 20)
        if (shown != null) ChatStore.add(context, ChatMessage(fromUser = true, text = shown))
        val settings = Settings(context)
        val items = AgendaRepository.load(context).items

        val plan = if (image != null) {
            // Sem fallback aqui: erro sobe para handleImage, que tenta as regras com o texto lido no aparelho.
            aiPlan(context, text.orEmpty(), items, history, image = image)
        } else if (meeting && audio != null) {
            aiPlan(context, "", items, history, audio, meeting = true)
        } else if (audio != null) {
            // Sem fallback: erro sobe para handleAudio.
            aiPlan(context, "", items, history, audio).also {
                ChatStore.add(context, ChatMessage(fromUser = true, text = "🎤 " + (it.heard ?: "(áudio)")))
            }
        } else if (text == null) {
            Plan(null, emptyList())
        } else if (settings.aiLightReady || settings.aiReady) {
            try {
                val first = aiPlan(context, text, items, history)
                val lookups = first.actions.filterIsInstance<Action.ReadEmail>()
                if (lookups.isEmpty()) {
                    first
                } else {
                    // 2º passo: a IA pediu o texto completo; busca e pergunta de novo já com ele.
                    val found = lookups.take(2).joinToString("\n") { lookupMessages(context, it.query) }
                    aiPlan(context, text, items, history, extra = found)
                        .let { p -> p.copy(actions = p.actions.filterNot { it is Action.ReadEmail }) }
                }
            } catch (e: Exception) {
                FailLog.add(context, "Conversa (IA)", e)
                rulesPlanCtx(context, text, items).let { it.copy(reply = (it.reply ?: "") + "\n(IA indisponível, usei o modo automático)") }
            }
        } else {
            rulesPlanCtx(context, text, items)
        }

        val done = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val undo = mutableListOf<Undo>()
        val buttons = mutableListOf<QuickAction>()
        for (a in plan.actions) {
            try {
                when (a) {
                    is Action.Message -> {
                        val contact = QuickActions.findContact(context, a.to)
                        val who = contact?.first ?: a.to
                        val app = if (a.app == "sms") QuickAction.SMS else QuickAction.WHATSAPP
                        buttons += QuickAction(app, if (app == QuickAction.SMS) "💬 Enviar SMS para $who" else "💬 Abrir WhatsApp com $who", a.to, a.text)
                        done += "💬 Mensagem para $who pronta: \"${a.text}\"" +
                            if (contact == null && !QuickActions.hasContacts(context)) "\n(libere Contatos nas Configurações para abrir direto na conversa)" else ""
                    }
                    is Action.Call -> {
                        val who = QuickActions.findContact(context, a.to)?.first ?: a.to
                        buttons += QuickAction(QuickAction.CALL, "📞 Ligar para $who", a.to)
                    }
                    is Action.Route -> buttons += QuickAction(QuickAction.MAP, "🗺️ Rota até ${a.place}", a.place)
                    is Action.Remember -> {
                        Memory.add(context, a.fact)
                        done += "🧠 Guardei: ${a.fact}"
                    }
                    is Action.Forget -> {
                        val f = Memory.forget(context, a.about)
                        if (f != null) done += "🧠 Esqueci: ${f.text}" else problems += "não achei nada guardado sobre \"${a.about}\""
                    }
                    is Action.Reply -> {
                        // Pela notificação do WhatsApp (sem abrir) quando houver; senão abre a conversa com o texto.
                        val direct = ReplyHub.has(a.to)
                        buttons += QuickAction(
                            if (direct) QuickAction.REPLY else QuickAction.WHATSAPP,
                            if (direct) "💬 Enviar para ${a.to}" else "💬 Abrir WhatsApp com ${a.to}",
                            a.to, a.text,
                        )
                        done += "💬 Resposta para ${a.to}: \"${a.text}\""
                    }
                    is Action.Bill -> {
                        val b = Bills.Bill(payee = a.payee, value = a.value, due = a.due, code = a.code.orEmpty(), kind = if (a.code?.startsWith("000201") == true) "pix" else "boleto")
                        Bills.add(context, b)
                        if (!a.code.isNullOrBlank()) buttons += QuickAction(QuickAction.COPY, "📋 Copiar ${if (b.kind == "pix") "PIX" else "código"}", a.code)
                        // Lembrete com vencimento no Todoist (ou agenda, sem Todoist).
                        val title = "Pagar ${a.payee}" + (a.value?.let { " ($it)" } ?: "")
                        if (settings.todoistToken.isNotBlank()) {
                            TodoistSource.create(settings.todoistToken, title, a.due, null, a.code)?.let { undo += Undo(Undo.TASK, it) }
                        } else if (a.due != null) {
                            CalendarWriter.insert(context, title, a.due, null, 60, a.code)?.let { undo += Undo(Undo.EVENT, it.toString()) }
                        }
                        done += "💸 ${Bills.label(b)}"
                    }
                    is Action.RemindAt -> {
                        // Nome genérico ("mercado") só vale se já foi salvo; endereço ou nome composto dá para procurar no mapa.
                        val searchable = a.place.trim().contains(' ') || a.place.any { it.isDigit() }
                        val p = Places.find(context, a.place)
                            ?: (if (searchable) Places.geocode(context, a.place) else null)?.also { Places.addPlace(context, it) }
                        if (p == null) {
                            problems += "ainda não sei onde fica \"${a.place}\". Quando estiver lá, diga \"salvar este lugar como ${a.place}\""
                        } else {
                            Places.addReminder(context, p, a.text)
                            done += "📍 Ao chegar em ${p.name}: ${a.text}"
                            if (!Places.hasFine(context) || !Places.hasBackground(context)) {
                                problems += "para avisar com o app fechado, libere a localização \"o tempo todo\" em Configurações → Lugares"
                            }
                        }
                    }
                    is Action.SavePlace -> {
                        val p = Places.saveHere(context, a.name)
                        if (p == null) problems += "não consegui pegar sua localização (libere em Configurações → Lugares)"
                        else done += "📍 Lugar salvo: ${p.name}"
                    }
                    is Action.Track -> {
                        Parcels.scan(context, a.code, "Conversa")
                        buttons += QuickAction(QuickAction.TRACK, "📦 Rastrear ${a.code}", a.code)
                        done += "📦 Encomenda ${a.code} guardada"
                    }
                    is Action.Event -> {
                        val id = CalendarWriter.insert(context, a.title, a.date, a.time, a.minutes, "Criado pelo Consigliere")
                        if (id != null) {
                            undo += Undo(Undo.EVENT, id.toString())
                            done += "📅 ${a.title} · ${whenText(a.date, a.time)}"
                        } else {
                            problems += "não consegui criar \"${a.title}\" na agenda (libere \"Criar eventos\" nas Configurações)"
                        }
                    }
                    is Action.Task -> {
                        if (settings.todoistToken.isBlank()) {
                            problems += "conecte o Todoist nas Configurações para eu criar tarefas"
                        } else {
                            val id = TodoistSource.create(settings.todoistToken, a.title, a.date, a.time, null, a.repeat)
                            // Apagar a tarefa-mãe no Desfazer leva as subtarefas junto.
                            if (id != null) undo += Undo(Undo.TASK, id)
                            if (id != null) a.subtasks.forEach { item ->
                                TodoistSource.create(settings.todoistToken, item, null, null, null, parentId = id)
                            }
                            done += "✅ ${a.title}" + when {
                                a.repeat != null -> " · 🔁 recorrente"
                                a.date != null -> " · ${whenText(a.date, a.time)}"
                                else -> ""
                            } + if (a.subtasks.isNotEmpty()) "\n" + a.subtasks.joinToString("\n") { "   ▫️ $it" } else ""
                        }
                    }
                    is Action.Delete -> {
                        val zone = ZoneId.systemDefault()
                        val target = findDeletable(items, a.title, a.date, a.isTask)
                        if (target == null) {
                            problems += if (a.isTask) "não achei a tarefa \"${a.title}\""
                            else "não achei \"${a.title}\" na agenda (convites, eventos recorrentes e agendas só de leitura eu não apago; abra no Google Agenda)"
                        } else {
                            val day = Instant.ofEpochMilli(target.startMillis).atZone(zone)
                            val whenTxt = whenText(day.toLocalDate(), if (target.dateOnly) null else day.toLocalTime())
                            val key = if (a.isTask) "T:${target.taskId}" else target.moveKey!!
                            buttons += QuickAction(
                                QuickAction.DELETE,
                                "🗑️ Apagar \"${target.title}\" ($whenTxt)",
                                key,
                            )
                            done += "🗑️ Confirme abaixo para apagar: ${target.title} · $whenTxt"
                        }
                    }
                    is Action.ReadEmail -> Unit
                    is Action.Edit -> {
                        val target = findDeletable(items, a.title, a.date, a.isTask)
                        if (target == null) {
                            problems += if (a.isTask) "não achei a tarefa \"${a.title}\""
                            else "não achei \"${a.title}\" na agenda (convites e eventos recorrentes eu não edito; abra no Google Agenda)"
                        } else {
                            val key = if (a.isTask) "T:${target.taskId}" else target.moveKey!!
                            val before = com.trevisolli.agendawidget.data.Editor.edit(
                                context, key,
                                com.trevisolli.agendawidget.data.Editor.Change(a.newTitle, a.newDate, a.newTime, a.minutes),
                            )
                            undo += Undo(Undo.EDIT, before)
                            val parts = listOfNotNull(
                                a.newTitle?.let { "agora \"$it\"" },
                                if (a.newDate != null || a.newTime != null) {
                                    val zone = ZoneId.systemDefault()
                                    val old = Instant.ofEpochMilli(target.startMillis).atZone(zone)
                                    whenText(a.newDate ?: old.toLocalDate(), a.newTime ?: if (target.dateOnly) null else old.toLocalTime())
                                } else null,
                                a.minutes?.let { "$it min" },
                            )
                            done += "✏️ ${target.title}: ${parts.joinToString(" · ")}"
                        }
                    }
                    is Action.Complete -> {
                        val target = findTask(items, a.title)
                        if (target?.taskId == null) {
                            problems += "não achei a tarefa \"${a.title}\""
                        } else {
                            TodoistSource.close(settings.todoistToken, target.taskId)
                            settings.logCompletion(target.title)
                            undo += Undo(Undo.REOPEN, target.taskId)
                            done += "☑️ Concluída: ${target.title}"
                        }
                    }
                }
            } catch (e: Exception) {
                problems += "erro: ${e.message?.take(100)}"
            }
        }

        if (plan.actions.isNotEmpty()) {
            // Atualiza agenda e widget em segundo plano: a resposta não espera (resumo, arte, Notion…).
            com.trevisolli.agendawidget.widget.RefreshWorker.runNow(context)
        }

        val reply = buildString {
            if (!plan.reply.isNullOrBlank()) append(plan.reply.trim())
            if (done.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(done.joinToString("\n"))
            }
            if (problems.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("⚠️ ").append(problems.joinToString("; "))
            }
            if (isEmpty() && buttons.isNotEmpty()) append("Pronto, é só tocar abaixo.")
            if (isEmpty()) append("Não entendi bem. Tente algo como \"dentista quinta às 15h\" ou \"lembrar de pagar o MEI amanhã\".")
        }
        val answer = ChatMessage(fromUser = false, text = reply, undo = undo, actions = buttons)
        ChatStore.add(context, answer)
        return answer
    }

    /** Desfaz o que uma resposta criou. */
    suspend fun undo(context: Context, message: ChatMessage) = withContext(Dispatchers.IO) {
        val token = Settings(context).todoistToken
        val failed = message.undo.count { u ->
            runCatching {
                when (u.kind) {
                    Undo.EVENT -> CalendarWriter.delete(context, u.id.toLong())
                    Undo.TASK -> TodoistSource.delete(token, u.id)
                    Undo.REOPEN -> TodoistSource.reopen(token, u.id)
                    Undo.RESTORE -> com.trevisolli.agendawidget.data.Trash.restore(context, u.id)
                    Undo.EDIT -> com.trevisolli.agendawidget.data.Editor.revert(context, u.id)
                }
            }.onFailure { FailLog.add(context, "Desfazer", it) }.isFailure
        }
        if (failed == message.undo.size && failed > 0) {
            ChatStore.add(context, ChatMessage(fromUser = false, text = "⚠️ Não consegui desfazer (veja Configurações → Avançado → O que falhou)."))
            return@withContext
        }
        ChatStore.markUndone(context, message.id)
        ChatStore.add(context, ChatMessage(fromUser = false, text = if (failed > 0) "↩️ Desfeito em parte." else "↩️ Desfeito."))
        com.trevisolli.agendawidget.widget.RefreshWorker.runNow(context)
    }

    private fun whenText(date: LocalDate, time: LocalTime?): String {
        val today = LocalDate.now()
        val day = when (date) {
            today -> "hoje"
            today.plusDays(1) -> "amanhã"
            else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".") + " " +
                date.format(DateTimeFormatter.ofPattern("dd/MM"))
        }
        return if (time != null) "$day às ${time.format(hhmm)}" else day
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").lowercase().trim()

    /** Mensagens que batem com a busca: primeiro as guardadas (WhatsApp, e-mail recente), depois a caixa pelo IMAP. */
    private fun lookupMessages(context: Context, query: String): String {
        val words = norm(query).split(Regex("\\s+")).filter { it.length >= 3 }
        val hhmm = java.text.SimpleDateFormat("dd/MM HH:mm", locale)
        val local = InboxStore.all(context).filter { m ->
            val hay = norm(m.sender + " " + m.text)
            words.isNotEmpty() && words.all { it in hay }
        }.sortedByDescending { it.time }.take(3).map { m ->
            "- ${hhmm.format(java.util.Date(m.time))} [${m.app}] ${m.sender}: ${m.text.replace('\n', ' ').take(2500)}"
        }
        val remote = if (local.size >= 2) emptyList() else runCatching { MailSource.search(context, query) }
            .onFailure { FailLog.add(context, "Busca de e-mail", it) }
            .getOrDefault(emptyList())
            .filter { f -> local.none { f.subject.isNotBlank() && f.subject in it } }
            .map { f -> "- ${hhmm.format(java.util.Date(f.time))} [E-mail] ${f.from} · ${f.subject}: ${f.text}" }
        val all = local + remote
        return if (all.isEmpty()) "(nada encontrado para \"$query\")" else "Busca \"$query\":\n" + all.joinToString("\n")
    }

    /** Item que dá para apagar: tarefa do Todoist, ou evento comum da agenda (moveKey "E:"; nunca recorrente/convite). */
    private fun findDeletable(items: List<AgendaItem>, title: String, date: LocalDate?, task: Boolean): AgendaItem? {
        if (task) return findTask(items, title)
        val q = norm(title)
        val zone = ZoneId.systemDefault()
        val events = items.filter { it.moveKey?.startsWith("E:") == true }
        val byTitle = events.filter { norm(it.title) == q }.ifEmpty {
            events.filter { norm(it.title).contains(q) || q.contains(norm(it.title)) }
        }
        if (date != null) byTitle.firstOrNull { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == date }?.let { return it }
        return byTitle.minByOrNull { it.startMillis }
    }

    private fun findTask(items: List<AgendaItem>, title: String): AgendaItem? {
        val q = norm(title)
        val tasks = items.filter { it.taskId != null }
        return tasks.firstOrNull { norm(it.title) == q }
            ?: tasks.firstOrNull { norm(it.title).contains(q) || q.contains(norm(it.title)) }
            ?: tasks.maxByOrNull { t -> q.split(" ").count { w -> w.length > 2 && norm(t.title).contains(w) } }
                ?.takeIf { t -> q.split(" ").count { w -> w.length > 2 && norm(t.title).contains(w) } >= 1 }
    }

    // ---------- Modo automático (regras) ----------

    private val ASK = Regex("^(o que|oque|quais|qual|tenho|como (esta|está)|resum)|\\?$")
    private val COMPLETE = Regex("^(conclui|concluir|terminei|terminar|feito|fiz|finalizei|marca(r)? como feit[oa]|marquei)\\b\\s*(a |o |de )?")
    private val EVENT_WORDS = Regex("\\b(agenda|calendario|marcar|marca|consulta|reuniao|evento|exame|compromisso)\\b")
    private val TASK_WORDS = Regex("\\b(tarefa|todoist|lembrar|lembrete|comprar|pagar|ligar)\\b")
    private val LEAD = Regex(
        "^(por favor\\s+)?(coloca(r)?|cria(r)?|adiciona(r)?|anota(r)?|agenda(r)?|marca(r)?|me lembra(r)?( de)?|lembrar( de)?|lembrete( de)?|nova tarefa|tarefa)\\s*(:|de|que)?\\s*",
    )
    private val TAIL = Regex("\\s*(no|na)\\s+(calendario|calendário|agenda|todoist)\\s*$", RegexOption.IGNORE_CASE)

    private val LIST = Regex("^(.*?\\blista\\b[^:]*?)(?::|\\s+com\\s+|\\s+de\\s+itens\\s*:?)\\s*(.+)$", RegexOption.IGNORE_CASE)

    /** "lista de compras: leite, pão e ovos" → tarefa "Lista de compras" com 3 subtarefas. */
    private fun rulesList(text: String): Plan? {
        val m = LIST.find(text.trim()) ?: return null
        var name = m.groupValues[1].trim()
        LEAD.find(norm(name))?.let { lead -> name = name.substring(minOf(lead.range.last + 1, name.length)).trim() }
        val items = m.groupValues[2].split(Regex(",|;|\\s+e\\s+|\\n")).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
        if (items.size < 2) return null
        return Plan(
            "Lista criada!",
            listOf(Action.Task(name.ifBlank { "Lista" }.replaceFirstChar { it.uppercase() }, null, null, subtasks = items.map { it.replaceFirstChar { c -> c.uppercase() } })),
        )
    }

    private val CALL = Regex("^(liga|ligar|ligue|telefona|telefonar)\\s+(pra|para|pro|ao|à|a)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val ROUTE = Regex("^(rota|como (eu )?chego|me leva|caminho)\\s+(pra|para|pro|até|ate|ao|à|no|na)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val MESSAGE = Regex(
        "^(manda|mande|envia|envie)\\s+(uma\\s+)?(mensagem|msg|zap|whats)\\s+(pra|para|pro)\\s+(\\S+)\\s*(dizendo|falando|:)?\\s*(que\\s+)?(.+)$|" +
            "^(avisa|avise|fala|diz)\\s+(pra|para|pro|a|o)?\\s*(\\S+)\\s+que\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val SAVE_PLACE = Regex("^(salva|salvar|guarda|guardar)\\s+(este|esse|aqui|o)?\\s*(lugar\\s+)?(aqui\\s+)?como\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val REMIND_AT = Regex(
        "^quando\\s+(eu\\s+)?(chegar|estiver)\\s+(no|na|em|ao|à|nos|nas)\\s+(.+?)[,]?\\s+(me\\s+)?(lembra|lembre|lembrar|avisa|avise)(\\s+de|\\s+que)?\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val REMEMBER = Regex("^(lembra|lembre|anota|anote|guarda|guarde|grava|memoriza)\\s+que\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val FORGET = Regex("^(esquece|esqueça|apaga|apague)\\s+(a |o |da |do )?(.+)$", RegexOption.IGNORE_CASE)

    /** Mensagem, ligação, rota, rastreio, lugares e memória sem IA. */
    private fun rulesActions(text: String): Plan? {
        val t = text.trim().trimEnd('.', '!')
        REMEMBER.find(t)?.let { return Plan(null, listOf(Action.Remember(it.groupValues[2]))) }
        FORGET.find(t)?.takeIf { !t.contains("tarefa", true) }?.let { return Plan(null, listOf(Action.Forget(it.groupValues[3]))) }
        SAVE_PLACE.find(t)?.let { return Plan(null, listOf(Action.SavePlace(it.groupValues[5]))) }
        REMIND_AT.find(t)?.let { m ->
            return Plan(null, listOf(Action.RemindAt(m.groupValues[4], m.groupValues[8].replaceFirstChar { c -> c.uppercase() })))
        }
        Parcels.find(t).takeIf { it.isNotEmpty() }?.let { codes -> return Plan(null, codes.map { Action.Track(it) }) }
        CALL.find(t)?.let { return Plan(null, listOf(Action.Call(it.groupValues[3]))) }
        ROUTE.find(t)?.let { return Plan(null, listOf(Action.Route(it.groupValues[5]))) }
        MESSAGE.find(t)?.let { m ->
            val g = m.groupValues
            val (to, msg) = if (g[5].isNotBlank()) g[5] to g[8] else g[11] to g[12]
            if (to.isNotBlank() && msg.isNotBlank()) {
                return Plan(null, listOf(Action.Message(to.replaceFirstChar { it.uppercase() }, msg.replaceFirstChar { it.uppercase() }, "whatsapp")))
            }
        }
        return null
    }

    /** Texto com várias linhas curtas e sem data (ex.: lista escrita à mão lida da foto) → lista com subtarefas. */
    private fun rulesLines(text: String): Plan? {
        val lines = text.lines().map { it.trim().trimStart('-', '•', '*', '·', '○', '□').trim() }.filter { it.length in 2..60 }
        if (lines.size < 3 || RuleParser.parse(text).date != null) return null
        val first = lines.first()
        val titled = norm(first).startsWith("lista") || first.endsWith(":")
        val name = if (titled) first.trimEnd(':').replaceFirstChar { it.uppercase() } else "Lista"
        val itens = (if (titled) lines.drop(1) else lines).map { it.replaceFirstChar { c -> c.uppercase() } }
        return Plan("Lista criada!", listOf(Action.Task(name, null, null, subtasks = itens.take(40))))
    }

    /** Sem IA, pergunta sobre algo guardado na memória responde pela memória. */
    private fun rulesPlanCtx(context: Context, text: String, items: List<AgendaItem>): Plan {
        if (ASK.containsMatchIn(norm(text))) Memory.search(context, text)?.let { return Plan("🧠 ${it.text}", emptyList()) }
        return rulesPlan(text, items)
    }

    private fun rulesPlan(text: String, items: List<AgendaItem>): Plan {
        rulesActions(text)?.let { return it }
        rulesList(text)?.let { return it }
        rulesLines(text)?.let { return it }
        val n = norm(text)
        if (ASK.containsMatchIn(n)) return Plan(answerQuestion(text, items), emptyList())

        COMPLETE.find(n)?.let { m ->
            val what = text.substring(m.range.last + 1).trim().trimEnd('.', '!')
            if (what.isNotBlank()) return Plan(null, listOf(Action.Complete(what)))
        }

        val r = RuleParser.parse(text)
        var title = r.title
        // Tira "coloca", "me lembra de"… do começo (comparando sem acento, cortando no original).
        LEAD.find(norm(title))?.let { m -> title = title.substring(minOf(m.range.last + 1, title.length)) }
        title = TAIL.replace(title, "").trim().ifBlank { r.title }
        title = title.replaceFirstChar { it.uppercase() }
        val wantsEvent = EVENT_WORDS.containsMatchIn(n)
        val wantsTask = TASK_WORDS.containsMatchIn(n)
        val action = when {
            wantsTask && !wantsEvent -> Action.Task(title, r.date, r.time)
            wantsEvent || r.time != null -> Action.Event(title, r.date ?: LocalDate.now(), r.time, 60)
            else -> Action.Task(title, r.date, null)
        }
        return Plan("Feito!", listOf(action))
    }

    private fun answerQuestion(text: String, items: List<AgendaItem>): String {
        val r = RuleParser.parse(text)
        val zone = ZoneId.systemDefault()
        val day = r.date ?: LocalDate.now()
        val list = items.filter { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == day }
        val label = whenText(day, null).replaceFirstChar { it.uppercase() }
        if (list.isEmpty()) return "$label está livre 🍃"
        return "$label:\n" + list.joinToString("\n") {
            val t = if (it.dateOnly) "" else Instant.ofEpochMilli(it.startMillis).atZone(zone).format(hhmm) + " "
            "• $t${it.title}"
        }
    }

    // ---------- Modo IA ----------

    private const val SYSTEM =
        "Você é o Consigliere, assistente pessoal de agenda e tarefas. Fala português do Brasil, curto e simpático. " +
            "Você pode executar ações. Responda APENAS com um objeto JSON, sem texto fora dele: " +
            "{\"reply\": string, \"actions\": [ ... ]}. Tipos de ação: " +
            "{\"type\":\"create_event\",\"title\":string,\"date\":\"YYYY-MM-DD\",\"time\":\"HH:MM\" ou null,\"duration_min\":número}; " +
            "{\"type\":\"create_task\",\"title\":string,\"date\":\"YYYY-MM-DD\" ou null,\"time\":\"HH:MM\" ou null," +
            "\"repeat\": null ou regra do Todoist EM INGLÊS quando repetir (\"every day at 8am\", \"every monday at 9am\", \"every month on the 5th\")," +
            "\"subtasks\": [] ou lista de itens}; " +
            "{\"type\":\"complete_task\",\"title\": título exato de uma tarefa da lista}; " +
            "{\"type\":\"delete_event\",\"title\": título exato de um evento da lista,\"date\":\"YYYY-MM-DD\" do evento}; " +
            "{\"type\":\"delete_task\",\"title\": título exato de uma tarefa da lista}; " +
            "{\"type\":\"edit_event\",\"title\": título exato de um evento da lista,\"date\": dia atual dele \"YYYY-MM-DD\"," +
            "\"new_title\": string ou null,\"new_date\": \"YYYY-MM-DD\" ou null,\"new_time\": \"HH:MM\" ou null,\"duration_min\": número ou null}; " +
            "{\"type\":\"edit_task\",\"title\": título exato de uma tarefa da lista,\"new_title\",\"new_date\",\"new_time\" (iguais ao edit_event)}; " +
            "{\"type\":\"send_message\",\"to\": nome do contato ou número,\"text\": a mensagem pronta, em 1ª pessoa,\"app\":\"whatsapp\" ou \"sms\"}; " +
            "{\"type\":\"call\",\"to\": nome ou número}; " +
            "{\"type\":\"route\",\"place\": endereço ou nome do lugar}; " +
            "{\"type\":\"track\",\"code\": código de rastreio, ex. AB123456789BR}; " +
            "{\"type\":\"remind_at_place\",\"place\": nome do lugar (ex. mercado, casa) ou endereço,\"text\": o lembrete}; " +
            "{\"type\":\"save_place\",\"name\": nome para o lugar onde a pessoa está agora}; " +
            "{\"type\":\"remember\",\"fact\": fato a guardar}; {\"type\":\"forget\",\"about\": o que esquecer}; " +
            "{\"type\":\"reply\",\"to\": nome de quem mandou a mensagem,\"text\": a resposta pronta}; " +
            "{\"type\":\"bill\",\"payee\": a quem pagar,\"value\": \"R$ 0,00\" ou null,\"due\": \"YYYY-MM-DD\" ou null,\"code\": linha digitável ou PIX copia e cola, ou null}. " +
            "\"lembra que…\"/\"anota que…\" (um fato, não uma tarefa) = remember; perguntas sobre fatos guardados: responda pela memória. " +
            "\"responde a Helena que sim\" = reply (use as mensagens recebidas para entender o contexto). " +
            "Promessas e combinados (\"fiquei de mandar o orçamento pro João até sexta\") = create_task com o prazo. " +
            "Para encaixar horários (\"quando tenho uma tarde livre?\", \"marca no primeiro horário livre\"), use os horários livres. " +
            "\"apaga/cancela/remove o compromisso/evento X\" = delete_event (da agenda); \"apaga a tarefa X\" = delete_task. " +
            "Só prepara um botão de confirmação: no reply diga que é só tocar para confirmar, nunca diga que já apagou. " +
            "Para ler um e-mail ou mensagem INTEIRO (a lista só mostra o começo) ou procurar um mais antigo, responda só com " +
            "{\"type\":\"read_email\",\"query\": 1 a 3 palavras do remetente ou assunto} e reply vazio; o app busca e te manda o texto. " +
            "\"muda/passa/remarca/renomeia X\" (algo que JÁ existe na lista) = edit_event ou edit_task, nunca criar outro; " +
            "preencha só o que muda. " +
            "Boleto, PIX ou conta (texto ou imagem) = bill. " +
            "Use remind_at_place para \"quando eu chegar no X, me lembra de Y\" e save_place para \"salva aqui como X\". " +
            "send_message/call/route/track só preparam um botão; a pessoa confirma. Para \"avisa a Helena que…\", use send_message " +
            "com o texto já escrito do jeito que a pessoa mandaria. " +
            "Regras: se o usuário falar em agenda/calendário, consulta, reunião, exame ou algo para comparecer, use create_event. " +
            "Se for algo a fazer (comprar, pagar, ligar, lembrar), use create_task. Pode criar várias ações. " +
            "Títulos curtos, sem a data. Datas relativas (amanhã, sexta) a partir de hoje. " +
            "Se for pergunta sobre a agenda, o tempo ou as mensagens recebidas (WhatsApp, e-mail), responda com base nos dados " +
            "fornecidos (cada mensagem tem a hora; só diga que conferiu o e-mail se ele aparecer na lista, e se a linha \"E-mail (IMAP)\" mostrar ❌, avise o erro) e deixe actions vazio. Ao criar algo ao ar livre com chuva prevista, avise no reply. " +
            "O reply confirma em até 2 frases; não repita a lista do que foi criado (o app mostra). " +
            "Coisas que se repetem (todo dia, toda semana) viram create_task com repeat. " +
            "Listas (de compras, de mala, de presentes, passos de algo) viram UMA create_task com o nome da lista " +
            "(ex.: \"Lista de compras\") e cada item em subtasks, curto e com inicial maiúscula. " +
            "Se a mensagem vier em ÁUDIO anexo, inclua \"heard\": a transcrição fiel do que foi dito."

    private val QUESTION = Regex(
        // (?U): \b do Java entende "ê", "á" como letra (senão "você acha…" e "será…" não casam).
        "(?U)^(o que|oque|qual|quais|quando|como|por ?que|quem|onde|quanto|será|voc[êe]|me (ajuda|explica|diz|conta|resume)|" +
            "explica|resume|acha|devo|vale a pena|tem (algum|algo)|chegou|preciso)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Pergunta ou papo (vai no modelo principal e pode responder mais por extenso); o resto é comando. */
    internal fun isConversation(text: String): Boolean {
        val t = text.trim()
        return t.contains('?') || QUESTION.containsMatchIn(t) || t.length > 140
    }

    private fun aiPlan(
        context: Context, text: String, items: List<AgendaItem>, history: List<ChatMessage>,
        audio: Pair<ByteArray, String>? = null,
        image: ByteArray? = null,
        meeting: Boolean = false,
        /** Texto completo de e-mails/mensagens que a IA pediu (2º passo do read_email). */
        extra: String? = null,
    ): Plan {
        val s = Settings(context)
        val today = LocalDate.now()
        // Só em pergunta de texto/voz: puxa os e-mails novos na hora (a conversa não depende do worker de 30 min).
        if (audio == null && image == null && !meeting) MailSource.peek(context)
        val conversational = audio == null && image == null && !meeting && isConversation(text)
        val prompt = buildString {
            append("Hoje é ${today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}, $today, ${LocalTime.now().format(hhmm)}. ")
            append("Fuso ${ZoneId.systemDefault().id}.\n")
            if (s.userName.isNotBlank()) append("O usuário se chama ${s.userName}.\n")
            append("Agenda e tarefas (atrasadas e próximos dias):\n")
            items.take(40).forEach { append("- ${it.title} (${Format.subtitle(it)})\n") }
            if (items.isEmpty()) append("(vazia)\n")
            WeatherSource.load(context)?.let { append(WeatherSource.forecastLine(it)).append("\n") }
            append(InboxStore.promptBlock(InboxStore.recent(context, hours = 24)))
            if (MailSource.configured(context)) append("E-mail (IMAP): ${MailSource.statusLine(context)}\n")
            append(Memory.promptBlock(context))
            append(Slots.freeBlock(items))
            if (extra != null) {
                append("RESULTADO DA BUSCA que você pediu (texto completo; responda com base nele, não peça read_email de novo):\n")
                append(extra).append("\n")
            }
            if (history.isNotEmpty()) {
                append("Conversa recente:\n")
                history.forEach { append(if (it.fromUser) "Usuário: " else "Você: ").append(it.text.take(400)).append("\n") }
            }
            when {
                meeting -> append(
                    "O ÁUDIO anexo é a gravação de uma conversa do usuário (reunião, consulta, ligação; pode ter várias pessoas). " +
                        "No reply, faça um resumo em tópicos curtos, sem markdown: assunto; o que foi decidido; combinados " +
                        "(quem faz o quê e até quando); números, endereços e valores citados. Em actions, crie os eventos e " +
                        "tarefas dos combinados que são do usuário. Não inclua heard.",
                )
                audio != null -> append("Nova mensagem do usuário: no ÁUDIO anexo (pt-BR).")
                image != null -> append(
                    "Nova mensagem do usuário: a IMAGEM anexa (foto de lista escrita à mão, print, convite, receita, conta…). " +
                        "Leia e faça o que ela pede; uma lista de itens vira create_task com subtasks. " +
                        if (text.isNotBlank()) "Legenda da pessoa: $text" else "",
                )
                else -> {
                    if (conversational) append(
                        "Esta mensagem é uma CONVERSA (pergunta, dúvida ou pedido de opinião), não só um comando: " +
                            "responda de forma completa e útil, com raciocínio e opinião própria como um bom conselheiro, " +
                            "em até ~6 frases curtas (ignore o limite de 2 frases), usando a agenda, as mensagens e a memória. " +
                            "Só crie ações se a pessoa pedir.\n",
                    )
                    append("Nova mensagem do usuário: ").append(text)
                }
            }
        }
        // Comando simples vai no modelo leve (rápido, cota maior); conversa e mídia vão no principal.
        val media = audio != null || image != null
        val model = when {
            media -> s.effectiveModel
            conversational && s.aiReady -> s.effectiveModel
            s.aiLightReady -> s.lightModel
            else -> s.effectiveModel
        }
        val raw = AiClient.complete(
            s.aiProvider, s.aiKey, model, SYSTEM, prompt, jpeg = image, audio = audio,
            fastTimeoutMs = when {
                meeting -> 240_000
                !media -> 20_000
                else -> 45_000
            },
        )
        val json = AiClient.extractJson(raw) ?: return Plan(raw.take(400), emptyList())
        val actions = mutableListOf<Action>()
        val arr = json.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            when (a.optString("type")) {
                "send_message" -> {
                    val to = a.optString("to").trim()
                    val msg = a.optString("text").trim()
                    if (to.isNotBlank() && msg.isNotBlank()) actions += Action.Message(to, msg, a.optString("app").ifBlank { "whatsapp" })
                    continue
                }
                "call" -> {
                    a.optString("to").trim().takeIf { it.isNotBlank() }?.let { actions += Action.Call(it) }
                    continue
                }
                "route" -> {
                    a.optString("place").trim().takeIf { it.isNotBlank() }?.let { actions += Action.Route(it) }
                    continue
                }
                "track" -> {
                    Parcels.find(a.optString("code")).forEach { actions += Action.Track(it) }
                    continue
                }
                "remember" -> {
                    a.optString("fact").trim().takeIf { it.isNotBlank() }?.let { actions += Action.Remember(it) }
                    continue
                }
                "forget" -> {
                    a.optString("about").trim().takeIf { it.isNotBlank() }?.let { actions += Action.Forget(it) }
                    continue
                }
                "reply" -> {
                    val to = a.optString("to").trim()
                    val msg = a.optString("text").trim()
                    if (to.isNotBlank() && msg.isNotBlank()) actions += Action.Reply(to, msg)
                    continue
                }
                "bill" -> {
                    val due = a.optString("due").takeIf { !a.isNull("due") && it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    actions += Action.Bill(
                        a.optString("payee").ifBlank { "conta" }, a.optString("value").takeIf { !a.isNull("value") && it.isNotBlank() },
                        due, a.optString("code").filter { !it.isWhitespace() }.takeIf { !a.isNull("code") && it.length >= 20 },
                    )
                    continue
                }
                "read_email" -> {
                    a.optString("query").trim().takeIf { it.isNotBlank() }?.let { actions += Action.ReadEmail(it) }
                    continue
                }
                "remind_at_place" -> {
                    val place = a.optString("place").trim()
                    val txt = a.optString("text").trim()
                    if (place.isNotBlank() && txt.isNotBlank()) actions += Action.RemindAt(place, txt)
                    continue
                }
                "save_place" -> {
                    a.optString("name").trim().takeIf { it.isNotBlank() }?.let { actions += Action.SavePlace(it) }
                    continue
                }
            }
            val title = a.optString("title").trim()
            if (title.isBlank()) continue
            val date = a.optString("date").takeIf { !a.isNull("date") && it.length == 10 }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val time = a.optString("time").takeIf { !a.isNull("time") && it.length >= 4 }
                ?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
            when (a.optString("type")) {
                "create_event" -> actions += Action.Event(title, date ?: today, time, a.optInt("duration_min", 60).coerceIn(5, 1440))
                "create_task" -> actions += Action.Task(
                    title, date, time,
                    a.optString("repeat").takeIf { !a.isNull("repeat") && it.startsWith("every", ignoreCase = true) },
                    a.optJSONArray("subtasks")?.let { arr ->
                        List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(40)
                    }.orEmpty(),
                )
                "complete_task" -> actions += Action.Complete(title)
                "delete_event" -> actions += Action.Delete(title, date, false)
                "delete_task" -> actions += Action.Delete(title, null, true)
                "edit_event", "edit_task" -> {
                    val newDate = a.optString("new_date").takeIf { !a.isNull("new_date") && it.length == 10 }
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val newTime = a.optString("new_time").takeIf { !a.isNull("new_time") && it.length >= 4 }
                        ?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
                    val newTitle = a.optString("new_title").takeIf { !a.isNull("new_title") && it.isNotBlank() }?.trim()
                    val minutes = a.optInt("duration_min", 0).takeIf { it in 5..1440 }
                    if (newDate != null || newTime != null || newTitle != null || minutes != null) {
                        actions += Action.Edit(title, date, a.optString("type") == "edit_task", newTitle, newDate, newTime, minutes)
                    }
                }
            }
        }
        return Plan(json.optString("reply").ifBlank { null }, actions, json.optString("heard").takeIf { !json.isNull("heard") && it.isNotBlank() })
    }
}
