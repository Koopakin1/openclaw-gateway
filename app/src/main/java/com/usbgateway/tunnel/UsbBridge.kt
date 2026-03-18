package com.usbgateway.tunnel

import android.content.Context
import android.hardware.usb.*
import android.util.Log

/**
 * UsbBridge: Handles raw byte I/O with the USB device via bulk transfers.
 *
 * Discovers the first USB device with bulk IN/OUT endpoints,
 * claims the interface, and provides read()/write() methods.
 */
class UsbBridge(private val context: Context) {

    companion object {
        private const val TAG = "UsbBridge"
        private const val TIMEOUT_MS = 500 // bulk transfer timeout
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    val isConnected: Boolean get() = connection != null && endpointIn != null && endpointOut != null

    /**
     * Opens a connection to the first available USB device with bulk endpoints.
     * Returns true if successful, false otherwise.
     */
    fun open(device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Find a USB interface with bulk IN and OUT endpoints
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        epIn = ep
                    } else {
                        epOut = ep
                    }
                }
            }

            if (epIn != null && epOut != null) {
                val conn = manager.openDevice(device)
                if (conn == null) {
                    Log.e(TAG, "openDevice() returned null — permission denied?")
                    return false
                }

                if (!conn.claimInterface(intf, true)) {
                    Log.e(TAG, "claimInterface() failed")
                    conn.close()
                    return false
                }

                connection = conn
                usbInterface = intf
                endpointIn = epIn
                endpointOut = epOut

                Log.i(TAG, "USB device opened: ${device.deviceName}, " +
                        "IN ep=0x${epIn.address.toString(16)}, " +
                        "OUT ep=0x${epOut.address.toString(16)}, " +
                        "maxPacket=${epIn.maxPacketSize}")
                return true
            }
        }

        Log.e(TAG, "No bulk endpoints found on device ${device.deviceName}")
        return false
    }

    /**
     * Reads raw bytes from the USB IN endpoint.
     * Returns the number of bytes read, or -1 on error.
     * This is a blocking call (up to TIMEOUT_MS).
     */
    fun read(buffer: ByteArray): Int {
        val conn = connection ?: return -1
        val ep = endpointIn ?: return -1
        return conn.bulkTransfer(ep, buffer, buffer.size, TIMEOUT_MS)
    }

    /**
     * Writes raw bytes to the USB OUT endpoint.
     * Returns the number of bytes written, or -1 on error.
     */
    fun write(data: ByteArray, length: Int): Int {
        val conn = connection ?: return -1
        val ep = endpointOut ?: return -1
        return conn.bulkTransfer(ep, data, length, TIMEOUT_MS)
    }

    /**
     * Releases the USB interface and closes the connection.
     */
    fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB: ${e.message}")
        } finally {
            connection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
        }
    }
}
