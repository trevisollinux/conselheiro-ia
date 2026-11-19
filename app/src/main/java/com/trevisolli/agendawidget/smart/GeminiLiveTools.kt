package com.trevisolli.agendawidget.smart

import android.content.Context
import com.trevisolli.agendawidget.data.AgendaRepository
import com.trevisolli.agendawidget.data.WeatherSource
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object GeminiLiveTools {
    const val END = "encerrar_conversa"

    fun declarations(personal: Boolean): JSONArray = JSONArray().apply {
        put(declare("consultar_previsao", "Obtém as temperaturas previstas no aparelho para hoje e amanhã."))
        put(declare(END, "Chame somente quando a pessoa se despedir ou mandar encerrar. Depois, despeça-se brevemente."))
        if (personal) {
            put(declare("consultar_agenda", "Lê compromissos e tarefas de um dia. Se a data for omitida, usa hoje.",
                field = "data", fieldDescription = "Data em formato ISO, AAAA-MM-DD."))
            put(declare("buscar_mensagens", "Busca e-mails e notificações recentes por assunto, remetente ou palavras.",
                field = "consulta", fieldDescription = "Nome ou palavras da busca.", required = true))
        }
    }

    fun answer(context: Context, name: String, args: JSONObject, allowPersonal: () -> Boolean): JSONObject = when (name) {
        END -> result("A pessoa encerrou a conversa. Despeça-se brevemente.")
        "consultar_previsao" -> result(
            WeatherSource.load(context)?.let(WeatherSource::dailyForecastLine)?.takeIf { it.isNotBlank() }
                ?: "Previsão atualizada indisponível.",
        )
        "consultar_agenda" -> if (allowPersonal()) agenda(context, args.optString("data")) else locked()
        "buscar_mensagens" -> if (allowPersonal()) {
            val query = args.optString("consulta").take(100).trim()
            if (query.isBlank()) result("Peça um assunto, remetente ou palavra para buscar.")
            else {
                val found = Assistant.lookupMessages(context, query)
                if (allowPersonal()) result(found.take(5_000)) else locked()
            }
        } else locked()
        else -> result("Função indisponível.")
    }

    private fun agenda(context: Context, date: String): JSONObject {
        val day = if (date.isBlank()) LocalDate.now() else runCatching { LocalDate.parse(date) }.getOrNull()
            ?: return result("Informe uma data válida.")
        val zone = ZoneId.systemDefault()
        val snapshot = AgendaRepository.load(context)
        if (snapshot.updatedAt <= 0) return result("A agenda ainda não foi sincronizada no aplicativo.")
        val items = snapshot.items.filter { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == day }
        val time = DateTimeFormatter.ofPattern("HH:mm")
        return JSONObject().put("data", day.toString()).put("total", items.size).put("itens", JSONArray().apply {
            items.take(12).forEach { item ->
                put(JSONObject().put("titulo", item.title.take(140)).put("origem", item.source.label)
                    .put("hora", if (item.dateOnly) "dia inteiro"
                    else Instant.ofEpochMilli(item.startMillis).atZone(zone).format(time)))
            }
        })
    }

    private fun locked() = result("Desbloqueie o celular para consultar dados pessoais.")
    private fun result(text: String) = JSONObject().put("result", text)

    private fun declare(
        name: String, description: String, field: String? = null, fieldDescription: String = "", required: Boolean = false,
    ): JSONObject = JSONObject().put("name", name).put("description", description).apply {
        if (field != null) {
            val parameters = JSONObject().put("type", "OBJECT").put("properties", JSONObject().put(field,
                JSONObject().put("type", "STRING").put("description", fieldDescription)))
            if (required) parameters.put("required", JSONArray().put(field))
            put("parameters", parameters)
        }
    }
}
