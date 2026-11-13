package com.trevisolli.agendawidget.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Regras sem IA: se alguma quebrar, o build da branch avisa antes de virar APK. */
class ParsersTest {
    // Quarta-feira.
    private val today = LocalDate.of(2026, 11, 11)

    @Test fun amanhaComHora() {
        val r = RuleParser.parse("consulta amanhã às 9h", today)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(LocalTime.of(9, 0), r.time)
        assertTrue(r.hasKeyword)
        assertEquals("Consulta", r.title.replaceFirstChar { it.uppercase() })
    }

    @Test fun diaDaSemanaComMinutos() {
        val r = RuleParser.parse("reunião sexta 14:30", today)
        assertEquals(LocalDate.of(2026, 11, 13), r.date)
        assertEquals(LocalTime.of(14, 30), r.time)
    }

    @Test fun dataNumerica() {
        val r = RuleParser.parse("prova dia 21/12", today)
        assertEquals(LocalDate.of(2026, 12, 21), r.date)
        assertNull(r.time)
    }

    @Test fun semData() {
        val r = RuleParser.parse("comprar pão", today)
        assertNull(r.date)
        assertNull(r.time)
    }

    @Test fun boletoComValorEVencimento() {
        val linha = "23793.38128 60000.000003 00000.000400 1 84340000012345"
        val b = Bills.find("Sua fatura de R$ 123,45 vence em 20/11/2026. Linha: $linha", "Claro").single()
        assertEquals("boleto", b.kind)
        assertEquals(47, b.code.length)
        assertEquals("R$ 123,45", b.value)
        assertEquals(LocalDate.of(2026, 11, 20), b.due)
    }

    @Test fun textoSemBoleto() {
        assertTrue(Bills.find("Oi, tudo bem? Te ligo amanhã às 10h", "João").isEmpty())
    }

    @Test fun codigoDeRastreio() {
        assertEquals(listOf("AB123456789BR"), Parcels.find("Seu pedido foi postado: ab 123 456 789 br"))
        assertTrue(Parcels.find("pedido 123456").isEmpty())
    }

    @Test fun perguntaVaiParaConversa() {
        assertTrue(Assistant.isConversation("o que chegou de e-mail hoje"))
        assertTrue(Assistant.isConversation("tenho algo amanhã?"))
        assertTrue(Assistant.isConversation("você acha que dá tempo de ir à academia"))
        assertFalse(Assistant.isConversation("dentista amanhã 10h"))
        assertFalse(Assistant.isConversation("comprar leite"))
    }

    @Test fun emailAutomaticoIgnorado() {
        assertTrue(NotificationWatcher.AUTOMATED.containsMatchIn("no-reply@accounts.google.com alerta de segurança"))
        assertFalse(NotificationWatcher.AUTOMATED.containsMatchIn("joão: consulta com dr. paulo quinta às 15h"))
    }
}

class AudioImportTest {
    @Test fun cabecalhoAdts() {
        // AAC-LC, 44,1 kHz, estéreo, quadro de 100 bytes.
        val h = AudioImport.adtsHeader(100, 2, 44100, 2).map { it.toInt() and 0xFF }
        assertEquals(listOf(0xFF, 0xF1, 0x50, 0x80, 0x0D, 0x7F, 0xFC), h)
    }
}

class AiUsageTest {
    @Test fun precoPorFamilia() {
        assertEquals(0.10 to 0.40, AiUsage.price("gemini-3.5-flash-lite"))
        assertEquals(0.30 to 2.50, AiUsage.price("gemini-3.8-flash"))
        assertEquals(1.0 to 5.0, AiUsage.price("claude-haiku-5-5"))
    }
}
