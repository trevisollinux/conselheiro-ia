package com.trevisolli.agendawidget.smart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeAudioTest {
    @Test fun formatoWavParaGemini() {
        val samples = byteArrayOf(1, 2, 3, 4)
        val wav = WakeAudio.wav(samples)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals(40, header.getInt(4))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(4, header.getInt(40))
        assertArrayEquals(samples, wav.copyOfRange(44, wav.size))
    }

    @Test fun ruidoNaoAbreCaptura() {
        assertFalse(WakeAudio.isSpeech(shortArrayOf(80, -90), 2, 35.0))
        assertFalse(WakeAudio.isSpeech(shortArrayOf(250, -250), 2, 100.0))
        assertTrue(WakeAudio.isSpeech(shortArrayOf(500, -500), 2, 100.0))
    }

    @Test fun pronunciasDaPalavraDeAtivacao() {
        assertTrue(WakePhrase.isActivation("consigliere", 0.90))
        assertTrue(WakePhrase.isActivation("consiglieri", 0.75))
        assertFalse(WakePhrase.isActivation("considera", 1.0))
        assertFalse(WakePhrase.isActivation("consigliere", 0.10))
    }
}

class VoiceConversationTest {
    @Test fun despedidaComOuSemPalavraDeAtivacao() {
        assertTrue(VoiceConversation.isFarewell("Consiglieri, tchau!"))
        assertTrue(VoiceConversation.isFarewell("Tchau."))
        assertTrue(VoiceConversation.isFarewell("Conselheiro, tchau"))
        assertFalse(VoiceConversation.isFarewell("Consigliere, como se diz tchau em italiano?"))
        assertEquals("qual é a temperatura amanhã?", VoiceConversation.withoutActivation("Consigliere, qual é a temperatura amanhã?"))
    }

    @Test fun gravadorMantemAOrdemAoDarAVoltaNoBuffer() {
        val buffer = WakeBuffer(4)
        buffer.add(shortArrayOf(1, 2, 3), 3)
        buffer.add(shortArrayOf(4, 5, 6), 3)
        assertArrayEquals(byteArrayOf(3, 0, 4, 0, 5, 0, 6, 0), buffer.pcm())
        buffer.clear()
        assertArrayEquals(byteArrayOf(), buffer.pcm())
    }
}
