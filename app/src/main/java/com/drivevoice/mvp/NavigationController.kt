package com.drivevoice.mvp

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Owns navigation math/state transitions. UI and voice are injected as callbacks. */
class NavigationController(
    private val maps: MapsRepository,
    private val session: TripSession,
    private val onSpeak: (String) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onDrawRoute: (MapsRepository.Route) -> Unit,
    private val onCameraDestination: (Double, Double) -> Unit,
    private val onSessionChanged: (MapsRepository.Route?, MapsRepository.Place?, Int) -> Unit = { _, _, _ -> },
    private val onNavigationStarted: (DriveAiCore.DrivingPlan?, MapsRepository.Route) -> Unit = { _, _ -> }
) {
    var latitude: Double = 25.2048
        private set
    var longitude: Double = 55.2708
        private set

    fun updateLocation(location: Location) {
        updateLocation(location.latitude, location.longitude)
    }

    fun updateLocation(lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
        val route = session.route ?: run {
            NavigationState.updatePosition(lat, lon, 0)
            return
        }
        NavigationState.updatePosition(lat, lon, session.stepIndex)
        val destination = session.destination ?: return

        val offRoute = distanceMeters(lat, lon, destination.lat, destination.lon) > 250.0 &&
            session.stepIndex < route.steps.size &&
            distanceMeters(lat, lon, route.steps[session.stepIndex].lat, route.steps[session.stepIndex].lon) > 350.0
        if (offRoute && System.currentTimeMillis() - session.lastRerouteAt > 15_000L) {
            session.markReroute(System.currentTimeMillis())
            reroute()
            return
        }

        val index = session.stepIndex
        if (route.steps.isEmpty() || index >= route.steps.size) return
        val step = route.steps[index]
        if (distanceMeters(lat, lon, step.lat, step.lon) <= 120.0) {
            onSpeak(step.instruction)
            onStatus(step.instruction, if (index + 1 < route.steps.size) "التالي: ${route.steps[index + 1].instruction}" else "أنت قريب من الوصول")
            session.advanceStep()
            NavigationState.updatePosition(lat, lon, session.stepIndex)
            onSessionChanged(session.route, session.destination, session.stepIndex)
        }
    }

    fun startNavigation(place: MapsRepository.Place, plan: DriveAiCore.DrivingPlan? = null) {
        maps.route(latitude, longitude, place.lat, place.lon) { route ->
            if (route == null) {
                onStatus("تعذر حساب الطريق", "")
                onSpeak("تعذر حساب الطريق")
                return@route
            }
            session.start(route, place, plan)
            onSessionChanged(session.route, session.destination, session.stepIndex)
            onNavigationStarted(plan, route)
            NavigationState.start(route, place.name, place.lat, place.lon, latitude, longitude)
            render(route, place)
            val first = route.steps.firstOrNull()?.instruction?.let { " $it." } ?: ""
            onSpeak("بدأت الملاحة إلى ${place.name}.$first الوصول المتوقع ${formatMinutes(route.durationS)}")
        }
    }

    fun startPlannedNavigation(plan: DriveAiCore.DrivingPlan, destination: MapsRepository.Place, stops: List<MapsRepository.Place>) {
        val stopCoords = stops.map { it.lat to it.lon }
        val callback: (MapsRepository.Route?) -> Unit = callback@{ route ->
            if (route == null) {
                onStatus("تعذر حساب الخطة", "")
                onSpeak("تعذر حساب الطريق بالخطة كاملة")
                return@callback
            }
            session.start(route, destination, plan)
            onSessionChanged(session.route, session.destination, session.stepIndex)
            onNavigationStarted(plan, route)
            NavigationState.start(route, destination.name, destination.lat, destination.lon, latitude, longitude)
            render(route, destination, stops.size)
            val stopNames = stops.joinToString(" ثم ") { it.name }
            onSpeak("بدأت الخطة إلى ${destination.name}${if (stopNames.isNotBlank()) "، مرورًا بـ $stopNames" else ""}. الوصول الأساسي ${formatMinutes(route.durationS)}")
        }
        if (stopCoords.isEmpty()) maps.route(latitude, longitude, destination.lat, destination.lon, callback)
        else maps.routeViaStops(latitude, longitude, stopCoords, destination.lat, destination.lon, callback)
    }

    fun addStop(stop: MapsRepository.Place) {
        val destination = session.destination ?: return
        maps.routeViaStop(latitude, longitude, stop.lat, stop.lon, destination.lat, destination.lon) { route ->
            if (route == null) {
                onSpeak("مش قادر أضيف المحطة للطريق")
                return@routeViaStop
            }
            session.replaceRoute(route)
            onSessionChanged(session.route, session.destination, session.stepIndex)
            NavigationState.updateRoute(route, latitude, longitude, 0)
            render(route, destination)
            onSpeak("تمام، ضفت ${stop.name} كمحطة في الطريق وبعدها هنكمل إلى ${destination.name}")
        }
    }

    fun reroute(alternative: Boolean = false) {
        val destination = session.destination ?: return
        if (alternative) {
            maps.routeAlternative(latitude, longitude, destination.lat, destination.lon) { route, hasAlternative ->
                if (route == null) {
                    onSpeak("مش قادر أحسب طريق بديل دلوقتي")
                    return@routeAlternative
                }
                session.replaceRoute(route)
                onSessionChanged(session.route, session.destination, session.stepIndex)
                NavigationState.updateRoute(route, latitude, longitude, 0)
                render(route, destination)
                if (hasAlternative) onSpeak("لقيت طريق بديل. المتبقي ${formatKm(route.distanceM)} ووقت الوصول ${formatMinutes(route.durationS)}")
                else onSpeak("مزود الخرائط لم يرجع طريقًا بديلًا. أبقيت المسار الحالي")
            }
        } else {
            maps.route(latitude, longitude, destination.lat, destination.lon) { route ->
                if (route == null) {
                    onSpeak("تعذر إعادة حساب الطريق")
                    return@route
                }
                session.replaceRoute(route)
                onSessionChanged(session.route, session.destination, session.stepIndex)
                NavigationState.updateRoute(route, latitude, longitude, 0)
                render(route, destination)
                onSpeak("أعدت حساب الطريق")
            }
        }
    }

    fun clear() {
        session.clear()
        onSessionChanged(null, null, 0)
        NavigationState.stop()
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun render(route: MapsRepository.Route, destination: MapsRepository.Place, stopCount: Int = 0) {
        onDrawRoute(route)
        onCameraDestination(destination.lat, destination.lon)
        onStatus("متجه إلى ${destination.name}", "${formatKm(route.distanceM)} • ${formatMinutes(route.durationS)}${if (stopCount > 0) " • $stopCount محطة" else ""}")
    }

    private fun formatKm(m: Double): String = if (m < 1000) "${m.toInt()} متر" else "%.1f كم".format(java.util.Locale.US, m / 1000.0)
    private fun formatMinutes(s: Double): String = if (s < 60) "أقل من دقيقة" else "${kotlin.math.round(s / 60).toInt()} دقيقة"
}
