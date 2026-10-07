package com.drivevoice.mvp

import java.util.concurrent.CopyOnWriteArrayList

/** Shared in-process navigation state used by the phone UI and Android Auto service. */
object NavigationState {
    data class Snapshot(
        val route: MapsRepository.Route? = null,
        val destinationName: String = "",
        val destinationLat: Double = 0.0,
        val destinationLon: Double = 0.0,
        val currentLat: Double = 0.0,
        val currentLon: Double = 0.0,
        val currentStepIndex: Int = 0,
        val active: Boolean = false
    )

    interface Listener { fun onNavigationStateChanged(snapshot: Snapshot) }

    @Volatile private var snapshot = Snapshot()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun get(): Snapshot = snapshot

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onNavigationStateChanged(snapshot)
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    fun start(route: MapsRepository.Route, name: String, lat: Double, lon: Double, currentLat: Double, currentLon: Double) {
        update(snapshot.copy(route = route, destinationName = name, destinationLat = lat, destinationLon = lon,
            currentLat = currentLat, currentLon = currentLon, currentStepIndex = 0, active = true))
    }

    fun updatePosition(lat: Double, lon: Double, stepIndex: Int = snapshot.currentStepIndex) {
        update(snapshot.copy(currentLat = lat, currentLon = lon, currentStepIndex = stepIndex))
    }

    fun updateRoute(route: MapsRepository.Route, currentLat: Double, currentLon: Double, stepIndex: Int = 0) {
        update(snapshot.copy(route = route, currentLat = currentLat, currentLon = currentLon, currentStepIndex = stepIndex, active = true))
    }

    fun stop() { update(Snapshot()) }

    private fun update(value: Snapshot) {
        snapshot = value
        listeners.forEach { it.onNavigationStateChanged(value) }
    }
}
