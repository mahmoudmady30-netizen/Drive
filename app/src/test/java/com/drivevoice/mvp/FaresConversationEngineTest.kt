package com.drivevoice.mvp

import org.junit.Assert.*
import org.junit.Test

class FaresConversationEngineTest {
    @Test fun contextSurvivesFollowUpTrafficInstruction() {
        val c = FaresConversationEngine()
        val first = c.process("فارس وديني دبي مول")
        assertTrue(first.isNewDestination)
        assertEquals("دبي مول", first.session.destinationQuery)

        val second = c.process("ولو الزحمة أكتر من 10 دقايق غير الطريق")
        assertFalse(second.isNewDestination)
        assertTrue(second.evaluateTraffic)
        assertEquals("دبي مول", second.session.destinationQuery)
        assertEquals(10, second.session.rerouteIfDelayMinutes)
    }

    @Test fun contextAccumulatesStopsWithoutForgettingDestination() {
        val c = FaresConversationEngine()
        c.process("فارس خدني المطار")
        val turn = c.process("وخليلي قهوة بعد نص الطريق")
        assertTrue(turn.rebuildRoute)
        assertEquals("المطار", turn.session.destinationQuery)
        assertEquals(1, turn.session.stops.size)
        assertEquals(DriveAiCore.StopType.COFFEE, turn.session.stops.first().type)
    }

    @Test fun shortAcknowledgementKeepsContext() {
        val c = FaresConversationEngine()
        c.process("فارس وديني المطار")
        val turn = c.process("تمام")
        assertFalse(turn.hasAction)
        assertEquals("المطار", turn.session.destinationQuery)
        assertEquals("تمام، أنا فاكر تفاصيل الرحلة ومكمل معاك", turn.acknowledgement)
    }

    @Test fun cancelClearsContext() {
        val c = FaresConversationEngine()
        c.process("فارس وديني دبي مول")
        val turn = c.process("فارس الغي الرحلة")
        assertNull(turn.session.destinationQuery)
        assertNull(c.current().destinationQuery)
    }
}
