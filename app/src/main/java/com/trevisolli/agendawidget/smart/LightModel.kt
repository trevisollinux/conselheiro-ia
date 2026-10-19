package com.trevisolli.agendawidget.smart

import com.trevisolli.agendawidget.data.Settings

/** Descobre o Flash-Lite da conta do Gemini (uma vez por semana) para o automático gastar a cota maior. */
object LightModel {
    private const val WEEK = 7 * 24 * 3600_000L

    fun refresh(settings: Settings) {
        if (!settings.useAi || settings.aiKey.isBlank() || settings.aiProvider != AiProvider.GEMINI) return
        if (settings.aiLightModel.isNotBlank()) return
        if (System.currentTimeMillis() - settings.geminiLiteCheckedAt < WEEK && settings.geminiLiteAuto.isNotBlank()) return
        // Sem rede: tenta de novo na próxima, mas não a cada atualização.
        settings.geminiLiteCheckedAt = System.currentTimeMillis() - WEEK + 3600_000L
        val found = runCatching { AiClient.discoverGeminiLite(settings.aiKey) }.getOrNull() ?: return
        settings.geminiLiteAuto = found
        settings.geminiLiteCheckedAt = System.currentTimeMillis()
    }
}
