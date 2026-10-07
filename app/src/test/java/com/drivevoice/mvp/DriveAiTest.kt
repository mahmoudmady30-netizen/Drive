package com.drivevoice.mvp

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class DriveAiTest {
    private val engine = DriveAiEngine()
    private val route = MapsRepository.Route(emptyList(), 6000.0, 600.0)

    @Test fun traffic_delay_can_trigger_reroute() {
        val d = engine.applyTraffic(route, TrafficProvider.Snapshot(300, 0.5, "test"), ZonedDateTime.parse("2026-10-06T10:00:00Z"))
        assertTrue(d.shouldReroute)
        assertTrue(d.eta.trafficDelayMinutes >= 8)
    }

    @Test fun traffic_status_without_delay_does_not_trigger() {
        val d = engine.applyTraffic(route, TrafficProvider.Snapshot(0, 1.0, "test"))
        assertFalse(d.shouldReroute)
        assertEquals(10, d.eta.minutes)
    }

    @Test fun deadline_calculation_is_positive_when_early() {
        val now = ZonedDateTime.parse("2026-10-06T10:00:00Z")
        assertEquals(50, engine.minutesUntil(11, 0, now, 600))
    }
}
