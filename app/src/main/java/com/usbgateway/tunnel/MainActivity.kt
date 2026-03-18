package com.usbgateway.tunnel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity: Single-screen UI for the USB-TCP Gateway.
 *
 * - IP/Port input fields (persisted in SharedPreferences)
 * - Start/Stop toggle button
 * - USB and TCP status indicator dots
 * - Terminal-style scrolling log view
 * - Handles USB_DEVICE_ATTACHED auto-launch
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "gateway_prefs"
        private const val PREF_HOST = "server_host"
        private const val PREF_PORT = "server_port"
        private const val ACTION_USB_PERMISSION = "com.usbgateway.tunnel.USB_PERMISSION"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnToggle: MaterialButton
    private lateinit var dotUsb: android.view.View
    private lateinit var dotTcp: android.view.View
    private lateinit var tvUsbStatus: TextView
    private lateinit var tvTcpStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    private var tunnelRunning = false
    private var pendingDevice: UsbDevice? = null

    // =========================================================================
    // USB permission BroadcastReceiver
    // =========================================================================

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                @Suppress("DEPRECATION")
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (granted && device != null) {
                    startTunnel(device)
                } else {
                    Toast.makeText(context, "USB-разрешение отклонено", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Bind views
        etIp = findViewById(R.id.etIpAddress)
        etPort = findViewById(R.id.etPort)
        btnToggle = findViewById(R.id.btnToggle)
        dotUsb = findViewById(R.id.dotUsb)
        dotTcp = findViewById(R.id.dotTcp)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        tvTcpStatus = findViewById(R.id.tvTcpStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)

        // Restore saved server address
        etIp.setText(prefs.getString(PREF_HOST, ""))
        etPort.setText(prefs.getString(PREF_PORT, ""))

        // Toggle button
        btnToggle.setOnClickListener {
            if (tunnelRunning) {
                stopTunnel()
            } else {
                savePrefs()
                requestUsbAndStart()
            }
        }

        // Register USB permission receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }

        // Observe TunnelEngine state if service is already running
        observeEngine()

        // Handle USB auto-attach intent
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        super.onDestroy()
    }

    // =========================================================================
    // USB handling
    // =========================================================================

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            if (device != null && !tunnelRunning) {
                pendingDevice = device
                // Auto-start if we have saved server settings
                val host = prefs.getString(PREF_HOST, "") ?: ""
                val port = prefs.getString(PREF_PORT, "") ?: ""
                if (host.isNotBlank() && port.isNotBlank()) {
                    etIp.setText(host)
                    etPort.setText(port)
                    requestUsbPermission(device)
                }
            }
        }
    }

    /**
     * Finds the first available USB device and requests permission.
     */
    private fun requestUsbAndStart() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val deviceList = manager.deviceList

        if (deviceList.isEmpty()) {
            Toast.makeText(this, "USB-устройство не найдено", Toast.LENGTH_SHORT).show()
            return
        }

        // Use the first available device (the scanner)
        val device = deviceList.values.first()
        requestUsbPermission(device)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val manager = getSystemService(USB_SERVICE) as UsbManager

        if (manager.hasPermission(device)) {
            startTunnel(device)
        } else {
            val permIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            manager.requestPermission(device, permIntent)
        }
    }

    // =========================================================================
    // Tunnel start / stop
    // =========================================================================

    private fun startTunnel(device: UsbDevice) {
        val host = etIp.text.toString().trim()
        val portStr = etPort.text.toString().trim()

        if (host.isBlank() || portStr.isBlank()) {
            Toast.makeText(this, "Укажите IP и порт сервера", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "Некорректный порт", Toast.LENGTH_SHORT).show()
            return
        }

        savePrefs()

        // Start foreground service
        TunnelService.start(this, device, host, port)
        tunnelRunning = true
        btnToggle.text = getString(R.string.btn_stop)

        // Start observing engine state
        observeEngine()
    }

    private fun stopTunnel() {
        TunnelService.stop(this)
        tunnelRunning = false
        btnToggle.text = getString(R.string.btn_start)

        // Reset status indicators
        setUsbStatus(TunnelEngine.UsbStatus.DISCONNECTED)
        setTcpStatus(TunnelEngine.TcpStatus.DISCONNECTED)
    }

    // =========================================================================
    // State observation
    // =========================================================================

    private fun observeEngine() {
        val engine = TunnelService.engine ?: return

        // Observe tunnel state (USB + TCP status)
        lifecycleScope.launch {
            engine.state.collectLatest { state ->
                setUsbStatus(state.usbStatus)
                setTcpStatus(state.tcpStatus)

                if (state.running && !tunnelRunning) {
                    tunnelRunning = true
                    btnToggle.text = getString(R.string.btn_stop)
                }
            }
        }

        // Observe log lines
        lifecycleScope.launch {
            engine.logLines.collectLatest { lines ->
                tvLog.text = lines.joinToString("\n")
                // Auto-scroll to bottom
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private fun setUsbStatus(status: TunnelEngine.UsbStatus) {
        when (status) {
            TunnelEngine.UsbStatus.CONNECTED -> {
                dotUsb.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green)
                tvUsbStatus.text = "USB: Подключено"
            }
            TunnelEngine.UsbStatus.DISCONNECTED -> {
                dotUsb.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
                tvUsbStatus.text = "USB: Не найдено"
            }
        }
    }

    private fun setTcpStatus(status: TunnelEngine.TcpStatus) {
        when (status) {
            TunnelEngine.TcpStatus.CONNECTED -> {
                dotTcp.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green)
                tvTcpStatus.text = "TCP: На связи"
            }
            TunnelEngine.TcpStatus.SEARCHING -> {
                dotTcp.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_yellow)
                tvTcpStatus.text = "TCP: Поиск сети…"
            }
            TunnelEngine.TcpStatus.DISCONNECTED -> {
                dotTcp.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
                tvTcpStatus.text = "TCP: Отключен"
            }
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString(PREF_HOST, etIp.text.toString().trim())
            .putString(PREF_PORT, etPort.text.toString().trim())
            .apply()
    }
}
