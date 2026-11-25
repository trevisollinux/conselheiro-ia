package com.trevisolli.agendawidget.smart

import com.trevisolli.agendawidget.data.Settings

/** Descobre o Flash-Lite da conta do Gemini (uma vez por semana) para o automático gastar a cota maior. */
object LightModel {
    private const val WEEK = 7 * 24 * 3600_000L

    fun refresh(settings: Settings) {
        if (!settings.useAi || settings.aiProvider != AiProvider.GEMINI) return
        val key = settings.keyFor(AiProvider.GEMINI)
        if (key.isBlank() || settings.lightModelFor(AiProvider.GEMINI).isNotBlank()) return
        if (System.currentTimeMillis() - settings.geminiLiteCheckedAt < WEEK && settings.geminiLiteAuto.isNotBlank()) return
        // Sem rede: tenta de novo na próxima, mas não a cada atualização.
        settings.geminiLiteCheckedAt = System.currentTimeMillis() - WEEK + 3600_000L
        val found = runCatching { AiClient.discoverGeminiLite(key) }.getOrNull() ?: return
        if (settings.keyFor(AiProvider.GEMINI) != key) return
        settings.geminiLiteAuto = found
        settings.geminiLiteCheckedAt = System.currentTimeMillis()
    }
}
