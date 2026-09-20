package com.steamcontroller.android.bt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.steamcontroller.android.parser.SteamReportParser
import com.steamcontroller.android.protocol.SteamCommands
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

        private const val DESIRED_MTU = 100

        /** Bounded so a rumble backlog cannot build up behind the BLE link. */
        private const val MAX_QUEUED_WRITES = 8

        /**
         * Vendor characteristic carrying the battery level over BLE, pinned from a
         * hardware logcat capture (2026-07-12): 14-byte payloads where byte[1] holds
         * steady (0x5d = 93) across samples taken seconds apart, while every other byte
         * fluctuates like a counter or checksum.
         *
         * 100f6c79 is NOT it, despite looking like a status char: its 5-byte payloads
         * alternate "01 02 00 00 00" / "00 02 00 00 00" in lockstep with our own
         * heartbeat writes, so it is an ack ping-pong tied to writes.
         */
        private const val VALVE_BATTERY_CHAR: Long = 0x100f6c78L
        private const val VALVE_BATTERY_REPORT_LEN = 14
    }

    private enum class State { IDLE, CONNECTING, MTU_REQUESTED, DISCOVERING, SUBSCRIBING, READY }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var featureWriteChar: BluetoothGattCharacteristic? = null

    private val pendingSubs = mutableListOf<BluetoothGattCharacteristic>()
    private var subsIndex = 0

    @Volatile private var state: State = State.IDLE

    private val writeQueue = ArrayDeque<Pair<ByteArray, Int>>()
    private val writeInFlight = AtomicBoolean(false)

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
            synchronized(writeQueue) { writeQueue.clear() }
            writeInFlight.set(false)
            onConnectionChange?.invoke(false)
        }
    }

    /**
     * Queue a payload for the feature-write characteristic.
     *
     * GATT allows exactly one outstanding write per connection: issuing a second one
     * before onCharacteristicWrite lands silently drops it. Every command therefore goes
     * through this queue, which is drained one write per callback. The queue is bounded —
     * rumble updates arrive faster than BLE can carry them, and a backlog is worse than a
     * dropped frame, so the oldest entries are discarded when it fills.
     */
    private fun enqueueWrite(
        payload: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ) {
        if (state != State.READY) return
        synchronized(writeQueue) {
            while (writeQueue.size >= MAX_QUEUED_WRITES) writeQueue.removeFirst()
            writeQueue.addLast(payload to writeType)
        }
        pumpWriteQueue()
    }

    private fun pumpWriteQueue() {
        val g = gatt ?: return
        val ch = featureWriteChar ?: return
        if (!writeInFlight.compareAndSet(false, true)) return
        val next = synchronized(writeQueue) { writeQueue.removeFirstOrNull() }
        if (next == null) {
            writeInFlight.set(false)
            return
        }
        val (payload, writeType) = next
        try {
            ch.value = payload
            ch.writeType = writeType
            if (!g.writeCharacteristic(ch)) {
                Log.v(TAG, "writeCharacteristic returned false, dropping payload")
                writeInFlight.set(false)
                pumpWriteQueue()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
            writeInFlight.set(false)
        }
    }

    /**
     * Rumble over BLE.
     *
     * Uses the SC1-era 0x8F HAPTIC_PULSE command, NOT OUT_HAPTIC_RUMBLE (0x80).
     * Switching to 0x80 was tried on real SC2026 hardware (2026-07-12) and produced no
     * vibration at all, while 0x8F does. The likely reason: 0x80/0x81/0x82 are genuine
     * USB HID *output* reports, a separate channel from the raw command scheme, and that
     * channel does not necessarily exist over this vendor GATT characteristic. Do not
     * retry 0x80 here without first confirming by sniff or log that another characteristic
     * in the 100f6cb5-be write range is meant for it. (USB rumble is a different story and
     * does use 0x80 — see SteamHidProtocol.)
     *
     * Payload, after the Linux hid-steam driver's steam_haptic_pulse:
     *   byte 0: command id (0x8F)
     *   byte 1: pad id (0 = left, 1 = right)
     *   bytes 2-3: high period (u16 LE, microseconds — actuator ON time per cycle)
     *   bytes 4-5: low period  (u16 LE, microseconds — actuator OFF per cycle)
     *   bytes 6-7: repeat count (u16 LE, 0xFFFF = continuous, 0 = stop)
     *
     * The two pad writes go through the queue rather than back to back: GATT carries one
     * write at a time, so the second was previously being dropped, leaving only one
     * actuator running — which may well be part of why the rumble measured "too weak"
     * through the tuning rounds below.
     */
    fun sendRumble(strong: Int, weak: Int) {
        enqueueWrite(pulsePayload(0, strong), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        enqueueWrite(pulsePayload(1, weak), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    private fun pulsePayload(padId: Int, magnitude: Int): ByteArray {
        val mag = magnitude.coerceIn(0, 0xFFFF)
        if (mag == 0) {
            // Explicit stop. This used to return null and skip the write entirely, on the
            // assumption the controller would stop once the pulse train expired. Wrong on
            // hardware: a repeat=1 pulse just buzzes on, and "Test rumble" had no way to
            // stop it. repeat=0 cancels any train in flight.
            return byteArrayOf(0x8F.toByte(), padId.toByte(), 0, 0, 0, 0, 0, 0)
        }
        // Hold the cycle period near the actuator's resonance and vary only duty cycle.
        // An earlier mapping scaled the high period against a fixed 1000us low period,
        // which swept the drive frequency from ~909Hz down to ~333Hz across the magnitude
        // range; LRAs only move meaningfully near resonance, so most of that range was
        // off-resonance and lost amplitude regardless of duty.
        //
        // Second tuning round: 182Hz at 88% max duty still felt weak, so the period moved
        // to ~160Hz and max duty to 97%. Both were changed together, which confounds the
        // next measurement — back either out independently if needed. The SC2026's actual
        // resonant frequency is unconfirmed (no datasheet); retune totalPeriodUs if it
        // still feels off.
        val totalPeriodUs = 6250  // ~160Hz
        val minDutyPct = 25
        val maxDutyPct = 97
        val dutyPct = minDutyPct + (mag * (maxDutyPct - minDutyPct) / 0xFFFF)
        val highPeriod = (totalPeriodUs * dutyPct / 100).coerceIn(1, totalPeriodUs - 1)
        val lowPeriod = totalPeriodUs - highPeriod
        // Continuous, not a single pulse: at repeat=1 the motor fired one ~2-3ms blip per
        // send and ControllerService throttles sends to every 50-200ms, so it sat idle
        // over 95% of the time. Confirmed too weak on hardware.
        val repeat = 0xFFFF
        return byteArrayOf(
            0x8F.toByte(),
            padId.toByte(),
            (highPeriod and 0xFF).toByte(), (highPeriod shr 8 and 0xFF).toByte(),
            (lowPeriod and 0xFF).toByte(),  (lowPeriod shr 8 and 0xFF).toByte(),
            (repeat and 0xFF).toByte(),     (repeat shr 8 and 0xFF).toByte(),
        )
    }

    /**
     * Lizard-mode keepalive — DISABLED over BLE, deliberately.
     *
     * Dropping the 64-byte padding got these writes accepted (no more GATT status 7), and
     * the firmware promptly started buzzing continuously: it is reading the buffer as
     * something else entirely. That makes sense next to the one format known to work on
     * this characteristic, V2's haptic pulse, which is [0x8F, padId, args...] — command id
     * in byte 0, no feature-report wrapper and no size byte. Our [0x01, 0x81, 0x00] puts
     * 0x01 in the command slot, and 0x81 — OUT_HAPTIC_PULSE — right behind it.
     *
     * So the vendor characteristic is a raw command channel, not a HID feature-report one,
     * and the encapsulation upstream documents (which it drives over HID-over-GATT on
     * Windows) does not apply here. Sending nothing is the safe state: the firmware simply
     * stays in lizard mode, which is where it already was — the bare 0x85 V2 sent is
     * SET_DEFAULT_MAPPINGS, i.e. lizard ON.
     *
     * Next thing to try, one write at a time with a finger ready on the power button:
     * [0x81] alone, then [0x87, size, key, lo, hi, ...] for the trackpad settings.
     */
    fun sendHeartbeat() {
        // Intentionally empty until the raw command format is confirmed on hardware.
    }

    /**
     * Silence any haptic pulse train left running on the controller.
     *
     * A pulse sent with repeat=0xFFFF runs until something cancels it, and a malformed
     * write can start one, so the controller can be found already buzzing at connect time.
     * repeat=0 on both pads clears it.
     */
    fun stopAllHaptics() {
        enqueueWrite(pulsePayload(0, 0), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        enqueueWrite(pulsePayload(1, 0), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    /** Enable or disable the raw accel/gyro block in the state report. */
    fun setImuEnabled(enabled: Boolean) {
        enqueueWrite(SteamCommands.setImuMode(enabled))
    }

    /** Hand the controller back to its firmware emulation. Call before disconnecting. */
    fun restoreLizardMode() {
        // Nothing to restore: we never took the controller out of lizard mode over BLE.
        // Do stop the motors, though — leaving them running would outlive the app.
        stopAllHaptics()
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
                    synchronized(writeQueue) { writeQueue.clear() }
                    writeInFlight.set(false)
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
            if (status != 0) Log.w(TAG, "Write ${ch.uuid} failed: status=$status")
            writeInFlight.set(false)
            pumpWriteQueue()
        }

        private var reportCounter = 0
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            val data = ch.value ?: return
            reportCounter++
            // BLE strips the HID report ID, so re-attach it before the parsers see the
            // data and they can work on the same shape they do over USB. Anything we
            // cannot identify is forwarded untouched, and logged so it can be identified.
            val short = shortUuid(ch.uuid)
            val toForward: ByteArray = when {
                data.size >= 40 -> {
                    val withId = ByteArray(data.size + 1)
                    withId[0] = SteamReportParser.REPORT_STATE.toByte()
                    System.arraycopy(data, 0, withId, 1, data.size)
                    withId
                }
                // Synthesise a 0x43 report from the vendor battery char. The payload order
                // has to match what parseBatteryStatus expects — [chargeState, percent] —
                // and this characteristic carries no charge state, so it goes out as 0
                // (unknown) rather than inventing one.
                short == VALVE_BATTERY_CHAR && data.size == VALVE_BATTERY_REPORT_LEN -> {
                    // byte[0] is the charge state and byte[1] the percent — the same
                    // [chargeState, percent] order the 0x43 report uses, confirmed on
                    // hardware while charging (04 64 = CHARGING_DONE at 100%). The rest is
                    // still unidentified, so log the payload whole.
                    Log.i(TAG, "Battery report: ${data.joinToString(" ") { "%02x".format(it) }}" +
                        " → percent=${data[1].toInt() and 0xFF}")
                    byteArrayOf(SteamReportParser.REPORT_BATTERY_STATUS.toByte(), data[0], data[1])
                }
                else -> {
                    Log.v(TAG, "Unrecognized short report (${data.size}B) from ${ch.uuid}: " +
                        data.joinToString(" ") { "%02x".format(it) })
                    data
                }
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
            state = State.READY
            if (featureWriteChar == null) {
                Log.w(TAG, "No feature write char; skipping disable lizard")
                return
            }
            // Clear any haptic left running from a previous session or a bad write.
            stopAllHaptics()
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
