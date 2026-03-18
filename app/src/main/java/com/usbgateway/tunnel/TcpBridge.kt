package com.usbgateway.tunnel

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TcpBridge: Manages a raw TCP socket connection.
 *
 * Provides blocking read/write on the socket streams.
 * tcpNoDelay is enabled to minimize latency.
 */
class TcpBridge {

    companion object {
        private const val TAG = "TcpBridge"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val SO_TIMEOUT_MS = 500 // read timeout to allow periodic checks
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Opens a TCP connection to the given host:port.
     * Throws IOException on failure (caller handles reconnect logic).
     */
    @Throws(IOException::class)
    fun connect(host: String, port: Int) {
        disconnect() // clean up any previous connection

        val sock = Socket()
        sock.tcpNoDelay = true        // disable Nagle's algorithm for low latency
        sock.soTimeout = SO_TIMEOUT_MS
        sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

        socket = sock
        inputStream = sock.getInputStream()
        outputStream = sock.getOutputStream()

        Log.i(TAG, "TCP connected to $host:$port")
    }

    /**
     * Reads bytes from the TCP input stream.
     * Returns number of bytes read, 0 on timeout, or -1 on end-of-stream / error.
     */
    fun read(buffer: ByteArray): Int {
        return try {
            val n = inputStream?.read(buffer) ?: -1
            n
        } catch (e: java.net.SocketTimeoutException) {
            0 // timeout — no data available, not an error
        } catch (e: IOException) {
            -1
        }
    }

    /**
     * Writes bytes to the TCP output stream and flushes immediately.
     * Throws IOException on failure.
     */
    @Throws(IOException::class)
    fun write(data: ByteArray, length: Int) {
        outputStream?.write(data, 0, length)
        outputStream?.flush()
    }

    /**
     * Closes the TCP socket and streams.
     */
    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TCP: ${e.message}")
        } finally {
            inputStream = null
            outputStream = null
            socket = null
        }
    }
}
