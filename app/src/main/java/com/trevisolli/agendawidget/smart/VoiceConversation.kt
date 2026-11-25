package com.trevisolli.agendawidget.smart

internal object VoiceConversation {
    const val GOODBYE = "Tchau! Se precisar, é só chamar."
    private val activationPrefix = Regex("^(?:consiglier[ei]|conselheir[oa])(?:[,.:!? ]+|$)", RegexOption.IGNORE_CASE)
    private val farewells = setOf("tchau", "tchau tchau", "pode parar", "parar", "encerrar", "encerrar conversa", "até logo")

    fun withoutActivation(text: String): String = text.trim().replace(activationPrefix, "").trim()

    fun isFarewell(text: String): Boolean =
        withoutActivation(text).lowercase().trimEnd('.', '!', '?').trim() in farewells
}
