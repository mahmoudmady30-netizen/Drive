package com.drivevoice.mvp

/**
 * Stateful local conversation layer for FARES.
 * Keeps trip context between short voice turns without an external AI API.
 */
class FaresConversationEngine {
    data class Session(
        val destinationQuery: String? = null,
        val deadline: DriveAiCore.Deadline? = null,
        val rerouteIfDelayMinutes: Int? = null,
        val avoidTraffic: Boolean = false,
        val stops: List<DriveAiCore.PlannedStop> = emptyList(),
        val musicQuery: String? = null,
        val callName: String? = null
    )

    data class Turn(
        val session: Session,
        val plan: DriveAiCore.DrivingPlan,
        val isNewDestination: Boolean,
        val rebuildRoute: Boolean,
        val evaluateTraffic: Boolean,
        val handleDeadline: Boolean,
        val hasAction: Boolean,
        val acknowledgement: String? = null,
        val directAction: VoiceAction? = null
    )

    private var session = Session()

    fun reset() {
        session = Session()
    }

    fun current(): Session = session

    fun process(raw: String): Turn {
        val parsed = DriveAiCore.plan(raw)
        val lower = raw.lowercase()
        val hasDestination = parsed.destinationQuery != null
        val hasStop = parsed.stops.isNotEmpty()
        val hasDeadline = parsed.deadline != null
        val hasTrafficRule = parsed.rerouteIfDelayMinutes != null || parsed.avoidTraffic
        val hasMusic = parsed.musicQuery != null
        val hasCall = parsed.callName != null
        val directAction = LegacyCommandParser.parse(raw)
        val explicitCancel = directAction == VoiceAction.CancelRoute || listOf("الغى الرحلة", "الغي الرحلة", "الغى الطريق", "الغي الطريق", "إلغاء الرحلة", "cancel trip", "cancel route")
            .any { lower.contains(it) }

        if (explicitCancel) {
            reset()
            return Turn(Session(), parsed, false, false, false, false, true, "تمام، ألغيت سياق الرحلة")
        }

        val nextStops = if (hasStop) (session.stops + parsed.stops).distinctBy { it.type } else session.stops
        val nextDestination = parsed.destinationQuery ?: session.destinationQuery
        val nextDeadline = parsed.deadline ?: session.deadline
        val nextThreshold = parsed.rerouteIfDelayMinutes ?: session.rerouteIfDelayMinutes
        val nextAvoid = session.avoidTraffic || parsed.avoidTraffic
        val nextMusic = parsed.musicQuery ?: session.musicQuery
        val nextCall = parsed.callName ?: session.callName

        session = Session(nextDestination, nextDeadline, nextThreshold, nextAvoid, nextStops, nextMusic, nextCall)

        val plan = DriveAiCore.DrivingPlan(
            destinationQuery = parsed.destinationQuery,
            deadline = parsed.deadline,
            rerouteIfDelayMinutes = parsed.rerouteIfDelayMinutes,
            avoidTraffic = parsed.avoidTraffic,
            stops = if (hasStop) parsed.stops else emptyList(),
            musicQuery = parsed.musicQuery,
            callName = parsed.callName,
            hasNavigationIntent = parsed.hasNavigationIntent
        )

        val conversationalOnly = !hasDestination && !hasStop && !hasDeadline && !hasTrafficRule && !hasMusic && !hasCall && directAction == VoiceAction.Unknown
        val acknowledgement = when {
            conversationalOnly && listOf("تمام", "اوكي", "أوكي", "ماشي", "تمام يا فارس", "حلو").any { lower.contains(it) } ->
                "تمام، أنا فاكر تفاصيل الرحلة ومكمل معاك"
            conversationalOnly -> "فاهمك، ومكمل على نفس الرحلة"
            hasDestination -> "تمام، هحدث الرحلة على الوجهة الجديدة"
            hasStop -> "تمام، هضيف المحطة للخطة الحالية"
            hasTrafficRule -> "تمام، هراقب شرط الزحمة على الرحلة الحالية"
            hasDeadline -> "تمام، هحسب الوصول على الموعد ده"
            else -> null
        }

        return Turn(
            session = session,
            plan = plan,
            isNewDestination = hasDestination,
            rebuildRoute = hasDestination || hasStop,
            evaluateTraffic = hasTrafficRule,
            handleDeadline = hasDeadline,
            hasAction = !conversationalOnly,
            acknowledgement = acknowledgement,
            directAction = directAction.takeUnless { it == VoiceAction.Unknown }
        )
    }
}
