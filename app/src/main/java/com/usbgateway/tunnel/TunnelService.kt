package com.usbgateway.tunnel

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * TunnelService: Foreground service that keeps the tunnel alive
 * even when the screen is off.
 *
 * - Shows a persistent notification in the notification shade
 * - Acquires PARTIAL_WAKE_LOCK to prevent CPU sleep
 * - Delegates all data work to TunnelEngine
 */
class TunnelService : Service() {

    companion object {
        private const val TAG = "TunnelService"
        private const val NOTIF_CHANNEL_ID = "tunnel_channel"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "com.usbgateway.tunnel.START"
        private const val ACTION_STOP = "com.usbgateway.tunnel.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        // Singleton engine reference so the Activity can observe state
        var engine: TunnelEngine? = null
            private set

        /**
         * Helper to start the service with server parameters.
         */
        fun start(context: Context, device: UsbDevice, host: String, port: Int) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra("usb_device", device)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Helper to stop the service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engine = TunnelEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (port == 0) return START_NOT_STICKY

                @Suppress("DEPRECATION")
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("usb_device", UsbDevice::class.java)
                } else {
                    intent.getParcelableExtra("usb_device")
                }
                if (device == null) {
                    Log.e(TAG, "No USB device in intent")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Go foreground with notification
                startForeground(NOTIF_ID, buildNotification())

                // Acquire wake lock to keep CPU active
                acquireWakeLock()

                // Start the tunnel engine
                engine?.start(device, host, port)
            }
            ACTION_STOP -> {
                engine?.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW // no sound, just persistent icon
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // --- WakeLock ---

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "UsbTcpGateway::TunnelWakeLock"
        ).apply {
            acquire() // hold indefinitely until tunnel stops
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
