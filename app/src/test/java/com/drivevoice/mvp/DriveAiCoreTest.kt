package com.drivevoice.mvp

import org.junit.Assert.*
import org.junit.Test

class DriveAiCoreTest {
    @Test fun compoundEgyptianPlan_isParsedIntoOneDrivingPlan() {
        val p = DriveAiCore.plan("فارس أنا مستعجل وعايز أوصل قبل ٨، ولو الطريق فيه زحمة خد طريق تاني، وفي النص هاتلي بنزين وقهوة")
        assertEquals("قبل ٨", 8, p.deadline?.hour)
        assertEquals(0, p.deadline?.minute)
        assertEquals(5, p.rerouteIfDelayMinutes)
        assertTrue(p.avoidTraffic)
        assertEquals("destination should be parsed", true, p.hasNavigationIntent)
        assertEquals("المطار", DriveAiCore.plan("فارس خدني المطار وفي النص هاتلي بنزين وقهوة").destinationQuery)
        assertEquals(2, p.stops.size)
        assertEquals(DriveAiCore.StopType.FUEL, p.stops[0].type)
        assertEquals(DriveAiCore.StopType.COFFEE, p.stops[1].type)
        assertTrue(p.isCompound)
    }

    @Test fun nearbyFuelQueryDoesNotBecomeAnAutomaticStop() {
        val p = DriveAiCore.plan("فارس فين أقرب محطة بنزين")
        assertTrue(p.stops.isEmpty())
    }

    @Test fun explicitTrafficThreshold_isRespected() {
        val p = DriveAiCore.plan("فارس وديني دبي مول، ولو الزحمة أكتر من عشر دقايق غير الطريق")
        assertEquals("دبي مول", p.destinationQuery)
        assertEquals(10, p.rerouteIfDelayMinutes)
        assertTrue(p.avoidTraffic)
    }

    @Test fun musicAndDestination_areSeparated() {
        val p = DriveAiCore.plan("فارس وديني المطار، شغل هادي")
        assertEquals("المطار", p.destinationQuery)
        assertEquals("هادي", p.musicQuery)
    }

    @Test fun multipleStops_preserveNaturalCategoryOrder() {
        val p = DriveAiCore.plan("فارس خدني المطار وفي النص هاتلي بنزين وقهوة")
        assertEquals(2, p.stops.size)
        assertEquals(DriveAiCore.StopType.FUEL, p.stops[0].type)
        assertEquals(DriveAiCore.StopType.COFFEE, p.stops[1].type)
    }

    @Test fun simpleDestination_isStillNotForcedIntoCompoundExecution() {
        val p = DriveAiCore.plan("فارس وديني دبي مول")
        assertEquals("دبي مول", p.destinationQuery)
        assertFalse(p.isCompound)
    }
}
