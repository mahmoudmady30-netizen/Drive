package com.drivevoice.mvp

/** Single source of truth for the active driving session. No UI state lives here. */
class TripSession {
    var route: MapsRepository.Route? = null
        private set
    var destination: MapsRepository.Place? = null
        private set
    var stepIndex: Int = 0
        private set
    var lastRerouteAt: Long = 0L
        private set
    var activePlan: DriveAiCore.DrivingPlan? = null
        private set

    val isActive: Boolean get() = route != null && destination != null

    fun start(route: MapsRepository.Route, destination: MapsRepository.Place, plan: DriveAiCore.DrivingPlan? = null) {
        this.route = route
        this.destination = destination
        this.stepIndex = 0
        this.activePlan = plan
        this.lastRerouteAt = 0L
    }

    fun replaceRoute(route: MapsRepository.Route) {
        this.route = route
        this.stepIndex = 0
    }

    fun advanceStep() { stepIndex++ }
    fun markReroute(now: Long) { lastRerouteAt = now }

    fun clear() {
        route = null
        destination = null
        stepIndex = 0
        lastRerouteAt = 0L
        activePlan = null
    }
}
