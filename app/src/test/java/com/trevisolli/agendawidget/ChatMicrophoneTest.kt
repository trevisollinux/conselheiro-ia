package com.trevisolli.agendawidget

import com.trevisolli.agendawidget.ui.MicSwipe
import com.trevisolli.agendawidget.ui.micSwipe
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMicrophoneTest {
    @Test fun arrastarParaCimaAbreConversaESoltaSemArrastarEnvia() {
        assertEquals(MicSwipe.CONVERSATION, micSwipe(-12f, -80f, 60f))
        assertEquals(MicSwipe.CANCEL, micSwipe(-80f, -12f, 60f))
        assertEquals(MicSwipe.NONE, micSwipe(10f, -20f, 60f))
        assertEquals(MicSwipe.NONE, micSwipe(10f, 90f, 60f))
    }
}
