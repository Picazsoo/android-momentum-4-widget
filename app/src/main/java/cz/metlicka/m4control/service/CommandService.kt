package cz.metlicka.m4control.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import cz.metlicka.m4control.R
import cz.metlicka.m4control.device.DeviceController
import cz.metlicka.m4control.device.NoiseControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground (connectedDevice) while DeviceController works on behalf of a
 * widget tap; it holds no connection logic itself. Stops when the controller reports idle.
 */
class CommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DeviceController.init(this)
        // Check the permission before startForeground (API 34+ throws without it).
        if (!DeviceController.link.hasPermission()) {
            DeviceController.recomputeLink()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            DeviceController.log("!! foreground start rejected: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        dispatch(intent)
        stopJob?.cancel()
        stopJob = scope.launch {
            DeviceController.busy.first { !it }
            ServiceCompat.stopForeground(this@CommandService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun dispatch(intent: Intent?) {
        val c = DeviceController
        when (intent?.action) {
            ACTION_STOP -> c.selectStop(intent.getIntExtra(EXTRA_INDEX, 3))
            ACTION_NOISE_TOGGLE -> c.toggleNoise()
            ACTION_ADAPTIVE -> c.setNoise(NoiseControl.Target.Adaptive)
            ACTION_BASS -> c.setBass(c.state.value.shownBass != true)
            ACTION_TOUCH_LOCK -> c.toggleTouchLock()
            ACTION_FAVORITE -> {
                val id = intent.getStringExtra(EXTRA_PRESET)
                c.presets.value.all.firstOrNull { it.id == id }?.let { c.applyPreset(it) }
            }
            else -> c.refresh()
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Headphone control", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown briefly while a widget action talks to the headphones"
                },
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle("M4 Control")
            .setContentText("Talking to your headphones…")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "cz.metlicka.m4control.STOP"
        const val ACTION_NOISE_TOGGLE = "cz.metlicka.m4control.NOISE_TOGGLE"
        const val ACTION_ADAPTIVE = "cz.metlicka.m4control.ADAPTIVE"
        const val ACTION_BASS = "cz.metlicka.m4control.BASS"
        const val ACTION_TOUCH_LOCK = "cz.metlicka.m4control.TOUCH_LOCK"
        const val ACTION_FAVORITE = "cz.metlicka.m4control.FAVORITE"
        const val ACTION_REFRESH = "cz.metlicka.m4control.REFRESH"
        const val EXTRA_INDEX = "index"
        const val EXTRA_PRESET = "preset"
        private const val CHANNEL = "control"
        private const val NOTIFICATION_ID = 1

        fun intent(context: Context, action: String) = Intent(context, CommandService::class.java).setAction(action)
    }
}
