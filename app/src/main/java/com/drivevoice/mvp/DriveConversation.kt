package com.drivevoice.mvp

/**
 * Wake-word state machine. Two-stage ("فارس" ... "خدني المطار") and one-shot ("فارس خدني المطار").
 * Pure Kotlin; the clock is injected so tests are deterministic.
 */
class DriveConversation(
    private val clock: () -> Long,
    private val windowMs: Long = 8_000L,
    private val dedupeMs: Long = 2_500L,
    private val wake: String = "فارس"
) {
    sealed class Result {
        data class WakeAck(val reply: String = "معاك.") : Result()
        data class Command(val utterance: ParsedUtterance) : Result()
        data class NotUnderstood(val reply: String = "مفهمتش، قولها تاني.") : Result()
        data class Ignored(val reason: String) : Result()
    }

    private var windowUntil = 0L
    private var lastKey = ""
    private var lastAt = 0L

    val isListeningForCommand get() = clock() < windowUntil

    fun openWindow() { windowUntil = clock() + windowMs }
    fun closeWindow() { windowUntil = 0L }

    fun onSpeech(raw: String): Result {
        val now = clock()
        val key = ArabicText.normalize(raw)
        if (key.isBlank()) return Result.Ignored("empty")
        if (key == lastKey && now - lastAt < dedupeMs) return Result.Ignored("duplicate")
        val inWindow = now < windowUntil
        val u = CommandParser.parse(raw, wake)
        if (!u.hadWakeWord && !inWindow) return Result.Ignored("no wake word")
        lastKey = key; lastAt = now
        if (u.isWakeOnly) { windowUntil = now + windowMs; return Result.WakeAck() }
        if (u.isUnknown) { windowUntil = now + windowMs; return Result.NotUnderstood() }
        windowUntil = 0L // one command per wake; avoids accidental re-execution of chatter
        return Result.Command(u)
    }
}
