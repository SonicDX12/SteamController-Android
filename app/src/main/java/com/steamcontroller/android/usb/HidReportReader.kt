package com.steamcontroller.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.parser.SteamReportParser
import kotlinx.coroutines.*

class HidReportReader(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
    private val onReport: (SteamControllerState, ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "HidReportReader"
    private val BUF_SIZE = 64
    private val READ_TIMEOUT_MS = 100  // increased: 16ms was too tight for some controllers

    private var job: Job? = null
    private var totalReports = 0

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(BUF_SIZE)
            Log.i(TAG, "Read loop started on endpoint 0x${endpoint.address.toString(16)}")
            while (isActive) {
                val len = connection.bulkTransfer(endpoint, buf, BUF_SIZE, READ_TIMEOUT_MS)
                when {
                    len > 0 -> {
                        totalReports++
                        if (totalReports == 1 || totalReports % 100 == 0) {
                            Log.i(TAG, "Reports received: $totalReports, last ID=0x${(buf[0].toInt() and 0xFF).toString(16)}, len=$len")
                        }
                        val raw = buf.copyOf(len)
                        val state = SteamReportParser.parse(buf)
                        // Always emit raw so DebugActivity shows something even with unexpected IDs
                        onReport(state ?: SteamReportParser.parseRaw(buf), raw)
                    }
                    len == 0 -> yield()
                    else     -> yield()  // -1 = timeout, normal
                }
            }
            Log.i(TAG, "Read loop stopped after $totalReports reports")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
