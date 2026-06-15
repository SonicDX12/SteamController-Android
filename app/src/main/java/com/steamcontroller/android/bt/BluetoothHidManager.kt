package com.steamcontroller.android.bt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SC2026 via Bluetooth LE — uses Valve's vendor GATT service (100f6c32-...).
 * The standard HID service 0x1812 is claimed by the OS and requires BLUETOOTH_PRIVILEGED.
 *
 * GATT operations are serialized via a small state machine:
 *   CONNECTED → request MTU → discover services → subscribe N chars one-by-one → send disable lizard → READY
 * Each step waits for its own callback before triggering the next. Duplicate callbacks
 * (Android Bluetooth stack sometimes fires onMtuChanged twice) are ignored.
 */
@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidManager"

        val VALVE_SERVICE_UUID: UUID = UUID.fromString("100f6c32-1735-4313-b402-38567131e5f3")
        private const val VALVE_NOTIFY_LOW: Long  = 0x100f6c75L
        private const val VALVE_NOTIFY_HIGH: Long = 0x100f6c7aL
        private const val VALVE_WRITE_LOW: Long   = 0x100f6cb5L
        private const val VALVE_WRITE_HIGH: Long  = 0x100f6cbeL

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val NAME_HINTS = listOf("Steam Ctrl", "Steam Controller", "SteamController", "Valve")

        private val DISABLE_LIZARD = byteArrayOf(0x85.toByte())
        private const val DESIRED_MTU = 100
    }

    private enum class State { IDLE, CONNECTING, MTU_REQUESTED, DISCOVERING, SUBSCRIBING, READY }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var featureWriteChar: BluetoothGattCharacteristic? = null

    private val pendingSubs = mutableListOf<BluetoothGattCharacteristic>()
    private var subsIndex = 0

    @Volatile private var state: State = State.IDLE
    private val heartbeatBusy = AtomicBoolean(false)

    private var onReport: ((ByteArray) -> Unit)? = null
    private var onConnectionChange: ((Boolean) -> Unit)? = null

    val isBluetoothAvailable: Boolean get() = adapter != null && adapter.isEnabled

    fun listPairedSteamControllers(): List<BluetoothDevice> {
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices.orEmpty().filter { dev ->
                val n = dev.name ?: return@filter false
                NAME_HINTS.any { hint -> n.contains(hint, ignoreCase = true) }
            }
        } catch (t: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT: ${t.message}")
            emptyList()
        }
    }

    fun connect(
        device: BluetoothDevice,
        onReport: (ByteArray) -> Unit,
        onConnectionChange: (Boolean) -> Unit
    ) {
        this.onReport = onReport
        this.onConnectionChange = onConnectionChange
        Log.i(TAG, "Connecting GATT to ${safeName(device)} (${device.address})")
        state = State.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect: ${t.message}")
        } finally {
            gatt = null
            featureWriteChar = null
            pendingSubs.clear()
            subsIndex = 0
            state = State.IDLE
            onConnectionChange?.invoke(false)
        }
    }

    /** Heartbeat: send 0x85 to feature write char. Skip if a write is already in flight. */
    /**
     * Send a rumble command to the controller.
     * Magnitudes are Android FF values (0..65535) — strong = left motor, weak = right.
     *
     * Payload format inspired by the Linux `hid-steam` driver (steam_haptic_pulse):
     *   byte 0: command id (0x8F = HAPTIC_PULSE on older Steam Controllers — likely
     *           different on SC2026, to be validated empirically)
     *   byte 1: pad id (0 = left, 1 = right)
     *   bytes 2-3: high period (u16 LE, microseconds — actuator ON time per cycle)
     *   bytes 4-5: low period  (u16 LE, microseconds — actuator OFF time per cycle)
     *   bytes 6-7: repeat count (u16 LE, 0xFFFF for continuous)
     *
     * Magnitude is encoded by the ratio high/low. Mapping used here:
     *   magnitude 0xFFFF → high=1000us, low=1000us  (50% duty, full strength)
     *   magnitude 0x0000 → no command sent (treated as stop)
     */
    fun sendRumble(strong: Int, weak: Int) {
        if (state != State.READY) return
        val ch = featureWriteChar ?: return
        val g = gatt ?: return

        val left  = magnitudeToPayload(0, strong) ?: return
        val right = magnitudeToPayload(1, weak)   ?: return

        // Send left then right. WRITE_NO_RESPONSE so they don't queue up acks.
        for (payload in listOf(left, right)) {
            try {
                ch.value = payload
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(ch)
            } catch (t: Throwable) {
                Log.w(TAG, "rumble write failed: ${t.message}")
            }
        }
    }

    private fun magnitudeToPayload(padId: Int, magnitude: Int): ByteArray? {
        // 0 magnitude → don't bother sending (the controller will stop on its own
        // once the previous pulse's repeat count expires)
        val mag = magnitude.coerceIn(0, 0xFFFF)
        if (mag == 0) return null
        // Map magnitude 0..65535 to high period 100..2000 microseconds (inverse: stronger = shorter? we try direct).
        // Empirical: try direct mapping first. If feels backwards, invert.
        val highPeriod = (mag * 2000 / 0xFFFF).coerceIn(100, 2000)
        val lowPeriod = 1000
        val repeat = 1
        return byteArrayOf(
            0x8F.toByte(),                       // command id (HAPTIC_PULSE — guess)
            padId.toByte(),
            (highPeriod and 0xFF).toByte(), (highPeriod shr 8 and 0xFF).toByte(),
            (lowPeriod and 0xFF).toByte(),  (lowPeriod shr 8 and 0xFF).toByte(),
            (repeat and 0xFF).toByte(),     (repeat shr 8 and 0xFF).toByte(),
        )
    }

    fun sendHeartbeat() {
        if (state != State.READY) return
        val g = gatt ?: return
        val ch = featureWriteChar ?: return
        if (!heartbeatBusy.compareAndSet(false, true)) return  // previous heartbeat not yet acked
        try {
            ch.value = DISABLE_LIZARD
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = g.writeCharacteristic(ch)
            if (!ok) {
                heartbeatBusy.set(false)
                Log.v(TAG, "heartbeat skipped: writeCharacteristic returned false")
            }
        } catch (t: Throwable) {
            heartbeatBusy.set(false)
            Log.w(TAG, "heartbeat write failed: ${t.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onConnectionChange?.invoke(true)
                    // Request a tight connection interval (11.25–15ms) to minimize input latency.
                    // Default is ~50ms which is fine for sensors but laggy for gamepads.
                    val priOk = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Log.i(TAG, "requestConnectionPriority(HIGH) → $priOk")

                    if (state == State.CONNECTING) {
                        state = State.MTU_REQUESTED
                        val ok = g.requestMtu(DESIRED_MTU)
                        if (!ok) {
                            Log.w(TAG, "requestMtu($DESIRED_MTU) returned false, skipping to discover")
                            state = State.DISCOVERING
                            g.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onConnectionChange?.invoke(false)
                    try { g.close() } catch (_: Throwable) {}
                    gatt = null
                    featureWriteChar = null
                    pendingSubs.clear()
                    subsIndex = 0
                    state = State.IDLE
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status (state=$state)")
            // Guard: this callback is sometimes fired twice on Android. Only act once.
            if (state != State.MTU_REQUESTED) return
            state = State.DISCOVERING
            val ok = g.discoverServices()
            Log.i(TAG, "discoverServices → $ok")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status (state=$state)")
            if (state != State.DISCOVERING) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed")
                return
            }

            // Log everything once for empirical validation
            for (svc in g.services) {
                Log.i(TAG, "Service ${svc.uuid}")
                for (ch in svc.characteristics) {
                    Log.i(TAG, "  Char ${ch.uuid}  ${describeProps(ch.properties)}")
                }
            }

            val valve = g.getService(VALVE_SERVICE_UUID)
            if (valve == null) {
                Log.e(TAG, "Valve vendor service not found")
                return
            }

            pendingSubs.clear()
            featureWriteChar = null
            for (ch in valve.characteristics) {
                val short = shortUuid(ch.uuid) ?: continue
                val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val canWrite = (ch.properties and (
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

                if (canNotify && short in VALVE_NOTIFY_LOW..VALVE_NOTIFY_HIGH) {
                    pendingSubs.add(ch)
                }
                if (canWrite && short in VALVE_WRITE_LOW..VALVE_WRITE_HIGH && featureWriteChar == null) {
                    featureWriteChar = ch
                }
            }
            Log.i(TAG, "Found ${pendingSubs.size} notify chars; feature-write=${featureWriteChar?.uuid}")

            subsIndex = 0
            state = State.SUBSCRIBING
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.v(TAG, "onDescriptorWrite ${descriptor.uuid} status=$status (state=$state, idx=$subsIndex/${pendingSubs.size})")
            if (state == State.SUBSCRIBING) subscribeNext(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            heartbeatBusy.set(false)
            if (status != 0) Log.w(TAG, "Write ${ch.uuid} failed: status=$status")
        }

        private var reportCounter = 0
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            val data = ch.value ?: return
            reportCounter++
            // BLE strips the HID Report ID prefix; prepend 0x45 to reuse the USB parser.
            // Only state reports (>=40 bytes) get the prefix; short reports (battery/status) are forwarded as-is.
            val toForward: ByteArray = if (data.size >= 40) {
                val withId = ByteArray(data.size + 1)
                withId[0] = 0x45
                System.arraycopy(data, 0, withId, 1, data.size)
                withId
            } else {
                data
            }
            // Log only the first report and one every 1000 (Hz check) — way less spammy
            if (reportCounter == 1 || reportCounter % 1000 == 0) {
                Log.i(TAG, "Report #$reportCounter from ${ch.uuid}: ${data.size} bytes")
            }
            onReport?.invoke(toForward)
        }
    }

    private fun subscribeNext(g: BluetoothGatt) {
        if (subsIndex >= pendingSubs.size) {
            // All subscriptions done — send disable lizard mode
            Log.i(TAG, "All ${pendingSubs.size} subscriptions complete, sending disable lizard")
            val ch = featureWriteChar
            state = State.READY
            if (ch == null) {
                Log.w(TAG, "No feature write char; skipping disable lizard")
                return
            }
            ch.value = DISABLE_LIZARD
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = g.writeCharacteristic(ch)
            Log.i(TAG, "Disable lizard write: $ok")
            return
        }

        val ch = pendingSubs[subsIndex++]
        val nOk = g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "No CCCD on ${ch.uuid}, skipping")
            subscribeNext(g)
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val wOk = g.writeDescriptor(cccd)
        Log.v(TAG, "Subscribe ${ch.uuid} idx=${subsIndex-1} setNotify=$nOk writeDesc=$wOk")
        if (!wOk) {
            // Move on; we'll lose this one but try the rest
            subscribeNext(g)
        }
    }

    private fun shortUuid(uuid: UUID): Long? {
        val s = uuid.toString()
        if (!s.endsWith("-1735-4313-b402-38567131e5f3")) return null
        return try { java.lang.Long.parseLong(s.substring(0, 8), 16) } catch (_: Throwable) { null }
    }

    private fun describeProps(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0)              parts += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)             parts += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)            parts += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)          parts += "INDICATE"
        return parts.joinToString("|").ifEmpty { "—" }
    }

    private fun safeName(device: BluetoothDevice): String =
        try { device.name ?: "?" } catch (_: SecurityException) { "?" }
}
