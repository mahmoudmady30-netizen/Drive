package com.drivevoice.mvp

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class StopRequest(val kind: PlaceKind?, val query: String? = null) {
    val label: String get() = kind?.label ?: query.orEmpty()
}

enum class TripStatus { IDLE, NAVIGATING, ARRIVED }

/** Pure trip *intent* state (what the user asked for). Route geometry lives in the routing layer. */
data class TripState(
    val destination: String? = null,
    val stops: List<StopRequest> = emptyList(),
    val deadline: Deadline? = null,
    val trafficRule: TrafficRule? = null,
    val hurry: Boolean = false,
    val status: TripStatus = TripStatus.IDLE
) {
    val isActive get() = status == TripStatus.NAVIGATING && destination != null
}

/** Process-lifetime holder so the trip intent survives Activity recreation (like NavigationState). */
object TripStore { @Volatile var state: TripState = TripState() }

/** What the app must do. The Android layer executes these; nothing here touches Android. */
sealed class Effect {
    data class Speak(val text: String) : Effect()
    data class StartNavigation(val state: TripState) : Effect()
    data class RebuildRoute(val state: TripState) : Effect()
    data class SwitchToAlternative(val fastest: Boolean) : Effect()
    data object Recalculate : Effect()
    data object CancelTrip : Effect()
    data class SearchNearby(val kind: PlaceKind) : Effect()
    data class SearchPlace(val query: String) : Effect()
    data object AnswerEta : Effect()
    data object AnswerTraffic : Effect()
    data object AnswerWhere : Effect()
    data object RepeatLast : Effect()
    data class Media(val command: VoiceCommand) : Effect()
    data class Call(val name: String) : Effect()
    data object EvaluateDeadline : Effect()
}

/** Turns a parsed utterance + current trip context into a new trip state and effects. */
object TripPlanner {
    class Result(val state: TripState, val effects: List<Effect>)

    fun apply(state: TripState, u: ParsedUtterance): Result {
        var s = state
        val fx = mutableListOf<Effect>()
        val cmds = u.commands
        val hasTrip = s.isActive
        var needsRebuild = false
        var started = false

        for (c in cmds) when (c) {
            is VoiceCommand.Navigate -> {
                s = s.copy(destination = c.destination, status = TripStatus.NAVIGATING, stops = if (hasTrip) s.stops else emptyList())
                started = true
            }
            is VoiceCommand.ArrivalBy -> s = s.copy(deadline = c.deadline)
            is VoiceCommand.Hurry -> s = s.copy(hurry = true)
            is VoiceCommand.SetTrafficRule -> s = s.copy(trafficRule = c.rule)
            is VoiceCommand.AddStop -> {
                val req = StopRequest(c.kind, c.query)
                if (s.stops.none { it.kind != null && it.kind == req.kind && req.query == null }) s = s.copy(stops = s.stops + req)
                if (hasTrip && !started) needsRebuild = true
            }
            else -> Unit
        }

        // Stops/deadline given without any destination or trip cannot be executed: say so honestly.
        val tripless = !s.isActive
        if (started) fx += Effect.StartNavigation(s)
        else if (needsRebuild) fx += Effect.RebuildRoute(s)

        for (c in cmds) when (c) {
            is VoiceCommand.AddStop -> if (tripless && !started) fx += Effect.Speak("مفيش رحلة شغالة، قولي رايح فين الأول.")
            is VoiceCommand.TrafficAlternative ->
                fx += if (hasTrip || started) Effect.SwitchToAlternative(c.fastest) else Effect.Speak("مفيش رحلة شغالة أغير طريقها.")
            is VoiceCommand.Reroute ->
                fx += if (hasTrip || started) Effect.Recalculate else Effect.Speak("مفيش رحلة شغالة.")
            is VoiceCommand.CancelRoute -> {
                if (hasTrip) { s = TripState(); fx += Effect.CancelTrip } else fx += Effect.Speak("مفيش رحلة شغالة.")
            }
            is VoiceCommand.Eta, is VoiceCommand.TrafficStatus ->
                fx += if (hasTrip || started) (if (c is VoiceCommand.Eta) Effect.AnswerEta else Effect.AnswerTraffic)
                else Effect.Speak("مفيش رحلة شغالة.")
            is VoiceCommand.ArrivalBy -> if (hasTrip && !started) fx += Effect.EvaluateDeadline
            else if (!hasTrip && !started) fx += Effect.Speak("تمام، قولي رايح فين.")
            is VoiceCommand.WhereAmI -> fx += Effect.AnswerWhere
            is VoiceCommand.Repeat -> fx += Effect.RepeatLast
            is VoiceCommand.Nearby -> fx += Effect.SearchNearby(c.kind)
            is VoiceCommand.SearchPlace -> fx += Effect.SearchPlace(c.query)
            is VoiceCommand.Play, VoiceCommand.Pause, VoiceCommand.Next, VoiceCommand.Previous,
            VoiceCommand.VolumeUp, VoiceCommand.VolumeDown, VoiceCommand.Mute, is VoiceCommand.MusicSearch -> fx += Effect.Media(c)
            is VoiceCommand.Call -> fx += Effect.Call(c.name)
            is VoiceCommand.RemoveStop -> if (hasTrip && s.stops.isNotEmpty()) {
                s = s.copy(stops = s.stops.dropLast(1)); fx += Effect.RebuildRoute(s)
            } else fx += Effect.Speak("مفيش محطات أشيلها.")
            is VoiceCommand.Unknown -> fx += Effect.Speak("مفهمتش، قولها تاني.")
            else -> Unit
        }
        // Deadline given together with a new destination is evaluated once the route is known.
        if (started && s.deadline != null) fx += Effect.EvaluateDeadline
        return Result(s, fx.distinct())
    }
}

/** ETA vs arrival deadline. Never invents numbers: callers must pass a real route ETA. */
object DeadlineMath {
    data class Verdict(val arrivalHour: Int, val arrivalMinute: Int, val lateMinutes: Int) {
        val onTime get() = lateMinutes <= 0
        val arrivalText get() = "%d:%02d".format(arrivalHour, arrivalMinute)
    }

    /** Next instant matching the spoken clock time ("قبل 8" at 7pm means 8pm, at 9am means tomorrow 8am? no: 8pm). */
    fun resolve(deadline: Deadline, now: ZonedDateTime): ZonedDateTime {
        val day = now.toLocalDate()
        val cands = buildList {
            add(day.atTime(deadline.hour, deadline.minute).atZone(now.zone))
            if (deadline.hour in 1..11) add(day.atTime(deadline.hour + 12, deadline.minute).atZone(now.zone))
            add(day.plusDays(1).atTime(deadline.hour, deadline.minute).atZone(now.zone))
        }
        return cands.first { it.isAfter(now) }
    }

    fun evaluate(deadline: Deadline, etaSeconds: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Verdict {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val due = resolve(deadline, now)
        val arrival = now.plusSeconds(etaSeconds)
        val late = java.time.Duration.between(due, arrival).toMinutes().toInt()
        val lateRounded = if (arrival.isAfter(due) && late == 0) 1 else late
        return Verdict(arrival.hour.let { if (it % 12 == 0) 12 else it % 12 }, arrival.minute, lateRounded)
    }

    fun speak(v: Verdict): String =
        if (v.onTime) "الطريق الحالي هيوصلك ${v.arrivalText}، في الميعاد."
        else "الطريق الحالي هيوصلك ${v.arrivalText}، يعني متأخر حوالي ${v.lateMinutes} دقيقة."
}

/** Decides whether a faster alternative justifies interrupting the driver. */
object RerouteDecision {
    enum class Kind { NONE, SUGGEST, AUTO }
    data class Outcome(val kind: Kind, val savedMinutes: Int = 0)

    fun evaluate(
        currentEtaSec: Long, bestAltEtaSec: Long?, rule: TrafficRule?, nowMs: Long, lastPromptMs: Long,
        cooldownMs: Long = 120_000L
    ): Outcome {
        if (rule == null || bestAltEtaSec == null) return Outcome(Kind.NONE)
        if (nowMs - lastPromptMs < cooldownMs) return Outcome(Kind.NONE)
        val saved = ((currentEtaSec - bestAltEtaSec) / 60).toInt()
        if (saved < rule.rerouteIfDelayMinutes || saved <= 0) return Outcome(Kind.NONE)
        return Outcome(if (rule.auto) Kind.AUTO else Kind.SUGGEST, saved)
    }

    fun speak(o: Outcome) = when (o.kind) {
        Kind.AUTO -> "الطريق زحم، غيرت لطريق أسرع بحوالي ${o.savedMinutes} دقيقة."
        Kind.SUGGEST -> "في طريق بديل أسرع بحوالي ${o.savedMinutes} دقيقة، أغيره؟"
        Kind.NONE -> ""
    }
}

/** Debounced off-route detection: ignores poor fixes and GPS jumps, needs consecutive evidence. */
class OffRouteDetector(private val consecutiveNeeded: Int = 3, private val baseThresholdM: Double = 50.0) {
    private var strikes = 0
    private var lastDistance = 0.0

    fun reset() { strikes = 0 }

    /** @return true exactly when a reroute should be triggered. */
    fun onFix(distanceToRouteM: Double, accuracyM: Float): Boolean {
        if (accuracyM > 60f) return false                       // too inaccurate to judge
        val threshold = maxOf(baseThresholdM, accuracyM * 2.0)
        if (distanceToRouteM <= threshold) { strikes = 0; lastDistance = distanceToRouteM; return false }
        strikes++
        lastDistance = distanceToRouteM
        if (strikes >= consecutiveNeeded) { strikes = 0; return true }
        return false
    }
}
