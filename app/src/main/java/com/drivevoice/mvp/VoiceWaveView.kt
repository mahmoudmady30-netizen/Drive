package com.drivevoice.mvp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Animated bars for the five voice states. The animator only runs while visible and non-idle. */
class VoiceWaveView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var state: VoiceState = VoiceState.IDLE
        set(value) { if (field != value) { field = value; sync(); invalidate() } }

    private val bars = 23
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    private fun colorFor(s: VoiceState): Int = ContextCompat.getColor(
        context,
        when (s) {
            VoiceState.ERROR -> R.color.dv_bad
            VoiceState.PROCESSING -> R.color.dv_muted
            VoiceState.SPEAKING -> R.color.dv_good
            else -> R.color.dv_accent
        }
    )

    private fun sync() {
        val shouldRun = state != VoiceState.IDLE && state != VoiceState.ERROR && isShown && windowVisibility == VISIBLE
        if (shouldRun && !animator.isRunning) animator.start()
        else if (!shouldRun && animator.isRunning) animator.cancel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.color = colorFor(state)
        val slot = w / bars
        val barW = slot * 0.5f
        val mid = (bars - 1) / 2f
        for (i in 0 until bars) {
            val norm = (i - mid) / mid
            val envelope = 1f - norm * norm * 0.75f
            val amp = when (state) {
                VoiceState.LISTENING -> 0.18f + 0.82f * abs(sin(2.0 * PI * (t * 2 + i * 0.19))).toFloat() * envelope
                VoiceState.PROCESSING -> {
                    val pos = t * (bars + 8) - 4
                    0.12f + 0.88f * exp(-((i - pos) * (i - pos)) / 6f)
                }
                VoiceState.SPEAKING -> 0.2f + 0.5f * (0.5f + 0.5f * sin(2.0 * PI * (t * 1.5 - i * 0.22)).toFloat()) * envelope
                VoiceState.ERROR -> 0.1f
                VoiceState.IDLE -> 0.1f
            }.coerceIn(0.06f, 1f)
            val bh = amp * h
            val cx = slot * (i + 0.5f)
            rect.set(cx - barW / 2, (h - bh) / 2, cx + barW / 2, (h + bh) / 2)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
        }
    }
}
