package com.trevisolli.agendawidget.smart

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeInputHealthTest {
    @Test fun trocaDeEntradaArmaNovaCaptura() {
        val health = WakeInputHealth(0)
        assertEquals(WakeInputHealth.Result.OK, health.update(0, 12, silenced = false, hasSignal = true))
        assertEquals(WakeInputHealth.Result.OK, health.update(250, null, silenced = false, hasSignal = true))
        assertEquals(WakeInputHealth.Result.REOPEN, health.update(500, 20, silenced = false, hasSignal = true))
    }

    @Test fun faltaDeSinalEInterrupcaoPeloSistemaSaoDiferentes() {
        val health = WakeInputHealth(0)
        assertEquals(WakeInputHealth.Result.REOPEN, health.update(8_000, 12, silenced = false, hasSignal = false))
        assertEquals(WakeInputHealth.Result.WAITING, health.update(20_000, 12, silenced = true, hasSignal = false))
        assertEquals(WakeInputHealth.Result.OK, health.update(20_250, 12, silenced = false, hasSignal = false))
    }
}
