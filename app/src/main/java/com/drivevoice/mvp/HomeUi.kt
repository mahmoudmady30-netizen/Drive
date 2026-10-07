package com.drivevoice.mvp

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

enum class Screen { HOME, TRIP, ARRIVED }

/** Owns every view of the home/driving UI. MainActivity only calls render functions; no business logic here. */
class HomeUi(private val a: Activity) {
    val tvStatus: TextView = a.findViewById(R.id.tvStatus)
    val tvHint: TextView = a.findViewById(R.id.tvHint)
    val tvTranscript: TextView = a.findViewById(R.id.tvTranscript)
    val voiceOverlay: View = a.findViewById(R.id.voiceOverlay)
    val btnVoice: View = a.findViewById(R.id.btnVoice)
    val btnVoiceTrip: View = a.findViewById(R.id.btnVoiceTrip)
    val btnCancelVoice: View = a.findViewById(R.id.btnCancelVoice)
    val btnAddStop: View = a.findViewById(R.id.btnAddStop)
    val btnCancelTrip: View = a.findViewById(R.id.btnCancelTrip)
    val btnNewTrip: View = a.findViewById(R.id.btnNewTrip)
    val btnCloseArrival: View = a.findViewById(R.id.btnCloseArrival)
    val permButton: TextView = a.findViewById(R.id.permButton)
    val chipFuel: View = a.findViewById(R.id.chipFuel)
    val chipCoffee: View = a.findViewById(R.id.chipCoffee)
    val chipFood: View = a.findViewById(R.id.chipFood)
    val chipParking: View = a.findViewById(R.id.chipParking)
    val chipCharging: View = a.findViewById(R.id.chipCharging)
    val chipMusic: View = a.findViewById(R.id.chipMusic)

    private val home: View = a.findViewById(R.id.homeContent)
    private val trip: View = a.findViewById(R.id.tripContent)
    private val arrived: View = a.findViewById(R.id.arrivalContent)
    private val permCard: View = a.findViewById(R.id.permCard)
    private val permBody: TextView = a.findViewById(R.id.permBody)
    private val micRing: View = a.findViewById(R.id.micRing)
    private val tvMicLabel: TextView = a.findViewById(R.id.tvMicLabel)
    private val tvVoiceState: TextView = a.findViewById(R.id.tvVoiceState)
    private val wave: VoiceWaveView = a.findViewById(R.id.waveView)
    private var pulse: ValueAnimator? = null

    var screen: Screen = Screen.HOME
        private set
    var voiceState: VoiceState = VoiceState.IDLE
        private set

    init { applyInsets() }

    private fun applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(a.window, false)
        val header = a.findViewById<View>(R.id.header)
        val panel = a.findViewById<View>(R.id.bottomPanel)
        val hp = header.paddingTop
        val pb = panel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(a.findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, hp + bars.top, header.paddingRight, header.paddingBottom)
            panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, pb + bars.bottom)
            insets
        }
    }

    fun showScreen(s: Screen) {
        screen = s
        home.visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        trip.visibility = if (s == Screen.TRIP) View.VISIBLE else View.GONE
        arrived.visibility = if (s == Screen.ARRIVED) View.VISIBLE else View.GONE
    }

    fun status(text: String) { tvStatus.text = text }
    fun hint(text: String) { tvHint.text = text }

    fun showPermissionCard(visible: Boolean, permanentlyDenied: Boolean = false) {
        permCard.visibility = if (visible) View.VISIBLE else View.GONE
        permButton.text = a.getString(if (permanentlyDenied) R.string.perm_settings else R.string.perm_allow)
        permBody.text = a.getString(R.string.perm_body)
    }

    fun renderTrip(p: TripProgress, destination: String, stops: List<String>) {
        a.findViewById<TextView>(R.id.tvArrow).text = p.arrow
        a.findViewById<TextView>(R.id.tvNextDistance).text = TripFormat.distance(p.nextDistanceM)
        a.findViewById<TextView>(R.id.tvNextInstruction).text = p.instruction
        a.findViewById<TextView>(R.id.tvRoad).apply { text = p.road; visibility = if (p.road.isBlank()) View.GONE else View.VISIBLE }
        a.findViewById<TextView>(R.id.tvStatArrival).text = TripFormat.clock(p.arrivalEpochMs)
        a.findViewById<TextView>(R.id.tvStatRemaining).text = TripFormat.distance(p.remainingDistanceM)
        a.findViewById<TextView>(R.id.tvStatTime).text = TripFormat.duration(p.remainingSeconds)
        a.findViewById<TextView>(R.id.tvTripDestination).text = destination
        a.findViewById<TextView>(R.id.tvTripStops).apply {
            text = stops.joinToString("  ←  ")
            visibility = if (stops.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    fun renderArrival(destination: String, durationText: String, distanceText: String) {
        a.findViewById<TextView>(R.id.tvArrivedDestination).text = destination
        a.findViewById<TextView>(R.id.tvArrivedStats).text = "$durationText  •  $distanceText"
    }

    /** Single place where voice state turns into pixels. */
    fun setVoiceState(s: VoiceState, transcript: String? = null) {
        voiceState = s
        wave.state = s
        transcript?.let { tvTranscript.text = it }
        val overlay = s == VoiceState.LISTENING || s == VoiceState.PROCESSING || s == VoiceState.ERROR
        voiceOverlay.visibility = if (overlay) View.VISIBLE else View.GONE
        tvVoiceState.text = a.getString(
            when (s) {
                VoiceState.PROCESSING -> R.string.voice_processing
                VoiceState.SPEAKING -> R.string.voice_speaking
                VoiceState.ERROR -> R.string.voice_error
                else -> R.string.voice_listening
            }
        )
        tvMicLabel.text = a.getString(if (s == VoiceState.SPEAKING) R.string.voice_speaking else R.string.talk_to_fares)
        if (s == VoiceState.LISTENING || s == VoiceState.SPEAKING) startPulse() else stopPulse()
        if (s == VoiceState.IDLE) tvStatus.text = a.getString(R.string.status_ready)
    }

    private fun startPulse() {
        if (pulse?.isRunning == true) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                micRing.scaleX = 0.9f + 0.35f * f; micRing.scaleY = micRing.scaleX
                micRing.alpha = 0.8f * (1f - f)
            }
            start()
        }
    }

    private fun stopPulse() { pulse?.cancel(); pulse = null; micRing.alpha = 0f }

    fun release() { stopPulse() }
}
