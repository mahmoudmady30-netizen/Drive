package com.drivevoice.mvp

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING, ERROR }

/** Driver-facing formatting. Pure, so it can be unit-tested. */
object TripFormat {
    fun distance(m: Double): String {
        if (m < 1000) {
            val step = if (m < 100) 10.0 else 50.0
            val v = max(step, Math.round(m / step) * step).toInt()
            return if (v >= 1000) "1 كم" else "$v متر"
        }
        val km = Math.round(m / 100.0) / 10.0
        return if (km == km.toLong().toDouble()) "${km.toLong()} كم" else "$km كم"
    }

    fun duration(s: Double): String {
        if (s < 60) return "أقل من دقيقة"
        val total = (s / 60).roundToInt()
        if (total < 60) return "$total دقيقة"
        val h = total / 60
        val m = total % 60
        return if (m == 0) "$h ساعة" else "$h ساعة و$m دقيقة"
    }

    fun clock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "%d:%02d %s".format(h12, t.minute, if (t.hour < 12) "ص" else "م")
    }

    /** OSRM maneuver type + modifier -> a plain arrow glyph. */
    fun arrow(type: String, modifier: String): String {
        if (type == "arrive") return "⚑"
        return when (modifier) {
            "left" -> "↰"
            "right" -> "↱"
            "slight left" -> "↖"
            "slight right" -> "↗"
            "sharp left" -> "↙"
            "sharp right" -> "↘"
            "uturn" -> "↶"
            else -> "↑"
        }
    }
}

data class TripProgress(
    val arrow: String,
    val nextDistanceM: Double,
    val instruction: String,
    val road: String,
    val remainingDistanceM: Double,
    val remainingSeconds: Double,
    val arrivalEpochMs: Long
)

object TripProgressCalc {
    /**
     * @param stepIndex index of the next maneuver; @param distToNextM straight-line metres to that maneuver.
     * Remaining = distance to the next maneuver + every step from that maneuver onward (real route data only).
     */
    fun compute(
        steps: List<MapsRepository.RouteStep>,
        stepIndex: Int,
        routeDistanceM: Double,
        routeDurationS: Double,
        distToNextM: Double,
        nowMs: Long
    ): TripProgress {
        val avgSpeed = if (routeDurationS > 0) routeDistanceM / routeDurationS else 10.0
        val i = stepIndex.coerceAtLeast(0)
        val next = steps.getOrNull(i)
        val tailDist = if (steps.isEmpty()) routeDistanceM else steps.drop(i).sumOf { it.distanceM }
        val tailTime = if (steps.isEmpty()) routeDurationS else steps.drop(i).sumOf { it.durationS }
        val toNext = if (steps.isEmpty()) 0.0 else distToNextM.coerceAtLeast(0.0)
        val remDist = tailDist + toNext
        val remTime = tailTime + (if (avgSpeed > 0) toNext / avgSpeed else 0.0)
        return TripProgress(
            arrow = next?.let { TripFormat.arrow(it.maneuverType, it.modifier) } ?: "↑",
            nextDistanceM = toNext,
            instruction = next?.instruction?.takeIf { it.isNotBlank() } ?: "تابع على الطريق",
            road = next?.roadName.orEmpty(),
            remainingDistanceM = remDist,
            remainingSeconds = remTime,
            arrivalEpochMs = nowMs + (remTime * 1000).toLong()
        )
    }
}

/** Declares arrival only after consecutive good fixes inside the radius (GPS jitter safe). */
class ArrivalDetector(private val radiusM: Double = 40.0, private val consecutive: Int = 2) {
    private var hits = 0
    fun reset() { hits = 0 }
    fun onFix(distanceToDestinationM: Double, accuracyM: Float): Boolean {
        if (accuracyM > 100f) return false
        val limit = max(radiusM, accuracyM.toDouble().coerceAtMost(80.0))
        if (abs(distanceToDestinationM) <= limit) hits++ else hits = 0
        if (hits >= consecutive) { hits = 0; return true }
        return false
    }
}
