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
object Assistant {
    private val locale = Locale("pt", "BR")
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /** Ação pedida, antes de executar. */
    private sealed class Action {
        data class Event(val title: String, val date: LocalDate, val time: LocalTime?, val minutes: Int) : Action()
        data class Task(val title: String, val date: LocalDate?, val time: LocalTime?) : Action()
        data class Complete(val title: String) : Action()
    }

    private data class Plan(val reply: String?, val actions: List<Action>)

    /** Processa uma mensagem do usuário e devolve a resposta (já salva no histórico). */
    suspend fun handle(context: Context, text: String): ChatMessage = withContext(Dispatchers.IO) {
        val history = ChatStore.all(context).takeLast(8)
        ChatStore.add(context, ChatMessage(fromUser = true, text = text))
        val settings = Settings(context)
        val items = AgendaRepository.load(context).items

        val plan = if (settings.aiReady) {
            try {
                aiPlan(context, text, items, history)
            } catch (e: Exception) {
                rulesPlan(text, items).let { it.copy(reply = (it.reply ?: "") + "\n(IA indisponível, usei o modo automático)") }
            }
        } else {
            rulesPlan(text, items)
        }

        val done = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val undo = mutableListOf<Undo>()
        for (a in plan.actions) {
            try {
                when (a) {
                    is Action.Event -> {
                        val id = CalendarWriter.insert(context, a.title, a.date, a.time, a.minutes, "Criado pelo ConsigliereIA")
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
                            val id = TodoistSource.create(settings.todoistToken, a.title, a.date, a.time, null)
                            if (id != null) undo += Undo(Undo.TASK, id)
                            done += "✅ ${a.title}" + (a.date?.let { " · ${whenText(it, a.time)}" } ?: "")
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
            val snapshot = AgendaRepository.refresh(context)
            AgendaWidget.push(context, snapshot)
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
            if (isEmpty()) append("Não entendi bem. Tente algo como \"dentista quinta às 15h\" ou \"lembrar de pagar o MEI amanhã\".")
        }
        val answer = ChatMessage(fromUser = false, text = reply, undo = undo)
        ChatStore.add(context, answer)
        answer
    }

    /** Desfaz o que uma resposta criou. */
    suspend fun undo(context: Context, message: ChatMessage) = withContext(Dispatchers.IO) {
        val token = Settings(context).todoistToken
        message.undo.forEach { u ->
            runCatching {
                when (u.kind) {
                    Undo.EVENT -> CalendarWriter.delete(context, u.id.toLong())
                    Undo.TASK -> TodoistSource.delete(token, u.id)
                    Undo.REOPEN -> TodoistSource.reopen(token, u.id)
                }
            }
        }
        ChatStore.markUndone(context, message.id)
        ChatStore.add(context, ChatMessage(fromUser = false, text = "↩️ Desfeito."))
        val snapshot = AgendaRepository.refresh(context)
        AgendaWidget.push(context, snapshot)
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

    private fun rulesPlan(text: String, items: List<AgendaItem>): Plan {
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
        "Você é o ConsigliereIA, assistente pessoal de agenda e tarefas. Fala português do Brasil, curto e simpático. " +
            "Você pode executar ações. Responda APENAS com um objeto JSON, sem texto fora dele: " +
            "{\"reply\": string, \"actions\": [ ... ]}. Tipos de ação: " +
            "{\"type\":\"create_event\",\"title\":string,\"date\":\"YYYY-MM-DD\",\"time\":\"HH:MM\" ou null,\"duration_min\":número}; " +
            "{\"type\":\"create_task\",\"title\":string,\"date\":\"YYYY-MM-DD\" ou null,\"time\":\"HH:MM\" ou null}; " +
            "{\"type\":\"complete_task\",\"title\": título exato de uma tarefa da lista}. " +
            "Regras: se o usuário falar em agenda/calendário, consulta, reunião, exame ou algo para comparecer, use create_event. " +
            "Se for algo a fazer (comprar, pagar, ligar, lembrar), use create_task. Pode criar várias ações. " +
            "Títulos curtos, sem a data. Datas relativas (amanhã, sexta) a partir de hoje. " +
            "Se for pergunta sobre a agenda, o tempo ou as mensagens recebidas (WhatsApp, e-mail), responda com base nos dados " +
            "fornecidos e deixe actions vazio. Ao criar algo ao ar livre com chuva prevista, avise no reply. " +
            "O reply confirma em até 2 frases; não repita a lista do que foi criado (o app mostra)."

    private fun aiPlan(context: Context, text: String, items: List<AgendaItem>, history: List<ChatMessage>): Plan {
        val s = Settings(context)
        val today = LocalDate.now()
        val prompt = buildString {
            append("Hoje é ${today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}, $today, ${LocalTime.now().format(hhmm)}. ")
            append("Fuso ${ZoneId.systemDefault().id}.\n")
            if (s.userName.isNotBlank()) append("O usuário se chama ${s.userName}.\n")
            append("Agenda e tarefas (atrasadas e próximos dias):\n")
            items.take(40).forEach { append("- ${it.title} (${Format.subtitle(it)})\n") }
            if (items.isEmpty()) append("(vazia)\n")
            WeatherSource.load(context)?.let { append(WeatherSource.forecastLine(it)).append("\n") }
            append(InboxStore.promptBlock(InboxStore.recent(context, hours = 24)))
            if (history.isNotEmpty()) {
                append("Conversa recente:\n")
                history.forEach { append(if (it.fromUser) "Usuário: " else "Você: ").append(it.text.take(200)).append("\n") }
            }
            append("Nova mensagem do usuário: ").append(text)
        }
        val raw = AiClient.complete(s.aiProvider, s.aiKey, s.effectiveModel, SYSTEM, prompt)
        val json = AiClient.extractJson(raw) ?: return Plan(raw.take(400), emptyList())
        val actions = mutableListOf<Action>()
        val arr = json.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val title = a.optString("title").trim()
            if (title.isBlank()) continue
            val date = a.optString("date").takeIf { !a.isNull("date") && it.length == 10 }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val time = a.optString("time").takeIf { !a.isNull("time") && it.length >= 4 }
                ?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
            when (a.optString("type")) {
                "create_event" -> actions += Action.Event(title, date ?: today, time, a.optInt("duration_min", 60).coerceIn(5, 1440))
                "create_task" -> actions += Action.Task(title, date, time)
                "complete_task" -> actions += Action.Complete(title)
            }
        }
        return Plan(json.optString("reply").ifBlank { null }, actions)
    }
}
