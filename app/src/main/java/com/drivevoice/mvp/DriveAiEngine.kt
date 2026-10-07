package com.drivevoice.mvp

import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Deterministic driving intelligence kept separate from Activity/UI. */
class DriveAiEngine(private val trafficProvider: TrafficProvider = TrafficProvider()) {
    data class Eta(val arrival: ZonedDateTime, val minutes: Long, val trafficDelayMinutes: Long)
    data class TrafficDecision(val eta: Eta, val shouldReroute: Boolean, val explanation: String)

    fun evaluate(route: MapsRepository.Route, now: ZonedDateTime = ZonedDateTime.now(), callback: (TrafficDecision) -> Unit) {
        val baseSeconds = route.durationS.coerceAtLeast(0.0)
        val fallback = TrafficDecision(
            Eta(now.plusSeconds(baseSeconds.toLong()), (baseSeconds / 60).roundToInt().toLong(), 0),
            false,
            "ETA حسب بيانات الطريق الحالية"
        )
        // No destination coordinates are stored in Route, so provider enrichment is performed by caller.
        callback(fallback)
    }

    fun applyTraffic(route: MapsRepository.Route, traffic: TrafficProvider.Snapshot, now: ZonedDateTime = ZonedDateTime.now()): TrafficDecision {
        val adjusted = (route.durationS / traffic.speedFactor).toLong() + traffic.delaySeconds
        val delay = ((adjusted - route.durationS).coerceAtLeast(0.0) / 60.0).roundToInt().toLong()
        return TrafficDecision(
            Eta(now.plusSeconds(adjusted), (adjusted / 60.0).roundToInt().toLong(), delay),
            delay >= 8,
            if (delay >= 8) "الزحمة قد تضيف حوالي $delay دقيقة" else "حالة الطريق لا تستدعي تغيير المسار"
        )
    }

    fun minutesUntil(targetHour: Int, targetMinute: Int, now: ZonedDateTime, routeSeconds: Long): Long {
        var target = now.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now.plusSeconds(routeSeconds), target).toMinutes()
    }
}
