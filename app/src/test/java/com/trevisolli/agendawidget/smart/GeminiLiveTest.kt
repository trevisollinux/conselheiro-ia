package com.trevisolli.agendawidget.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveTest {
    @Test fun setupSolicitaAudioEVoz() {
        val setup = GeminiLiveConversation.setup(personal = true).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        val generation = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", generation.getJSONArray("responseModalities").getString(0))
        assertEquals("Aoede", generation.getJSONObject("speechConfig").getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertTrue(setup.getJSONObject("realtimeInputConfig").has("automaticActivityDetection"))
    }

    @Test fun sessoesBloqueadasOferecemApenasFuncoesPublicas() {
        val locked = names(personal = false)
        assertEquals(setOf("consultar_previsao", GeminiLiveTools.END), locked)
        assertEquals(locked + setOf("consultar_agenda", "buscar_mensagens"), names(personal = true))
    }

    private fun names(personal: Boolean): Set<String> {
        val declarations = GeminiLiveConversation.setup(personal).getJSONObject("setup")
            .getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        return List(declarations.length()) { declarations.getJSONObject(it).getString("name") }.toSet()
    }
}
