package com.steamcontroller.android.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.input.GamepadMapper
import com.steamcontroller.android.input.ShizukuInputInjector
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.parser.SteamReportParser
import com.steamcontroller.android.uinput.UInputGamepad
import com.steamcontroller.android.usb.HidReportReader
import com.steamcontroller.android.usb.SteamHidProtocol
import com.steamcontroller.android.usb.UsbConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ControllerService : Service() {

    enum class InjectionMode { NONE, UINPUT, SHIZUKU_INJECT }

    companion object {
        private const val TAG = "ControllerService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "steam_controller"
        const val EXTRA_DEVICE = "usb_device"
        const val ACTION_STOP = "com.steamcontroller.android.STOP"
        const val ACTION_NEXT_PROFILE = "com.steamcontroller.android.NEXT_PROFILE"
        const val ACTION_TEST_RUMBLE = "com.steamcontroller.android.TEST_RUMBLE"

        // Observed by DebugActivity / MainActivity for live display
        private val _stateFlow = MutableStateFlow<SteamControllerState?>(null)
        val stateFlow: StateFlow<SteamControllerState?> = _stateFlow.asStateFlow()

        private val _rawReportFlow = MutableStateFlow<ByteArray?>(null)
        val rawReportFlow: StateFlow<ByteArray?> = _rawReportFlow.asStateFlow()

        private val _modeFlow = MutableStateFlow(InjectionMode.NONE)
        val modeFlow: StateFlow<InjectionMode> = _modeFlow.asStateFlow()

        // Emits the active emulated profile id whenever it changes (start, cycle from notif, etc.)
        private val _profileFlow = MutableStateFlow<Int?>(null)
        val profileFlow: StateFlow<Int?> = _profileFlow.asStateFlow()

        // Controller battery as 0..100, or null if unknown. Updated when a HID report carries it.
        private val _batteryFlow = MutableStateFlow<Int?>(null)
        val batteryFlow: StateFlow<Int?> = _batteryFlow.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var usbManager: UsbConnectionManager
    private lateinit var btManager: BluetoothHidManager
    private val legacyInjector = ShizukuInputInjector()
    private lateinit var uinput: UInputGamepad
    private var mode: InjectionMode = InjectionMode.NONE
    private var reader: HidReportReader? = null
    private var heartbeatJob: Job? = null

    // Bits whose changes must pass through the debounce mechanism before being injected.
    // Mechanical switches only — the SC2026's grip squeeze sensors (GRIP_LT/RT), trackpad
    // touch flags (TP_*) and stick touch flags (LS_TOUCH/RS_TOUCH) are capacitive and
    // inherently noisy when the controller is held; if those are mapped, the debounce
    // counter will keep resetting and the mapping will misbehave (known limitation).
    // L4/L5/R4/R5 ARE included — they are real mechanical back-paddle switches.
    private val INJECTABLE_MASK =
        Buttons.A or Buttons.B or Buttons.X or Buttons.Y or
        Buttons.LB or Buttons.RB or
        Buttons.MENU or Buttons.VIEW or Buttons.STEAM or Buttons.QUICK_ACCESS or
        Buttons.LS or Buttons.RS or
        Buttons.L4 or Buttons.L5 or Buttons.R4 or Buttons.R5 or
        Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT

    // Debounce window. USB=333Hz so 5 frames = ~15ms. BT=~150Hz so 3 frames = ~20ms.
    // Tuned to filter capacitive noise without adding perceptible button latency.
    private val DEBOUNCE_FRAMES = 3
    private var confirmedState: SteamControllerState? = null
    private var pendingButtons = 0
    private var pendingFrames = 0

    override fun onCreate() {
        super.onCreate()
        usbManager = UsbConnectionManager(this)
        btManager = BluetoothHidManager(this)
        uinput = UInputGamepad(this, Prefs.getProfile(this))
        uinput.onRumble = { strong, weak -> forwardRumble(strong, weak) }
        createNotificationChannel()
        _profileFlow.value = Prefs.getProfile(this).id

        // Refresh the foreground notification whenever the controller's battery level changes.
        // StateFlow only emits on actual value changes, so this triggers ~once per percent dropped.
        scope.launch {
            _batteryFlow.collect { refreshNotification() }
        }
    }

    private var lastRumbleStrong = -1
    private var lastRumbleWeak   = -1
    private var lastRumbleSentAt = 0L

    /**
     * Manual rumble test: pulse both motors at full strength for 500ms, then stop.
     * Triggered by the "Test rumble" button in CalibrationActivity.
     */
    private fun testRumble() {
        Log.i(TAG, "Test rumble requested")
        // Bypass the throttle by resetting last-sent timestamps
        lastRumbleSentAt = 0
        forwardRumble(0xFFFF, 0xFFFF)
        scope.launch {
            delay(500)
            lastRumbleSentAt = 0
            forwardRumble(0, 0)
        }
    }

    /**
     * Called when a game triggers a rumble effect on the virtual gamepad.
     * Magnitudes are 0..65535. Forwarded to the controller via the active transport.
     *
     * Throttled: we re-send at most every 50ms if the magnitudes change, or every
     * 200ms if they're the same (keep-alive for long-lasting effects).
     */
    private fun forwardRumble(strong: Int, weak: Int) {
        // Apply user-configured intensity (0..100% of game-requested magnitude)
        val intensity = Prefs.getRumbleIntensity(this)
        val scaledStrong = (strong * intensity / 100).coerceIn(0, 0xFFFF)
        val scaledWeak   = (weak   * intensity / 100).coerceIn(0, 0xFFFF)

        val now = System.currentTimeMillis()
        val changed = (scaledStrong != lastRumbleStrong || scaledWeak != lastRumbleWeak)
        val tooSoon = (now - lastRumbleSentAt) < (if (changed) 50 else 200)
        if (tooSoon) return
        lastRumbleStrong = scaledStrong
        lastRumbleWeak   = scaledWeak
        lastRumbleSentAt = now

        Log.v(TAG, "Rumble → controller: strong=$scaledStrong weak=$scaledWeak (intensity=$intensity%)")
        when (Prefs.getTransport(this)) {
            Transport.BLUETOOTH -> btManager.sendRumble(scaledStrong, scaledWeak)
            Transport.USB       -> {
                // USB rumble = feature report via controlTransfer. Not implemented yet —
                // requires identifying the exact SC2026 feature report ID (likely 0x8F
                // per hid-steam.c) and payload format. Same byte structure as BT.
                Log.v(TAG, "USB rumble not implemented yet")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_NEXT_PROFILE) {
            cycleProfile()
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_RUMBLE) {
            testRumble()
            return START_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DEVICE)
        }

        scope.launch { initialize(device) }
        return START_STICKY
    }

    private suspend fun initialize(device: UsbDevice?) {
        val transport = Prefs.getTransport(this@ControllerService)
        Log.i(TAG, "Initializing with transport=$transport")
        chooseInjectionMode()

        val ok = when (transport) {
            Transport.USB       -> initUsb(device)
            Transport.BLUETOOTH -> initBluetooth()
        }
        if (!ok) {
            Log.e(TAG, "Transport init failed, stopping service")
            stopSelf()
            return
        }
        Log.i(TAG, "Controller service running, injection mode = $mode")
    }

    private suspend fun initUsb(device: UsbDevice?): Boolean {
        val dev = device ?: usbManager.findSteamController()
        if (dev == null) {
            Log.e(TAG, "No Steam Controller found over USB")
            return false
        }
        if (!usbManager.connect(dev)) {
            Log.e(TAG, "Failed to connect USB device")
            return false
        }
        val conn = usbManager.connection!!
        val ep   = usbManager.endpointIn!!

        SteamHidProtocol.disableLizardMode(conn)

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(800)
                SteamHidProtocol.heartbeat(conn)
            }
        }

        reader = HidReportReader(conn, ep,
            onReport = { state, raw -> onHidFrame(state, raw) },
            onError  = { msg -> Log.e(TAG, "USB read error: $msg") }
        )
        reader?.start(scope)
        return true
    }

    private fun initBluetooth(): Boolean {
        if (!btManager.isBluetoothAvailable) {
            Log.e(TAG, "Bluetooth disabled or unavailable")
            return false
        }
        val address = Prefs.getBluetoothAddress(this)
        if (address == null) {
            Log.e(TAG, "No paired Bluetooth Steam Controller selected")
            return false
        }
        val mgr = getSystemService(BluetoothManager::class.java)
        val device = try {
            mgr?.adapter?.getRemoteDevice(address)
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid BT address $address: ${t.message}")
            return false
        }
        if (device == null) {
            Log.e(TAG, "No remote device for $address")
            return false
        }

        btManager.connect(
            device,
            onReport = { raw ->
                val state = SteamReportParser.parse(raw) ?: SteamReportParser.parseRaw(raw)
                onHidFrame(state, raw)
            },
            onConnectionChange = { connected ->
                Log.i(TAG, "BT connection state: $connected")
            }
        )

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(800)
                btManager.sendHeartbeat()
            }
        }
        return true
    }

    private fun onHidFrame(state: SteamControllerState, raw: ByteArray) {
        _stateFlow.value = state
        _rawReportFlow.value = raw
        // Battery only ships in the full state report; null when unknown
        state.batteryPercent?.let { pct ->
            if (_batteryFlow.value != pct) _batteryFlow.value = pct
        }
        if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0x45) {
            handleState(state)
        }
    }

    // Try uinput first (real InputDevice, works in games). Fall back to inject if uinput is denied.
    private suspend fun chooseInjectionMode() {
        // 1. Try uinput via Shizuku UserService
        try {
            uinput.bind()
            for (attempt in 0 until 20) {
                if (uinput.isReady) break
                delay(150)
            }
            if (uinput.isReady) {
                setMode(InjectionMode.UINPUT)
                Log.i(TAG, "Using uinput virtual gamepad (${Prefs.getProfile(this@ControllerService).displayName})")
                return
            }
            Log.w(TAG, "uinput service did not become ready, falling back to inject")
            uinput.unbind()
        } catch (t: Throwable) {
            Log.w(TAG, "uinput bind failed: ${t.message}, falling back to inject")
        }

        // 2. Fallback: legacy injectInputEvent via Shizuku reflection
        if (legacyInjector.init()) {
            setMode(InjectionMode.SHIZUKU_INJECT)
            Log.i(TAG, "Using legacy Shizuku injectInputEvent (games may filter this)")
        } else {
            setMode(InjectionMode.NONE)
            Log.e(TAG, "No injection method available")
        }
    }

    private fun setMode(newMode: InjectionMode) {
        mode = newMode
        _modeFlow.value = newMode
        refreshNotification()
    }

    private fun refreshNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Triggered by the notification action: cycle to the next profile (Xbox360 → XboxOne → DS4 → DualSense → ...).
     * Only meaningful in uinput mode; in fallback inject mode the profile is ignored.
     */
    private fun cycleProfile() {
        val profiles = com.steamcontroller.android.uinput.GamepadProfile.values()
        val current = Prefs.getProfile(this)
        val next = profiles[(current.ordinal + 1) % profiles.size]
        Prefs.setProfile(this, next)
        Log.i(TAG, "Cycle profile: ${current.displayName} → ${next.displayName}")

        if (mode == InjectionMode.UINPUT) {
            val ok = uinput.switchProfile(next)
            if (!ok) Log.w(TAG, "switchProfile failed; the gamepad may need a service restart")
        }
        // Reset baseline state so any buttons "held" during the swap don't get injected
        confirmedState = null
        pendingButtons = 0
        pendingFrames = 0

        refreshNotification()
        // Tell MainActivity about the new profile so the dropdown and "Mode:" label update.
        _profileFlow.value = next.id
    }

    private fun handleState(state: SteamControllerState) {
        if (mode == InjectionMode.NONE) return

        // First frame = baseline
        if (confirmedState == null) {
            confirmedState = state
            pendingButtons = state.buttons and INJECTABLE_MASK
            pendingFrames = 0
            return
        }

        // Button debounce: only inject after DEBOUNCE_FRAMES consecutive stable frames
        val injectableBits = state.buttons and INJECTABLE_MASK
        val buttonsConfirmedThisFrame: Boolean
        if (injectableBits == pendingButtons) {
            pendingFrames++
            if (pendingFrames >= DEBOUNCE_FRAMES && injectableBits != (confirmedState!!.buttons and INJECTABLE_MASK)) {
                confirmedState = state
                buttonsConfirmedThisFrame = true
            } else {
                buttonsConfirmedThisFrame = false
            }
        } else {
            pendingButtons = injectableBits
            pendingFrames = 1
            buttonsConfirmedThisFrame = false
        }

        when (mode) {
            InjectionMode.UINPUT -> {
                // Combine confirmed buttons with current raw axes — uinput frame is atomic
                val merged = state.copy(buttons = confirmedState!!.buttons)
                uinput.pushFrame(merged)
            }
            InjectionMode.SHIZUKU_INJECT -> {
                // Axes every frame for smoothness, with live-reloaded calibration
                legacyInjector.injectMotion(GamepadMapper.axes(
                    state,
                    Prefs.getLeftCalibration(this),
                    Prefs.getRightCalibration(this)
                ))
                // Buttons only on debounced change
                if (buttonsConfirmedThisFrame) {
                    GamepadMapper.buttons(state, confirmedState!!).forEach { (keyCode, down) ->
                        legacyInjector.injectKey(keyCode, down)
                    }
                }
            }
            InjectionMode.NONE -> { /* unreachable */ }
        }
    }

    override fun onDestroy() {
        try { uinput.unbind() } catch (_: Throwable) {}
        scope.cancel()
        reader?.stop()
        usbManager.disconnect()
        try { btManager.disconnect() } catch (_: Throwable) {}
        _modeFlow.value = InjectionMode.NONE
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows controller status and active emulation profile"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val profile = Prefs.getProfile(this)
        val battery = _batteryFlow.value

        val modeText = when (mode) {
            InjectionMode.UINPUT         -> getString(R.string.notif_mode_uinput, profile.displayName)
            InjectionMode.SHIZUKU_INJECT -> getString(R.string.notif_mode_inject)
            InjectionMode.NONE           -> getString(R.string.notif_starting)
        }
        val title = getString(R.string.notification_title)
        val text = if (battery != null) "$modeText  •  🔋 $battery%" else modeText

        // Tap on the notification → open MainActivity
        val openIntent = Intent(this, com.steamcontroller.android.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ControllerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            getString(android.R.string.cancel),
            stopIntent
        ).build()

        // "Switch profile" action: cycles to the next emulated controller (only useful in uinput mode).
        val nextProfileIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ControllerService::class.java).apply { action = ACTION_NEXT_PROFILE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextLabel = if (mode == InjectionMode.UINPUT) {
            val profiles = com.steamcontroller.android.uinput.GamepadProfile.values()
            val next = profiles[(profile.ordinal + 1) % profiles.size]
            "→ ${next.displayName}"
        } else {
            "Switch profile"
        }
        val switchAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_rotate),
            nextLabel,
            nextProfileIntent
        ).build()

        val icon = when (mode) {
            InjectionMode.UINPUT         -> android.R.drawable.ic_media_play
            InjectionMode.SHIZUKU_INJECT -> android.R.drawable.ic_media_play
            InjectionMode.NONE           -> android.R.drawable.stat_notify_sync
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(icon)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        // Only show the switch action when uinput is active — pointless when in fallback or starting
        if (mode == InjectionMode.UINPUT) {
            builder.addAction(switchAction)
        }
        builder.addAction(stopAction)

        return builder.build()
    }
}
