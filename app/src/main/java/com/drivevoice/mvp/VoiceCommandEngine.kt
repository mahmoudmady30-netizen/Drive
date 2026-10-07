package com.drivevoice.mvp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceCommandEngine(private val context: Context) {
    interface Listener {
        fun onListening(listening: Boolean)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
        fun onWakeWord() {}
    }

    var listener: Listener? = null
    private var recognizer: SpeechRecognizer? = null
    private var keepListening = false
    private var restarting = false
    private val conversation = DriveConversation({ System.currentTimeMillis() })
    var isRunning = false
        private set

    /** Mic button pressed: accept the next utterance as a command without needing the wake word. */
    fun arm() { conversation.openWindow() }
    fun disarm() { conversation.closeWindow() }

    fun start(continuous: Boolean = false) {
        keepListening = continuous
        isRunning = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener?.onError("خدمة التعرف على الصوت غير متاحة على الجهاز")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listener?.onListening(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listener?.onListening(false) }
                    override fun onError(error: Int) {
                        listener?.onListening(false)
                        if (keepListening && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) scheduleRestart()
                        else if (!keepListening) listener?.onError("لم أسمع الأمر بوضوح")
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                        if (text.isNotBlank()) {
                            if (keepListening) {
                                // Wake word / command window / duplicate suppression live in DriveConversation (unit-tested).
                                when (val r = conversation.onSpeech(text)) {
                                    is DriveConversation.Result.WakeAck -> listener?.onWakeWord()
                                    is DriveConversation.Result.Command -> listener?.onResult(text)
                                    is DriveConversation.Result.NotUnderstood -> listener?.onResult(text)
                                    is DriveConversation.Result.Ignored -> Unit
                                }
                            } else listener?.onResult(text)
                        }
                        if (keepListening) scheduleRestart()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) listener?.onPartial(text)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-AE")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-AE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "قل فارس ثم أمرك")
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { if (keepListening) scheduleRestart() }
    }

    private fun scheduleRestart() {
        if (restarting) return
        restarting = true
        android.os.Handler(context.mainLooper).postDelayed({
            restarting = false
            if (keepListening) start(true)
        }, 700)
    }

    fun stop() {
        isRunning = false
        keepListening = false
        restarting = false
        conversation.closeWindow()
        recognizer?.stopListening()
        listener?.onListening(false)
    }

    fun destroy() {
        isRunning = false
        keepListening = false
        restarting = false
        conversation.closeWindow()
        recognizer?.destroy()
        recognizer = null
    }
}
