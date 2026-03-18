package com.usbgateway.tunnel

import android.hardware.usb.UsbDevice
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * TunnelEngine: Orchestrates the bidirectional USB ↔ TCP data bridge.
 *
 * Manages two independent coroutine loops:
 *   1) USB → TCP: reads from USB, writes to TCP
 *   2) TCP → USB: reads from TCP, writes to USB
 *
 * Handles TCP auto-reconnect on network loss (every 2 seconds).
 * Exposes status and log messages via StateFlow for UI observation.
 */
class TunnelEngine(private val context: Context) {

    companion object {
        private const val TAG = "TunnelEngine"
        private const val BUFFER_SIZE = 4096
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_LOG_LINES = 500
    }

    // --- Public status ---
    enum class UsbStatus { CONNECTED, DISCONNECTED }
    enum class TcpStatus { CONNECTED, SEARCHING, DISCONNECTED }

    data class TunnelState(
        val usbStatus: UsbStatus = UsbStatus.DISCONNECTED,
        val tcpStatus: TcpStatus = TcpStatus.DISCONNECTED,
        val running: Boolean = false
    )

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines

    // --- Internal ---
    private val usbBridge = UsbBridge(context)
    private val tcpBridge = TcpBridge()
    private var engineScope: CoroutineScope? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var serverHost: String = ""
    private var serverPort: Int = 0

    /**
     * Starts the tunnel: opens USB, connects to TCP, and launches
     * two bidirectional forwarding loops.
     */
    fun start(device: UsbDevice, host: String, port: Int) {
        if (_state.value.running) {
            log("Туннель уже запущен")
            return
        }

        serverHost = host
        serverPort = port

        // Open USB device
        if (!usbBridge.open(device)) {
            log("Ошибка: не удалось открыть USB-устройство")
            _state.value = _state.value.copy(usbStatus = UsbStatus.DISCONNECTED)
            return
        }

        log("USB-устройство подключено: ${device.deviceName}")
        _state.value = _state.value.copy(
            usbStatus = UsbStatus.CONNECTED,
            running = true
        )

        // Launch engine coroutines
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engineScope = scope

        // TCP connection + reconnect manager
        scope.launch { tcpConnectionLoop() }

        // USB → TCP forwarding
        scope.launch { usbToTcpLoop() }

        // TCP → USB forwarding
        scope.launch { tcpToUsbLoop() }
    }

    /**
     * Stops the tunnel: cancels all coroutines, closes USB and TCP.
     */
    fun stop() {
        log("Остановка туннеля…")
        engineScope?.cancel()
        engineScope = null

        tcpBridge.disconnect()
        usbBridge.close()

        _state.value = TunnelState() // reset to defaults
        log("Туннель остановлен")
    }

    // =========================================================================
    // TCP connection loop with auto-reconnect
    // =========================================================================

    private suspend fun tcpConnectionLoop() {
        while (currentCoroutineContext().isActive) {
            if (!tcpBridge.isConnected) {
                _state.value = _state.value.copy(tcpStatus = TcpStatus.SEARCHING)
                log("Подключение к серверу $serverHost:$serverPort…")

                try {
                    tcpBridge.connect(serverHost, serverPort)
                    _state.value = _state.value.copy(tcpStatus = TcpStatus.CONNECTED)
                    log("Соединение с сервером установлено")
                } catch (e: IOException) {
                    log("Ошибка подключения: ${e.message}")
                    _state.value = _state.value.copy(tcpStatus = TcpStatus.SEARCHING)
                }
            }

            delay(RECONNECT_DELAY_MS)
        }
    }

    // =========================================================================
    // USB → TCP forwarding loop
    // =========================================================================

    private suspend fun usbToTcpLoop() {
        val buffer = ByteArray(BUFFER_SIZE)

        while (currentCoroutineContext().isActive) {
            try {
                // Read from USB (blocking up to timeout)
                val bytesRead = usbBridge.read(buffer)

                if (bytesRead > 0 && tcpBridge.isConnected) {
                    try {
                        tcpBridge.write(buffer, bytesRead)
                    } catch (e: IOException) {
                        log("Ошибка записи в TCP: ${e.message}")
                        tcpBridge.disconnect()
                        _state.value = _state.value.copy(tcpStatus = TcpStatus.SEARCHING)
                    }
                } else if (bytesRead < 0) {
                    // USB read error — device might have been unplugged
                    log("Ошибка чтения USB")
                    _state.value = _state.value.copy(usbStatus = UsbStatus.DISCONNECTED)
                    delay(500) // brief pause before retry
                }

                // Small yield to prevent tight spin when USB has no data
                if (bytesRead <= 0) {
                    delay(1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("USB→TCP ошибка: ${e.message}")
                delay(100)
            }
        }
    }

    // =========================================================================
    // TCP → USB forwarding loop
    // =========================================================================

    private suspend fun tcpToUsbLoop() {
        val buffer = ByteArray(BUFFER_SIZE)

        while (currentCoroutineContext().isActive) {
            try {
                if (!tcpBridge.isConnected) {
                    delay(100) // wait for TCP reconnect
                    continue
                }

                // Read from TCP (blocking up to SO_TIMEOUT)
                val bytesRead = tcpBridge.read(buffer)

                if (bytesRead > 0 && usbBridge.isConnected) {
                    val written = usbBridge.write(buffer, bytesRead)
                    if (written < 0) {
                        log("Ошибка записи в USB")
                    }
                } else if (bytesRead < 0) {
                    // TCP connection lost
                    log("TCP соединение разорвано")
                    tcpBridge.disconnect()
                    _state.value = _state.value.copy(tcpStatus = TcpStatus.SEARCHING)
                }

                // Small yield when no data
                if (bytesRead <= 0) {
                    delay(1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("TCP→USB ошибка: ${e.message}")
                tcpBridge.disconnect()
                _state.value = _state.value.copy(tcpStatus = TcpStatus.SEARCHING)
                delay(100)
            }
        }
    }

    // =========================================================================
    // Logging
    // =========================================================================

    private fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $message"
        Log.d(TAG, line)

        val current = _logLines.value.toMutableList()
        current.add(line)
        // Trim old lines to prevent memory bloat
        if (current.size > MAX_LOG_LINES) {
            _logLines.value = current.takeLast(MAX_LOG_LINES)
        } else {
            _logLines.value = current
        }
    }
}
