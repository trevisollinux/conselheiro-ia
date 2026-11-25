package com.trevisolli.agendawidget.smart

/** A captura pode continuar registrada no Android mesmo sem entregar áudio do microfone. */
internal class WakeInputHealth(startedAt: Long) {
    enum class Result { OK, WAITING, REOPEN }

    private var lastSignal = startedAt
    private var route: Int? = null

    fun update(now: Long, routeId: Int?, silenced: Boolean, hasSignal: Boolean): Result {
        if (routeId != null) {
            val previous = route
            route = routeId
            if (previous != null && previous != routeId) return Result.REOPEN
        }
        if (silenced) {
            lastSignal = now
            return Result.WAITING
        }
        if (hasSignal) lastSignal = now
        return if (now - lastSignal >= SIGNAL_TIMEOUT_MS) Result.REOPEN else Result.OK
    }

    private companion object {
        const val SIGNAL_TIMEOUT_MS = 8_000L
    }
}
