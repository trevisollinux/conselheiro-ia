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

    @Test fun pixDaCrecheSemVencimento() {
        val bill = Bills.find("Mensagem da escola\nPagar creche\nFicou R\$ 350,00. Pix: financeiro@example.test", "Tesouraria", today).single()
        assertEquals("Pagar creche", bill.title)
        assertEquals("R$ 350,00", bill.value)
        assertEquals("financeiro@example.test", bill.code)
        assertEquals("pix_key", bill.kind)
        assertNull(bill.due)
    }

    @Test fun cobrançaSemCodigoComPrazoRelativo() {
        val bill = Bills.find("Pagar creche\nValor R\$ 350. Pagar até amanhã.", "Tesouraria", today).single()
        assertEquals("payment", bill.kind)
        assertTrue(bill.code.isBlank())
        assertEquals(today.plusDays(1), bill.due)
    }

    @Test fun comprovanteEChaveSemPedidoNaoCriamConta() {
        assertTrue(Bills.find("Comprovante: pagamento já realizado de R\$ 350,00. Pix: e@example.test", "Creche").isEmpty())
        assertTrue(Bills.find("Minha chave Pix: e@example.test", "Amigo").isEmpty())
        assertTrue(Bills.find("Certo, já paguei.\n> Pagar creche R\$ 350,00", "Amigo").isEmpty())
        assertTrue(Bills.find("Cobrança cancelada. Valor R\$ 350", "Creche").isEmpty())
    }

    @Test fun pixCopiaEColaPreservaEspacos() {
        val payload = "00020126580014br.gov.bcb.pix0136TESTEDEFORMATO12345678901234567890520400005912ESCOLA TESTE6304ABCD"
        assertEquals(payload, Bills.find("Pix copia e cola: $payload", "Creche").single().code)
    }

    @Test fun whatsappJuntaAcaoComHorarioSemJuntarConversasSoltas() {
        val combined = WhatsAppContext.combine("Consegue buscar a Ana?", "Amanhã às 17h", today)!!
        val parsed = RuleParser.parse(combined, today)
        assertTrue(RuleParser.isStrong(parsed))
        assertTrue(parsed.title.contains("Ana"))
        assertEquals(today.plusDays(1), parsed.date)
        assertEquals(LocalTime.of(17, 0), parsed.time)
        assertNull(WhatsAppContext.combine("Consegue buscar a Ana?", "Muito obrigado", today))
        assertNull(WhatsAppContext.combine("Consulta amanhã às 15h", "Amanhã às 17h", today))
    }

    @Test fun whatsappCapturaAPendenciaEPixEmMensagensSeparadas() {
        val combined = WhatsAppContext.combinePayment("Pagar creche", "R\$ 350,00. Pix: escola@example.test")!!
        assertEquals("escola@example.test", Bills.find(combined, "Creche").single().code)
        assertNull(WhatsAppContext.combinePayment("Pagar creche R\$ 350,00", "Comprovante R\$ 350,00"))
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
        assertEquals(0.30 to 2.50, AiUsage.price("gemini-3.5-flash-lite"))
        assertEquals(0.75 to 3.75, AiUsage.price("gemini-3.8-flash", LocalDate.of(2026, 12, 31)))
        assertEquals(1.50 to 7.50, AiUsage.price("gemini-3.8-flash", LocalDate.of(2027, 1, 1)))
        assertEquals(2.0 to 10.0, AiUsage.price("gemini-4-carbon", LocalDate.of(2026, 11, 30)))
        assertEquals(4.0 to 20.0, AiUsage.price("gemini-4-carbon", LocalDate.of(2026, 12, 1)))
        assertEquals(1.0 to 5.0, AiUsage.price("claude-haiku-5-5"))
    }
}


class ModelSelectionTest {
    @Test fun usaModelosQueAChaveListou() {
        val gemini = listOf("gemini-4-carbon", "gemini-4-hydrogen", "gemini-3.5-flash-lite")
        assertEquals("gemini-4-hydrogen", AiClient.recommendMain(AiProvider.GEMINI, gemini))
        assertEquals("gemini-3.5-flash-lite", AiClient.recommendLight(AiProvider.GEMINI, gemini))
        assertEquals("gpt-5-mini", AiClient.recommendLight(AiProvider.OPENAI, listOf("gpt-5", "gpt-5-mini")))
        assertEquals("claude-haiku-5-5", AiClient.recommendLight(AiProvider.CLAUDE, listOf("claude-opus-5-5", "claude-haiku-5-5")))
    }

    @Test fun versoesDoLiteSaoComparadasPorComponente() {
        assertEquals("gemini-3.10-flash-lite", AiClient.recommendGeminiLite(listOf(
            "gemini-3.8-flash-lite", "gemini-3.10-flash-lite", "gemini-4.0-flash-lite-preview",
        )))
    }
}
