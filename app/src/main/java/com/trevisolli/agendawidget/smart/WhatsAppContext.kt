package com.trevisolli.agendawidget.smart

import java.time.LocalDate

/** Une o pedido à data/hora quando chegaram em mensagens separadas da mesma conversa. */
internal object WhatsAppContext {
    fun combinePayment(previous: String, latest: String): String? = when {
        Bills.hasPaymentRequest(previous) && !Bills.hasPaymentDetails(previous) &&
            !Bills.hasPaymentRequest(latest) && Bills.hasPaymentDetails(latest) -> "$previous\n$latest"
        Bills.hasPaymentRequest(latest) && !Bills.hasPaymentDetails(latest) &&
            !Bills.hasPaymentRequest(previous) && Bills.hasPaymentDetails(previous) -> "$latest\n$previous"
        else -> null
    }

    fun combine(previous: String, latest: String, today: LocalDate): String? {
        val first = RuleParser.parse(previous, today)
        val second = RuleParser.parse(latest, today)
        val firstHasTime = first.date != null || first.time != null
        val secondHasTime = second.date != null || second.time != null
        val firstHasAction = first.hasKeyword || Brain.hasDirectRequest(previous)
        val secondHasAction = second.hasKeyword || Brain.hasDirectRequest(latest)
        return when {
            !firstHasTime && firstHasAction && secondHasTime && !secondHasAction ->
                previous.trimEnd('.', '?', '!') + " " + latest
            firstHasTime && !firstHasAction && !secondHasTime && secondHasAction ->
                latest.trimEnd('.', '?', '!') + " " + previous
            else -> null
        }
    }
}
