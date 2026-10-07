package com.drivevoice.mvp

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TripProgressTest {
    private fun step(d: Double, t: Double, text: String, type: String = "turn", mod: String = "", road: String = "") =
        MapsRepository.RouteStep(d, t, text, 0.0, 0.0, type, mod, road)

    @Test fun distance_formatting() {
        assertEquals("400 متر", TripFormat.distance(400.0))
        assertEquals("450 متر", TripFormat.distance(430.0))
        assertEquals("100 متر", TripFormat.distance(97.0))
        assertEquals("10 متر", TripFormat.distance(5.0))
        assertEquals("1 كم", TripFormat.distance(980.0))
        assertEquals("2.5 كم", TripFormat.distance(2500.0))
        assertEquals("28 كم", TripFormat.distance(28000.0))
    }

    @Test fun duration_formatting() {
        assertEquals("أقل من دقيقة", TripFormat.duration(30.0))
        assertEquals("31 دقيقة", TripFormat.duration(31 * 60.0))
        assertEquals("1 ساعة و5 دقيقة", TripFormat.duration(65 * 60.0))
        assertEquals("2 ساعة", TripFormat.duration(120 * 60.0))
    }

    @Test fun clock_formatting() {
        val pm = ZonedDateTime.of(2026, 10, 7, 19, 52, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val am = ZonedDateTime.of(2026, 10, 7, 0, 5, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("7:52 م", TripFormat.clock(pm, ZoneOffset.UTC))
        assertEquals("12:05 ص", TripFormat.clock(am, ZoneOffset.UTC))
    }

    @Test fun arrows() {
        assertEquals("↰", TripFormat.arrow("turn", "left"))
        assertEquals("↱", TripFormat.arrow("turn", "right"))
        assertEquals("↶", TripFormat.arrow("turn", "uturn"))
        assertEquals("⚑", TripFormat.arrow("arrive", ""))
        assertEquals("↑", TripFormat.arrow("continue", ""))
    }

    @Test fun progress_uses_real_remaining_steps() {
        val steps = listOf(
            step(100.0, 10.0, "ابدأ", "depart"),
            step(900.0, 90.0, "لف يمين", "turn", "right", "شارع النيل"),
            step(500.0, 50.0, "وصلت", "arrive")
        )
        val p = TripProgressCalc.compute(steps, 1, 1500.0, 150.0, 400.0, 1_000L)
        assertEquals("↱", p.arrow)
        assertEquals("لف يمين", p.instruction)
        assertEquals("شارع النيل", p.road)
        assertEquals(1800.0, p.remainingDistanceM, 0.001)
        assertEquals(180.0, p.remainingSeconds, 0.001)
        assertEquals(1_000L + 180_000L, p.arrivalEpochMs)
    }

    @Test fun progress_without_steps_falls_back_to_route_totals() {
        val p = TripProgressCalc.compute(emptyList(), 0, 3000.0, 300.0, 999.0, 0L)
        assertEquals(3000.0, p.remainingDistanceM, 0.001)
        assertEquals(300.0, p.remainingSeconds, 0.001)
        assertEquals("تابع على الطريق", p.instruction)
    }

    @Test fun progress_past_last_step_does_not_crash() {
        val p = TripProgressCalc.compute(listOf(step(10.0, 1.0, "x")), 5, 10.0, 1.0, 20.0, 0L)
        assertEquals(20.0, p.remainingDistanceM, 0.001)
    }

    @Test fun arrival_needs_two_good_fixes() {
        val d = ArrivalDetector()
        assertFalse(d.onFix(30.0, 10f)); assertTrue(d.onFix(30.0, 10f))
    }
    @Test fun arrival_ignores_jitter_and_bad_accuracy() {
        val d = ArrivalDetector()
        assertFalse(d.onFix(30.0, 10f)); assertFalse(d.onFix(300.0, 10f)); assertFalse(d.onFix(30.0, 10f))
        repeat(5) { assertFalse(ArrivalDetector().onFix(5.0, 150f)) }
    }
}
