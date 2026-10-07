package com.drivevoice.mvp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class VoiceForegroundService : Service(), VoiceCommandEngine.Listener {
    companion object {
        const val ACTION_START = "com.drivevoice.mvp.START_VOICE"
        const val ACTION_STOP = "com.drivevoice.mvp.STOP_VOICE"
        const val ACTION_ARM = "com.drivevoice.mvp.ARM_VOICE"
        const val ACTION_COMMAND = "com.drivevoice.mvp.VOICE_COMMAND"
        const val ACTION_WAKE = "com.drivevoice.mvp.VOICE_WAKE"
        const val EXTRA_TEXT = "text"
        const val ACTION_DISARM = "com.drivevoice.mvp.DISARM_VOICE"
        const val WAKE_MARKER = "wake"
        const val ERROR_PREFIX = "error:"
        private const val CHANNEL_ID = "drivevoice_voice"
        private const val NOTIFICATION_ID = 7001
    }

    private lateinit var engine: VoiceCommandEngine

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = VoiceCommandEngine(this).also { it.listener = this }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISARM) { engine.disarm(); return START_STICKY }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (intent?.action == ACTION_ARM) engine.arm()
        if (!engine.isRunning) engine.start(true)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drivevoice)
            .setContentTitle("DriveVoice")
            .setContentText("فارس جاهز يسمعك")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "التحكم الصوتي", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onListening(listening: Boolean) {
        sendBroadcast(Intent(ACTION_WAKE).putExtra(EXTRA_TEXT, if (listening) "listening" else "idle"))
    }
    override fun onPartial(text: String) { sendBroadcast(Intent(ACTION_WAKE).putExtra(EXTRA_TEXT, text)) }
    override fun onWakeWord() { sendBroadcast(Intent(ACTION_WAKE).putExtra(EXTRA_TEXT, WAKE_MARKER)) }
    override fun onResult(text: String) { sendBroadcast(Intent(ACTION_COMMAND).putExtra(EXTRA_TEXT, text)) }
    override fun onError(message: String) { sendBroadcast(Intent(ACTION_WAKE).putExtra(EXTRA_TEXT, ERROR_PREFIX + message)) }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.destroy()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
