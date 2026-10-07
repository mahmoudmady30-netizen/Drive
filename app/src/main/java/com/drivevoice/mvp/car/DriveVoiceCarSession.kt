package com.drivevoice.mvp.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import com.drivevoice.mvp.MapsRepository
import com.drivevoice.mvp.NavigationState
import java.time.ZonedDateTime
import kotlin.math.max

class DriveVoiceCarSession : Session() {
    private var screen: DriveVoiceCarScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        screen = DriveVoiceCarScreen(carContext)
        return screen!!
    }
}

class DriveVoiceCarScreen(carContext: CarContext) : Screen(carContext), NavigationState.Listener {
    private val navigationManager = carContext.getCarService(CarContext.NAVIGATION_SERVICE) as NavigationManager
    private var state = NavigationState.get()
    private var navigationStarted = false

    init {
        NavigationState.addListener(this)
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                navigationStarted = false
                NavigationState.stop()
                invalidate()
            }

            override fun onAutoDriveEnabled() {
                // Android Auto's simulation mode is intentionally passive here.
                // Real GPS remains the source of truth when a route is active.
            }
        })
        if (state.active) startNavigationIfNeeded()
    }

    override fun onNavigationStateChanged(snapshot: NavigationState.Snapshot) {
        state = snapshot
        if (snapshot.active) startNavigationIfNeeded()
        else if (navigationStarted) {
            navigationManager.navigationEnded()
            navigationStarted = false
        }
        if (snapshot.active && navigationStarted) updateTripSafely()
        invalidate()
    }

    private fun startNavigationIfNeeded() {
        if (navigationStarted) return
        try {
            navigationManager.navigationStarted()
            navigationStarted = true
            updateTripSafely()
        } catch (_: Exception) {
            navigationStarted = false
        }
    }

    private fun updateTripSafely() {
        if (!navigationStarted) return
        try { navigationManager.updateTrip(buildTrip(state)) } catch (_: Exception) { }
    }

    private fun buildTrip(s: NavigationState.Snapshot): Trip {
        val route = s.route
        val builder = Trip.Builder()
        if (route == null || !s.active) return builder.setLoading(true).build()

        val startIndex = s.currentStepIndex.coerceIn(0, route.steps.size)
        val remainingSteps = route.steps.drop(startIndex)
        val remainingDistanceM = remainingSteps.sumOf { it.distanceM }.coerceAtLeast(0.0)
        val remainingSeconds = remainingSteps.sumOf { it.durationS }.toLong().coerceAtLeast(0L)
        val destination = Destination.Builder()
            .setName(s.destinationName.ifBlank { "الوجهة" })
            .build()
        builder.addDestination(destination, estimate(remainingDistanceM, remainingSeconds))

        var cumulativeDistance = 0.0
        var cumulativeTime = 0.0
        remainingSteps.take(8).forEach { rs ->
            cumulativeDistance += rs.distanceM
            cumulativeTime += rs.durationS
            val step = Step.Builder(rs.instruction)
                .setManeuver(toManeuver(rs))
                .apply { if (rs.roadName.isNotBlank()) setRoad(rs.roadName) }
                .build()
            val stepRemaining = max(0L, (remainingSeconds - cumulativeTime).toLong())
            builder.addStep(step, estimate(max(0.0, remainingDistanceM - cumulativeDistance), stepRemaining))
        }
        if (startIndex < route.steps.size && route.steps[startIndex].roadName.isNotBlank()) {
            builder.setCurrentRoad(route.steps[startIndex].roadName)
        }
        return builder.build()
    }

    private fun estimate(distanceM: Double, seconds: Long): TravelEstimate {
        val km = max(0.0, distanceM / 1000.0)
        val arrival = ZonedDateTime.now().plusSeconds(seconds)
        return TravelEstimate.Builder(Distance.create(km, Distance.UNIT_KILOMETERS), arrival)
            .setRemainingTimeSeconds(seconds)
            .build()
    }

    private fun toManeuver(step: MapsRepository.RouteStep): Maneuver {
        val type = step.maneuverType
        val mod = step.modifier
        val code = when (type) {
            "depart" -> Maneuver.TYPE_DEPART
            "arrive" -> Maneuver.TYPE_DESTINATION
            "turn" -> when (mod) {
                "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
                "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
                "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
                "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
                "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
                "uturn" -> Maneuver.TYPE_U_TURN_RIGHT
                else -> Maneuver.TYPE_STRAIGHT
            }
            "roundabout", "rotary" -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            "merge" -> Maneuver.TYPE_MERGE_RIGHT
            "fork" -> if (mod.contains("left")) Maneuver.TYPE_FORK_LEFT else Maneuver.TYPE_FORK_RIGHT
            "on ramp" -> if (mod.contains("left")) Maneuver.TYPE_ON_RAMP_NORMAL_LEFT else Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            "off ramp" -> if (mod.contains("left")) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            else -> Maneuver.TYPE_STRAIGHT
        }
        return Maneuver.Builder(code).build()
    }

    override fun onGetTemplate(): androidx.car.app.model.Template {
        val route = state.route
        val actionStrip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("فارس").setOnClickListener { invalidate() }.build())
            .addAction(Action.Builder().setTitle("إلغاء").setOnClickListener {
                NavigationState.stop()
                invalidate()
            }.build())
            .build()
        val builder = NavigationTemplate.Builder().setActionStrip(actionStrip)
        if (route != null && state.active) {
            val remaining = route.steps.drop(state.currentStepIndex.coerceIn(0, route.steps.size))
            builder.setDestinationTravelEstimate(estimate(remaining.sumOf { it.distanceM }, remaining.sumOf { it.durationS }.toLong()))
        }
        return builder.build()
    }

    // AndroidX Car App Screen does not expose an overridable onDestroy()
    // callback in the API used by this project. Navigation resources are
    // therefore managed by the navigation manager/session lifecycle.
}
