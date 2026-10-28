package com.sdrnova.t25.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sdrnova.t25.MainActivity
import com.sdrnova.t25.R
import com.sdrnova.t25.SdrNovaApp
import com.sdrnova.t25.trunking.TrunkingTracker

/** Keeps the process in the foreground (and the CPU awake) while the radio runs with the screen off. */
class RadioService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as SdrNovaApp).engine
        if (intent?.action == ACTION_STOP) {
            val status = engine.status.value
            if (status.running || status.starting) engine.stop() else stopSelf()
            return START_NOT_STICKY
        }
        val config = ConfigStore(this).load()
        startInForeground(config)
        wakeLock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SDRNova:radio")
            .apply { acquire() }
        engine.onStopped = { main.post { stopSelf() } }
        engine.start(config)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as SdrNovaApp).engine.stop()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground(config: RadioConfig) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Radio monitoring", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SDRNova T25")
            .setContentText("${config.mode.label} on ${TrunkingTracker.mhz(config.frequencyHz)}")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIFICATION_ID = 25
        private const val ACTION_STOP = "com.sdrnova.t25.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RadioService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP))
        }
    }
}
