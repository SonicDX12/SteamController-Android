package com.steamcontroller.android.usb

import android.content.Context
import android.hardware.usb.*
import android.util.Log

class UsbConnectionManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var device: UsbDevice? = null
        private set
    var connection: UsbDeviceConnection? = null
        private set
    var endpointIn: UsbEndpoint? = null
        private set

    // Interfaces claimed only to keep them away from the kernel — e.g. the Steam
    // Controller's legacy "boot keyboard" HID interface used for lizard mode. If we
    // don't claim it, Android's kernel usbhid driver binds it and creates a real,
    // non-virtual keyboard InputDevice — which makes Android believe a hardware
    // keyboard is always connected and suppresses the on-screen keyboard everywhere,
    // for as long as the controller stays plugged in. Released on disconnect().
    private val heldInterfaces = mutableListOf<UsbInterface>()

    companion object {
        private const val TAG = "UsbConnectionManager"
        const val STEAM_VID = 0x28DE
    }

    fun findSteamController(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { it.vendorId == STEAM_VID }
    }

    fun connect(dev: UsbDevice): Boolean {
        if (!usbManager.hasPermission(dev)) {
            Log.e(TAG, "No USB permission for device")
            return false
        }

        logAllInterfaces(dev)

        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            Log.e(TAG, "Failed to open device")
            return false
        }

        // Try each interface in order, pick the first one with an IN endpoint that accepts a claim
        val (iface, ep) = findBestInterface(dev, conn) ?: run {
            Log.e(TAG, "No usable interface/endpoint found")
            conn.close()
            return false
        }

        device = dev
        connection = conn
        endpointIn = ep
        claimRemainingInterfaces(dev, conn, iface)
        Log.i(TAG, "Connected — PID=0x${dev.productId.toString(16).uppercase()} iface=${iface.id} ep=${ep.address} type=${ep.type}")
        return true
    }

    // Force-claims every other interface on the device so the kernel usbhid driver
    // can't bind them behind our back (see heldInterfaces comment). We never read
    // from these — just holding the claim is enough to keep the kernel off them.
    private fun claimRemainingInterfaces(dev: UsbDevice, conn: UsbDeviceConnection, dataIface: UsbInterface) {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.id == dataIface.id) continue
            if (conn.claimInterface(iface, true)) {
                heldInterfaces.add(iface)
                Log.i(TAG, "Claimed iface ${iface.id} (class=0x${iface.interfaceClass.toString(16)}) to keep it off the kernel usbhid driver")
            } else {
                Log.w(TAG, "Could not claim iface ${iface.id} to withhold it from kernel usbhid")
            }
        }
    }

    private fun logAllInterfaces(dev: UsbDevice) {
        Log.i(TAG, "Device VID=0x${dev.vendorId.toString(16)} PID=0x${dev.productId.toString(16)} interfaces=${dev.interfaceCount}")
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            Log.i(TAG, "  iface[$i] id=${iface.id} class=0x${iface.interfaceClass.toString(16)} endpoints=${iface.endpointCount}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT  -> "INT"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                    else -> "CTRL"
                }
                Log.i(TAG, "    ep[$j] addr=0x${ep.address.toString(16)} $dir $type maxPkt=${ep.maxPacketSize}")
            }
        }
    }

    private fun findBestInterface(dev: UsbDevice, conn: UsbDeviceConnection): Pair<UsbInterface, UsbEndpoint>? {
        // Prefer vendor-defined (0xFF) or HID (0x03) interfaces with an IN endpoint
        val preferred = (0 until dev.interfaceCount)
            .map { dev.getInterface(it) }
            .sortedByDescending {
                when (it.interfaceClass) {
                    0xFF -> 2   // vendor-defined first
                    UsbConstants.USB_CLASS_HID -> 1
                    else -> 0
                }
            }

        for (iface in preferred) {
            val ep = findInEndpoint(iface) ?: continue
            if (conn.claimInterface(iface, true)) {
                return iface to ep
            } else {
                Log.w(TAG, "Could not claim iface ${iface.id}, trying next")
            }
        }
        return null
    }

    fun disconnect() {
        try {
            connection?.let { conn ->
                heldInterfaces.forEach { conn.releaseInterface(it) }
                conn.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}")
        } finally {
            heldInterfaces.clear()
            device = null
            connection = null
            endpointIn = null
        }
    }

    val isConnected get() = connection != null

    private fun findInEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN &&
                (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                 ep.type == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                return ep
            }
        }
        return null
    }
}
