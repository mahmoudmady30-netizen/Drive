package com.drivevoice.mvp

import org.junit.Assert.*
import org.junit.Test

class ProductionHardeningTest {
    @Test fun arabicIndicDeadlineIsParsed() {
        val p = DriveAiCore.plan("فارس وديني المطار قبل ٨")
        assertEquals(8, p.deadline?.hour)
        assertEquals(0, p.deadline?.minute)
    }

    @Test fun arabicWordTrafficThresholdIsParsed() {
        val p = DriveAiCore.plan("فارس وديني دبي مول ولو الزحمة أكتر من عشر دقايق غير الطريق")
        assertEquals(10, p.rerouteIfDelayMinutes)
    }

    @Test fun followUpDirectTrafficCommandIsNotLost() {
        val c = FaresConversationEngine()
        c.process("فارس وديني دبي مول")
        val turn = c.process("غير الطريق")
        assertTrue(turn.hasAction)
        assertEquals(VoiceAction.TrafficAlternative, turn.directAction)
    }

    @Test fun followUpEtaCommandIsNotLost() {
        val c = FaresConversationEngine()
        c.process("فارس وديني دبي مول")
        val turn = c.process("كم باقي")
        assertEquals(VoiceAction.Eta, turn.directAction)
    }
}
