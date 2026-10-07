package com.drivevoice.mvp

import android.Manifest
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

/**
 * Orchestration only: permissions, lifecycle, wiring voice -> parser -> planner -> effects.
 * Rendering lives in [HomeUi]; language understanding in [CommandParser]/[TripPlanner] (unit-tested, Android-free).
 */
class MainActivity : AppCompatActivity(), VoiceCommandEngine.Listener, TextToSpeech.OnInitListener {
    private lateinit var ui: HomeUi
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var maps: MapsRepository
    private lateinit var driveAi: DriveAiEngine
    private lateinit var traffic: TrafficProvider
    private lateinit var tripSession: TripSession
    private lateinit var navigationController: NavigationController
    private lateinit var tts: TextToSpeech
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var voiceOverlay: View
    private lateinit var btnVoice: View

    private var tripState: TripState
        get() = TripStore.state
        set(value) { TripStore.state = value }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { if (ui.voiceState != VoiceState.SPEAKING) ui.setVoiceState(VoiceState.IDLE) }
    private val arrivalDetector = ArrivalDetector()
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasFix = false
    private var lastRoute: MapsRepository.Route? = null
    private var routeStepIndex = 0
    private var destinationLat = 0.0
    private var destinationLon = 0.0
    private var destinationName = ""
    private var lastProgress: TripProgress? = null
    private var activePlan: DriveAiCore.DrivingPlan? = null
    private var wasActive = false
    private var tripStartMs = 0L
    private var tripStartDistanceM = 0.0
    private var pendingSpeech = 0
    private var lastSpoken = ""
    private var locationStarted = false
    private var voiceStarted = false
    private var permBlocked = false
    private var activityDestroyed = false
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val navListener = object : NavigationState.Listener {
        override fun onNavigationStateChanged(snapshot: NavigationState.Snapshot) {
            runOnUiThread { renderNavigation(snapshot) }
        }
    }

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (activityDestroyed || intent == null) return
            val text = intent.getStringExtra(VoiceForegroundService.EXTRA_TEXT).orEmpty()
            when (intent.action) {
                VoiceForegroundService.ACTION_COMMAND -> if (text.isNotBlank()) onResult(text)
                VoiceForegroundService.ACTION_WAKE -> when {
                    text == "listening" || text == "idle" -> onListening(text == "listening")
                    text == VoiceForegroundService.WAKE_MARKER -> onWakeWord()
                    text.startsWith(VoiceForegroundService.ERROR_PREFIX) -> onError(text.removePrefix(VoiceForegroundService.ERROR_PREFIX))
                    text.isNotBlank() -> onPartial(text)
                }
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (loc.hasAccuracy() && loc.accuracy > 150f) return // too inaccurate to act on
            updateNavigationPosition(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) // driving-first dark UI
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        ui = HomeUi(this)
        mapView = findViewById(R.id.mapView)
        tvStatus = ui.tvStatus; tvHint = ui.tvHint; tvTranscript = ui.tvTranscript
        voiceOverlay = ui.voiceOverlay; btnVoice = ui.btnVoice
        maps = MapsRepository()
        driveAi = DriveAiEngine()
        traffic = TrafficProvider()
        tripSession = TripSession()
        navigationController = NavigationController(
            maps = maps,
            session = tripSession,
            onSpeak = ::say,
            onStatus = { status, hint -> runOnUiThread { ui.status(status); if (hint.isNotBlank()) ui.hint(hint) } },
            onDrawRoute = { route -> runOnUiThread { drawRoute(route) } },
            onCameraDestination = { lat, lon ->
                runOnUiThread {
                    if (::map.isInitialized) map.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(13.0).build()
                }
            },
            onSessionChanged = { route, destination, step ->
                lastRoute = route
                routeStepIndex = step
                destinationLat = destination?.lat ?: 0.0
                destinationLon = destination?.lon ?: 0.0
                destinationName = destination?.name.orEmpty()
            },
            onNavigationStarted = { plan, _ ->
                plan?.deadline?.let { handleArrivalBy(it.hour, it.minute) }
                plan?.rerouteIfDelayMinutes?.let { evaluatePlanTraffic(it) }
                plan?.musicQuery?.let { playFromSearch(it) }
                plan?.callName?.let { callContact(it) }
            }
        )
        tts = TextToSpeech(this, this)
        setupMap(savedInstanceState)
        setupActions()
        registerVoiceReceiver()
        refreshPermissionState()
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            map.uiSettings.isLogoEnabled = false
            map.setStyle(Style.Builder().fromJson(MapStyle.JSON)) {
                if (hasFix) map.cameraPosition = CameraPosition.Builder().target(LatLng(lastLat, lastLon)).zoom(14.0).build()
            }
        }
    }

    // ---- actions: every visible control is wired to a real command -----------------------------

    private fun synthetic(vararg c: VoiceCommand) = ParsedUtterance("", true, c.toList())

    private fun setupActions() {
        ui.btnVoice.setOnClickListener { startVoice() }
        ui.btnVoiceTrip.setOnClickListener { startVoice() }
        ui.btnCancelVoice.setOnClickListener {
            ui.setVoiceState(VoiceState.IDLE)
            startService(Intent(this, VoiceForegroundService::class.java).setAction(VoiceForegroundService.ACTION_DISARM))
        }
        ui.chipFuel.setOnClickListener { handleUtterance(synthetic(VoiceCommand.Nearby(PlaceKind.FUEL))) }
        ui.chipCoffee.setOnClickListener { handleUtterance(synthetic(VoiceCommand.Nearby(PlaceKind.COFFEE))) }
        ui.chipFood.setOnClickListener { handleUtterance(synthetic(VoiceCommand.Nearby(PlaceKind.FOOD))) }
        ui.chipParking.setOnClickListener { handleUtterance(synthetic(VoiceCommand.Nearby(PlaceKind.PARKING))) }
        ui.chipCharging.setOnClickListener { handleUtterance(synthetic(VoiceCommand.Nearby(PlaceKind.CHARGING))) }
        ui.chipMusic.setOnClickListener { handleUtterance(synthetic(VoiceCommand.Play)) }
        ui.btnAddStop.setOnClickListener { showAddStopSheet() }
        ui.btnCancelTrip.setOnClickListener { handleUtterance(synthetic(VoiceCommand.CancelRoute)) }
        ui.btnNewTrip.setOnClickListener { ui.showScreen(Screen.HOME); startVoice() }
        ui.btnCloseArrival.setOnClickListener { ui.showScreen(Screen.HOME) }
        ui.permButton.setOnClickListener {
            if (permBlocked) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            else requestCorePermissions()
        }
    }

    private fun showAddStopSheet() {
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            setBackgroundColor(ContextCompat.getColor(context, R.color.dv_surface))
        }
        fun row(text: String, bold: Boolean = false, onClick: (() -> Unit)? = null) = TextView(this).apply {
            this.text = text
            textSize = if (bold) 20f else 18f
            setTextColor(ContextCompat.getColor(context, R.color.dv_text))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            if (onClick != null) setOnClickListener { dialog.dismiss(); onClick() }
        }
        box.addView(row(getString(R.string.add_stop_title), bold = true))
        for (k in PlaceKind.values()) {
            box.addView(row(k.label) { handleUtterance(synthetic(VoiceCommand.AddStop(k))) })
        }
        dialog.setContentView(box)
        dialog.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- permissions: asked only when needed, with an explanation card ------------------------

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestCorePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.filter { !hasPerm(it) }.toTypedArray(), 20)
    }

    private fun refreshPermissionState() {
        val loc = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
        val mic = hasPerm(Manifest.permission.RECORD_AUDIO)
        if (loc && !locationStarted) { locationStarted = true; getLocation(); startLocationUpdates() }
        if (mic && !voiceStarted) { voiceStarted = true; startVoiceService(VoiceForegroundService.ACTION_START) }
        ui.showPermissionCard(!(loc && mic), permBlocked)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20) {
            permBlocked = permissions.indices.any {
                grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[it]) &&
                    permissions[it] != Manifest.permission.POST_NOTIFICATIONS
            }
            refreshPermissionState()
        }
    }

    private fun needFix(): Boolean {
        if (hasFix) return false
        say(if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) "لسه بدور على موقعك، ثواني." else "محتاج إذن الموقع الأول.")
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) ui.showPermissionCard(true, permBlocked)
        return true
    }

    // ---- voice -> parser -> planner -> effects ---------------------------------------------------

    private fun startVoice() {
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) { ui.showPermissionCard(true, permBlocked); requestCorePermissions(); return }
        ui.setVoiceState(VoiceState.LISTENING, getString(R.string.voice_hint))
        scheduleIdle(10_000)
        startVoiceService(VoiceForegroundService.ACTION_ARM)
    }

    private fun startVoiceService(action: String) {
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, VoiceForegroundService::class.java).setAction(action)) }
    }

    private fun scheduleIdle(ms: Long) { uiHandler.removeCallbacks(idleRunnable); uiHandler.postDelayed(idleRunnable, ms) }

    private fun registerVoiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(VoiceForegroundService.ACTION_COMMAND)
            addAction(VoiceForegroundService.ACTION_WAKE)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(voiceReceiver, filter)
    }

    override fun onWakeWord() {
        runOnUiThread { ui.setVoiceState(VoiceState.LISTENING, getString(R.string.voice_hint)); scheduleIdle(10_000) }
    }

    /** The recognizer cycles continuously for the wake word; the overlay is driven by wake/arm only. */
    override fun onListening(listening: Boolean) {}

    override fun onPartial(text: String) {
        runOnUiThread { if (ui.voiceState == VoiceState.LISTENING) ui.tvTranscript.text = text }
    }

    override fun onResult(text: String) {
        runOnUiThread { ui.setVoiceState(VoiceState.PROCESSING, text); scheduleIdle(6_000) }
        handleUtterance(CommandParser.parse(text))
    }

    override fun onError(message: String) {
        runOnUiThread { ui.setVoiceState(VoiceState.ERROR, ""); scheduleIdle(2_500) }
        say("حصلت مشكلة بسيطة، حاول تاني.")
    }

    private fun handleUtterance(u: ParsedUtterance) {
        runOnUiThread {
            val previous = tripState
            val res = TripPlanner.apply(previous, u)
            val needsLocation = res.effects.any { it is Effect.StartNavigation || it is Effect.SearchNearby || it is Effect.SearchPlace || it is Effect.RebuildRoute }
            if (needsLocation && needFix()) { tripState = previous; return@runOnUiThread }
            tripState = res.state
            val starting = res.effects.any { it is Effect.StartNavigation }
            res.effects.forEach { runEffect(it, starting) }
        }
    }

    private fun planFrom(s: TripState) = DriveAiCore.DrivingPlan(
        destinationQuery = s.destination,
        deadline = s.deadline?.let { DriveAiCore.Deadline(it.hour, it.minute) },
        rerouteIfDelayMinutes = s.trafficRule?.rerouteIfDelayMinutes,
        avoidTraffic = s.trafficRule != null,
        stops = s.stops.map { r ->
            if (r.kind != null) DriveAiCore.PlannedStop(DriveAiCore.StopType.valueOf(r.kind.name))
            else DriveAiCore.PlannedStop(DriveAiCore.StopType.FOOD, query = r.query.orEmpty(), label = r.query.orEmpty()) // named place
        },
        hasNavigationIntent = true
    )

    private fun currentPlace(): MapsRepository.Place? =
        if (destinationName.isNotBlank() && lastRoute != null) MapsRepository.Place(destinationName, destinationLat, destinationLon, destinationName) else null

    private fun runEffect(e: Effect, starting: Boolean) {
        when (e) {
            is Effect.Speak -> say(e.text)
            is Effect.StartNavigation -> {
                executePlan(planFrom(e.state))
                // If routing never produces a trip (network/geocoding failure) drop the pending intent.
                uiHandler.postDelayed({ if (!NavigationState.get().active) tripState = TripState() }, 25_000L)
            }
            is Effect.RebuildRoute -> {
                val dest = currentPlace()
                val route = lastRoute
                if (dest == null || route == null) say("مفيش رحلة شغالة.")
                else resolvePlanStops(planFrom(e.state).copy(destinationQuery = null), dest, route, 0, mutableListOf())
            }
            is Effect.SwitchToAlternative -> rerouteWithAlternative()
            is Effect.Recalculate -> reroute()
            is Effect.CancelTrip -> { cancelTrip(); say("تمام، ألغيت الرحلة.") }
            is Effect.SearchNearby -> searchKind(e.kind)
            is Effect.SearchPlace -> searchPlace(e.query)
            is Effect.AnswerEta -> answerEta()
            is Effect.AnswerTraffic -> trafficStatus()
            is Effect.AnswerWhere -> answerWhere()
            is Effect.RepeatLast -> if (lastSpoken.isNotBlank()) say(lastSpoken) else say("مفيش حاجة أكررها.")
            is Effect.Media -> runMedia(e.command)
            is Effect.Call -> callContact(e.name)
            is Effect.EvaluateDeadline -> if (!starting) tripState.deadline?.let { handleArrivalBy(it.hour, it.minute) }
        }
    }

    private fun searchKind(kind: PlaceKind) {
        val (q, label) = when (kind) {
            PlaceKind.FUEL -> "gas station" to "البنزين"
            PlaceKind.COFFEE -> "coffee cafe" to "القهوة"
            PlaceKind.FOOD -> "restaurant" to "الأكل"
            PlaceKind.PARKING -> "parking" to "موقف السيارات"
            PlaceKind.CHARGING -> "EV charging station" to "محطة الشحن"
        }
        if (destinationName.isNotBlank()) searchSmartStop(q, label) else searchAndSpeak(q, label)
    }

    private fun runMedia(c: VoiceCommand) {
        when (c) {
            is VoiceCommand.Play -> { sendMedia(KeyEvent.KEYCODE_MEDIA_PLAY); say("تمام.") }
            is VoiceCommand.Pause -> { sendMedia(KeyEvent.KEYCODE_MEDIA_PAUSE); say("تمام.") }
            is VoiceCommand.Next -> { sendMedia(KeyEvent.KEYCODE_MEDIA_NEXT); say("التالي.") }
            is VoiceCommand.Previous -> { sendMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS); say("السابق.") }
            is VoiceCommand.VolumeUp -> { volume(true); say("تمام.") }
            is VoiceCommand.VolumeDown -> { volume(false); say("تمام.") }
            is VoiceCommand.Mute -> {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                say("تمام.")
            }
            is VoiceCommand.MusicSearch -> playFromSearch(c.query)
            else -> Unit
        }
    }

    private fun answerEta() {
        val p = lastProgress
        if (p == null) { lastRoute?.let { speakRouteSummary(it) } ?: say("مفيش رحلة شغالة."); return }
        say("فاضل ${TripFormat.duration(p.remainingSeconds)}، وهتوصل ${TripFormat.clock(p.arrivalEpochMs)}.")
    }

    private fun answerWhere() {
        maps.reverse(lastLat, lastLon) { name ->
            say(if (name.isNullOrBlank()) "موقعك ظاهر على الخريطة." else "أنت عند $name.")
        }
    }

    private fun abortPlan(message: String) { tripState = TripState(); say(message) }

    private fun cancelTrip() {
        navigationController.clear()
        if (::map.isInitialized) map.clear()
        lastRoute = null; routeStepIndex = 0; destinationName = ""; lastProgress = null
        tripState = TripState()
        ui.showScreen(Screen.HOME)
        ui.hint(getString(R.string.greeting_sub))
    }

    // ---- live trip UI -----------------------------------------------------------------------------

    private fun renderNavigation(s: NavigationState.Snapshot) {
        if (activityDestroyed) return
        val route = s.route
        if (!s.active || route == null) {
            wasActive = false
            if (ui.screen == Screen.TRIP) ui.showScreen(Screen.HOME)
            return
        }
        if (!wasActive) {
            wasActive = true
            tripStartMs = System.currentTimeMillis()
            tripStartDistanceM = route.distanceM
            arrivalDetector.reset()
        }
        val step = route.steps.getOrNull(s.currentStepIndex)
        val dNext = step?.let { distanceMeters(s.currentLat, s.currentLon, it.lat, it.lon) } ?: 0.0
        val p = TripProgressCalc.compute(route.steps, s.currentStepIndex, route.distanceM, route.durationS, dNext, System.currentTimeMillis())
        lastProgress = p
        ui.renderTrip(p, s.destinationName, tripState.stops.map { it.label })
        if (ui.screen != Screen.TRIP) ui.showScreen(Screen.TRIP)
    }

    private fun updateNavigationPosition(lat: Double, lon: Double, accuracy: Float = 0f) {
        lastLat = lat; lastLon = lon; hasFix = true
        navigationController.updateLocation(lat, lon)
        val s = NavigationState.get()
        if (s.active && System.currentTimeMillis() - tripStartMs > 15_000L &&
            arrivalDetector.onFix(distanceMeters(lat, lon, s.destinationLat, s.destinationLon), accuracy)
        ) finishArrival(s.destinationName)
    }

    private fun finishArrival(name: String) {
        val durationS = (System.currentTimeMillis() - tripStartMs) / 1000.0
        val distance = tripStartDistanceM
        cancelTrip() // clears session + map + state
        ui.renderArrival(name, TripFormat.duration(durationS), TripFormat.distance(distance))
        ui.showScreen(Screen.ARRIVED)
        say("وصلنا.")
    }

    private fun getLocation() {
        val client = LocationServices.getFusedLocationProviderClient(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
            if (loc != null) {
                lastLat = loc.latitude; lastLon = loc.longitude; hasFix = true
                navigationController.updateLocation(loc.latitude, loc.longitude)
                if (::map.isInitialized) map.cameraPosition = CameraPosition.Builder().target(LatLng(lastLat, lastLon)).zoom(14.0).build()
                ui.status(getString(R.string.status_ready))
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        locationClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun executePlan(plan: DriveAiCore.DrivingPlan) {
        activePlan = plan
        val destination = plan.destinationQuery
        if (destination == null) {
            if (destinationName.isNotBlank() && lastRoute != null) {
                val currentDestination = MapsRepository.Place(destinationName, destinationLat, destinationLon, destinationName)
                resolvePlanStops(plan, currentDestination, lastRoute!!, 0, mutableListOf())
            } else {
                plan.musicQuery?.let { playFromSearch(it) }
                plan.callName?.let { callContact(it) }
                if (plan.stops.isNotEmpty() || plan.deadline != null || plan.rerouteIfDelayMinutes != null) {
                    say("قولي رايح فين الأول.")
                }
            }
            return
        }
        say("تمام. فهمت الخطة: وجهتك $destination${if (plan.stops.isNotEmpty()) ", و${plan.stops.size} محطات في الطريق" else ""}")
        maps.search(destination, lastLat, lastLon) { places ->
            val place = places.firstOrNull()
            if (place == null) { abortPlan("مش لاقي الوجهة $destination"); return@search }
            maps.route(lastLat, lastLon, place.lat, place.lon) { baseRoute ->
                if (baseRoute == null) { abortPlan("تعذر حساب الطريق الأساسي إلى $destination"); return@route }
                resolvePlanStops(plan, place, baseRoute, 0, mutableListOf())
            }
        }
    }

    private fun resolvePlanStops(
        plan: DriveAiCore.DrivingPlan,
        destination: MapsRepository.Place,
        baseRoute: MapsRepository.Route,
        index: Int,
        resolved: MutableList<MapsRepository.Place>
    ) {
        if (index >= plan.stops.size) {
            startPlannedRoute(plan, destination, resolved)
            return
        }
        val stop = plan.stops[index]
        val fraction = ((index + 1).toDouble() / (plan.stops.size + 1)).coerceIn(0.15, 0.85)
        val routePoint = baseRoute.points[(baseRoute.points.size * fraction).toInt().coerceIn(0, baseRoute.points.lastIndex)]
        maps.searchNearby(stop.query, routePoint.first, routePoint.second) { places ->
            val candidate = places.minByOrNull { distanceMeters(routePoint.first, routePoint.second, it.lat, it.lon) }
            if (candidate == null) {
                say("مش لاقي ${stop.label} مناسب على مسار الرحلة، هكمل من غيره")
                resolvePlanStops(plan, destination, baseRoute, index + 1, resolved)
            } else {
                resolved += candidate
                resolvePlanStops(plan, destination, baseRoute, index + 1, resolved)
            }
        }
    }

    private fun startPlannedRoute(plan: DriveAiCore.DrivingPlan, destination: MapsRepository.Place, stops: List<MapsRepository.Place>) {
        navigationController.startPlannedNavigation(plan, destination, stops)
    }

    private fun evaluatePlanTraffic(thresholdMinutes: Int) {
        val route = lastRoute ?: return
        traffic.fetch(lastLat, lastLon, destinationLat, destinationLon) { snapshot ->
            if (snapshot == null) {
                say("مش متوصل بمصدر زحمة مباشر حاليًا، فمش هادّعي إن الطريق فيه زحمة")
                return@fetch
            }
            val decision = driveAi.applyTraffic(route, snapshot)
            if (decision.eta.trafficDelayMinutes >= thresholdMinutes) {
                say("الزحمة زادت حوالي ${decision.eta.trafficDelayMinutes} دقيقة، هبحث عن طريق بديل")
                rerouteWithAlternative()
            } else {
                say("التأخير المروري حوالي ${decision.eta.trafficDelayMinutes} دقيقة، وده أقل من الحد اللي طلبته")
            }
        }
    }

    private fun searchAndSpeak(query: String, label: String) {
        tvStatus.text = "أبحث عن $label قريب…"
        maps.search(query, lastLat, lastLon) { places ->
            val place = places.firstOrNull()
            if (place == null) { say("مش لاقي $label قريب منك"); return@search }
            runOnUiThread { tvStatus.text = place.name; tvHint.text = place.display }
            say("لقيت ${place.name}. لو عايز أروح لها قل فارس وديني لها")
        }
    }

    private fun trafficStatus() {
        if (destinationName.isBlank() || lastRoute == null) { say("حدد وجهتك الأول عشان أقدر أقيّم الطريق"); return }
        say("هفحص حالة الطريق")
        traffic.fetch(lastLat, lastLon, destinationLat, destinationLon) { snapshot ->
            if (snapshot == null) {
                say("مفيش مزود زحمة مباشر متوصل حاليًا، والوقت الظاهر مبني على بيانات الطريق الأساسية")
                return@fetch
            }
            val decision = driveAi.applyTraffic(lastRoute!!, snapshot)
            runOnUiThread { tvStatus.text = "${snapshot.source}: +${decision.eta.trafficDelayMinutes} دقيقة" }
            if (decision.shouldReroute) say("الزحمة ممكن تزود حوالي ${decision.eta.trafficDelayMinutes} دقيقة. لو تحب أبحث عن طريق بديل قل غير الطريق")
            else say("الطريق حالته كويسة، والتأخير المتوقع حوالي ${decision.eta.trafficDelayMinutes} دقيقة")
        }
    }

    private fun searchSmartStop(query: String, label: String) {
        if (destinationName.isBlank() || lastRoute == null) {
            searchAndSpeak(query, label)
            return
        }
        val route = lastRoute!!
        val midpoint = route.points[(route.points.size / 2).coerceIn(0, route.points.lastIndex)]
        maps.searchNearby(query, midpoint.first, midpoint.second) { places ->
            val best = places.minByOrNull { distanceMeters(midpoint.first, midpoint.second, it.lat, it.lon) }
            if (best == null) { say("مش لاقي $label مناسب على الطريق"); return@searchNearby }
            say("لقيت ${best.name} تقريبًا في منتصف الطريق. لو عايز أضيفه كمحطة قل ضيف محطة")
        }
    }

    private fun rerouteWithAlternative() {
        if (destinationName.isBlank()) { say("مفيش وجهة حالية أغير لها الطريق"); return }
        say("هبحث عن طريق بديل")
        navigationController.reroute(alternative = true)
    }

    private fun handleArrivalBy(hour: Int, minute: Int) {
        val route = lastRoute
        if (route == null || destinationName.isBlank()) { say("حدد وجهتك الأول وأنا أحسب لك وقت الوصول"); return }
        val now = java.time.ZonedDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val eta = now.plusSeconds(route.durationS.toLong())
        val diff = java.time.Duration.between(eta, target).toMinutes()
        if (diff >= 0) say("أيوه، حسب الوقت الحالي الطريق يوصلك قبل الساعة $hour:${minute.toString().padStart(2, '0')} بحوالي $diff دقيقة")
        else say("المسار الحالي متأخر عن الساعة $hour:${minute.toString().padStart(2, '0')} بحوالي ${-diff} دقيقة")
    }

    private fun reroute() {
        if (destinationName.isBlank()) return
        navigationController.reroute(alternative = false)
    }

    private fun callContact(name: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 21)
            say("محتاج إذن جهات الاتصال عشان أتصل بالاسم")
            return
        }
        Thread {
            var number: String? = null
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use { c ->
                if (c.moveToFirst()) number = c.getString(0)
            }
            runOnUiThread {
                if (number.isNullOrBlank()) { say("مش لاقي $name في جهات الاتصال"); return@runOnUiThread }
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${number!!.replace(" ", "")}"))
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) startActivity(intent)
                else { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 22); say("اسمح لي بالمكالمات عشان أتصل") }
            }
        }.start()
    }

    private fun searchPlace(query: String) {
        tvStatus.text = "أبحث عن $query…"
        maps.search(query, lastLat, lastLon) { places ->
            val place = places.firstOrNull()
            if (place == null) { say("مش لاقي نتائج قريبة"); return@search }
            runOnUiThread { tvStatus.text = place.name; tvHint.text = place.display }
            say("أقرب نتيجة: ${place.name}. لو عايز أروح لها قل وديني")
        }
    }

    private fun drawRoute(route: MapsRepository.Route) {
        if (!::map.isInitialized) return
        map.clear()
        val points = route.points.map { LatLng(it.first, it.second) }
        map.addPolyline(PolylineOptions().addAll(points).color(Color.rgb(90, 167, 255)).width(7f))
    }

    private fun playFromSearch(query: String) {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            say("هشغل $query")
        } catch (_: Exception) {
            say("مش قادر أرسل بحث موسيقى للمشغل الحالي")
        }
    }

    private fun sendMedia(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun volume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    private fun speakRouteSummary(route: MapsRepository.Route) {
        if (destinationName.isNotBlank()) {
            traffic.fetch(lastLat, lastLon, destinationLat, destinationLon) { snapshot ->
                if (snapshot != null) {
                    val decision = driveAi.applyTraffic(route, snapshot)
                    say("متبقي ${formatKm(route.distanceM)}، والوقت المتوقع ${decision.eta.minutes} دقيقة، والتأخير المروري حوالي ${decision.eta.trafficDelayMinutes} دقيقة")
                } else say("متبقي ${formatKm(route.distanceM)}، والوقت المتوقع ${formatMinutes(route.durationS)}")
            }
        } else say("متبقي ${formatKm(route.distanceM)}، والوقت المتوقع ${formatMinutes(route.durationS)}")
    }

    private fun formatKm(m: Double): String = if (m < 1000) "${m.toInt()} متر" else String.format(Locale.US, "%.1f كم", m / 1000.0)

    private fun formatMinutes(s: Double): String = if (s < 60) "أقل من دقيقة" else "${kotlin.math.round(s / 60).toInt()} دقيقة"

    // ---- speech output --------------------------------------------------------------------------------

    /** Queued (not flushed) so multi-part answers are not cut off; short, driving-safe phrases only. */
    private fun say(text: String) {
        if (activityDestroyed || !::tts.isInitialized) return
        runOnUiThread {
            if (activityDestroyed || !::tts.isInitialized) return@runOnUiThread
            lastSpoken = text
            pendingSpeech++
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "drivevoice-${System.nanoTime()}")
        }
    }

    private fun speechDone() {
        runOnUiThread {
            pendingSpeech = (pendingSpeech - 1).coerceAtLeast(0)
            if (pendingSpeech == 0 && ui.voiceState == VoiceState.SPEAKING) ui.setVoiceState(VoiceState.IDLE)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { ui.hint("الصوت غير متاح على الجهاز"); return }
        val r = tts.setLanguage(Locale("ar"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) ui.hint("نزّل صوت عربي من إعدادات النطق عشان أتكلم")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { if (ui.voiceState != VoiceState.ERROR && ui.voiceState != VoiceState.LISTENING) ui.setVoiceState(VoiceState.SPEAKING) }
            }
            override fun onDone(utteranceId: String?) = speechDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = speechDone()
        })
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    override fun onDestroy() {
        activityDestroyed = true
        uiHandler.removeCallbacksAndMessages(null)
        if (::ui.isInitialized) ui.release()
        locationClient.removeLocationUpdates(locationCallback)
        runCatching { unregisterReceiver(voiceReceiver) }
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onStart() { super.onStart(); mapView.onStart(); NavigationState.addListener(navListener) }
    override fun onStop() { NavigationState.removeListener(navListener); mapView.onStop(); super.onStop() }
    override fun onResume() { super.onResume(); mapView.onResume(); refreshPermissionState() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }
}
