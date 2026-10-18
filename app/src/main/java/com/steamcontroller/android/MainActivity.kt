package com.steamcontroller.android

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.ui.*
import com.steamcontroller.android.uinput.GamepadProfile
import com.steamcontroller.android.usb.UsbConnectionManager
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

enum class AppTab { HOME, PROFILES, SETTINGS }

/** Full-height screens opened from a tab. They hide the tab bar and carry a back arrow. */
enum class SubScreen { CALIBRATION, MAPPING, DEBUG, APP_PICKER }

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // UI state read by the Compose tree. Kept on the activity because the permission,
    // USB and service callbacks below are what change it.
    private var serviceRunning by mutableStateOf(false)
    private var shizukuReady by mutableStateOf(false)
    private var transport by mutableStateOf(Transport.USB)
    private var profile by mutableStateOf(GamepadProfile.XBOX_360)
    private var pairedBtDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var btAddress by mutableStateOf<String?>(null)
    private var themeMode by mutableStateOf(ThemeMode.STEAM)

    // Tracks whether the current "started" session has reached a working injection mode.
    // Used so the modeFlow observer doesn't mistake StateFlow's initial NONE replay for
    // an external service stop right after the user pressed Start.
    private var hasSeenActiveMode = false

    private val usbPermissionAction = "com.steamcontroller.android.USB_PERMISSION"
    private val githubRepoUrl = "https://github.com/SonicDX12/SteamController-Android"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                usbPermissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        device?.let { startControllerService(it) }
                    } else {
                        log("USB permission denied")
                        Toast.makeText(this@MainActivity, "USB permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let { onDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    stopControllerService()
                    log("Controller disconnected")
                }
            }
        }
    }

    private val shizukuRequestCode = 1001

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted")
            shizukuReady = true
            checkAndRequestUsb()
        } else {
            log("Shizuku permission denied")
            Toast.makeText(this, getString(R.string.shizuku_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        themeMode = Prefs.getThemeMode(this)
        transport = Prefs.getTransport(this)
        profile = Prefs.getProfile(this)
        btAddress = Prefs.getBluetoothAddress(this)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        requestNotificationPermissionIfNeeded()
        if (transport == Transport.BLUETOOTH) ensureBluetoothPermissionThenRefresh()

        setContent { Content() }

        // Observe injection mode changes from the service.
        // Also detect the service being stopped externally (e.g. via the notification action)
        // and re-sync the UI state so the button flips back to "Start".
        //
        // Subtlety: a fresh `collect` on a StateFlow immediately replays its current value,
        // which is `NONE` when no service ever ran. If the user taps Start *before* that
        // initial replay runs on the Main thread, we'd see `mode==NONE && serviceRunning==true`
        // and incorrectly reset the button back to "Start". `hasSeenActiveMode` defends
        // against that — we only treat a NONE as "service stopped" once we've previously
        // observed a working mode in this session.
        lifecycleScope.launch {
            ControllerService.modeFlow.collect { mode ->
                if (mode != ControllerService.InjectionMode.NONE) {
                    hasSeenActiveMode = true
                    // Catch-up sync: activity re-entered while service was already running.
                    serviceRunning = true
                } else if (serviceRunning && hasSeenActiveMode) {
                    serviceRunning = false
                    hasSeenActiveMode = false
                    log("Service stopped")
                }
            }
        }

        // Observe profile changes — e.g. when the user cycles via the notification action.
        // We need a dedicated flow because modeFlow doesn't re-emit when the profile changes within UINPUT.
        lifecycleScope.launch {
            ControllerService.profileFlow.collect { profileId ->
                if (profileId != null) profile = GamepadProfile.fromId(profileId)
            }
        }

        // Handle intent if launched by USB attach event
        intent?.let { handleIntent(it) }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
        var sub by rememberSaveable { mutableStateOf<SubScreen?>(null) }
        var pickerProfileId by rememberSaveable { mutableStateOf("") }

        // Back closes a sub-screen first, then returns to Home, then leaves the app.
        BackHandler(enabled = sub != null || tab != AppTab.HOME) {
            if (sub != null) sub = null else tab = AppTab.HOME
        }

        SteamTheme(themeMode) {
            val tabs = listOf(
                Tab("Home", painterResource(R.drawable.ic_gamepad), tab == AppTab.HOME) { tab = AppTab.HOME },
                Tab("Profiles", painterResource(R.drawable.ic_game_profiles), tab == AppTab.PROFILES) { tab = AppTab.PROFILES },
                Tab("Settings", painterResource(R.drawable.ic_settings), tab == AppTab.SETTINGS) { tab = AppTab.SETTINGS },
            )
            // Keeps each tab's scroll position while a sub-screen covers it.
            val saved = rememberSaveableStateHolder()
            AppRoot(tabs, showTabs = sub == null) {
                val back = { sub = null }
                when (sub) {
                    SubScreen.CALIBRATION -> CalibrationScreen(onBack = back)
                    SubScreen.MAPPING -> MappingScreen(
                        onBack = back,
                        onOpenProfiles = { sub = null; tab = AppTab.PROFILES },
                    )
                    SubScreen.DEBUG -> DebugScreen(onBack = back)
                    SubScreen.APP_PICKER -> AppPickerScreen(pickerProfileId, onBack = back)
                    null -> saved.SaveableStateProvider(tab) { when (tab) {
                        AppTab.HOME -> HomeScreen(
                            serviceRunning = serviceRunning,
                            shizukuReady = shizukuReady,
                            transport = transport,
                            onTransport = ::onTransportPicked,
                            profile = profile,
                            onProfile = ::onProfilePicked,
                            btDevices = pairedBtDevices.map { dev ->
                                // Show just the friendly name; the address is what gets saved.
                                dev.address to ((try { dev.name } catch (_: SecurityException) { null }) ?: "Unknown")
                            },
                            btAddress = btAddress,
                            onBtDevice = { address ->
                                btAddress = address
                                Prefs.setBluetoothAddress(this, address)
                                log("BT device: $address")
                            },
                            onRefreshBt = {
                                log("Refreshing Bluetooth devices…")
                                ensureBluetoothPermissionThenRefresh()
                            },
                            onToggleService = {
                                if (serviceRunning) stopControllerService() else checkPermissionsAndStart()
                            },
                            onGithub = ::openGithubRepo,
                        )
                        AppTab.PROFILES -> ProfilesScreen(
                            onBindApps = { id -> pickerProfileId = id; sub = SubScreen.APP_PICKER },
                            onProfileLoaded = ::syncFromPrefs,
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            themeMode = themeMode,
                            onThemeMode = { themeMode = it; Prefs.setThemeMode(this, it) },
                            onOpen = { sub = it },
                            onGithub = ::openGithubRepo,
                        )
                    } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            device?.let { onDeviceAttached(it) }
        }
    }

    /** Re-read everything a loaded game profile may have overwritten. */
    private fun syncFromPrefs() {
        transport = Prefs.getTransport(this)
        profile = Prefs.getProfile(this)
        btAddress = Prefs.getBluetoothAddress(this)
    }

    private fun onTransportPicked(picked: Transport) {
        if (picked == transport) return
        transport = picked
        Prefs.setTransport(this, picked)
        if (picked == Transport.BLUETOOTH) ensureBluetoothPermissionThenRefresh()
        log("Transport set: ${picked.displayName}")
        if (serviceRunning) {
            Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onProfilePicked(picked: GamepadProfile) {
        if (picked.id == profile.id) return
        profile = picked
        Prefs.setProfile(this, picked)
        log("Emulated controller → ${picked.displayName}")
        if (serviceRunning) {
            Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureBluetoothPermissionThenRefresh() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 9002)
        } else {
            refreshBluetoothDevices()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9002 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            refreshBluetoothDevices()
        }
    }

    private fun refreshBluetoothDevices() {
        val mgr = getSystemService(BluetoothManager::class.java)
        if (mgr?.adapter?.isEnabled != true) {
            log("Bluetooth disabled — enable it in settings")
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }
        pairedBtDevices = BluetoothHidManager(this).listPairedSteamControllers()
        if (pairedBtDevices.isEmpty()) {
            log("No Steam Controller paired — pair via Android Bluetooth settings first")
            return
        }
        val saved = pairedBtDevices.firstOrNull { it.address == btAddress } ?: pairedBtDevices.first()
        btAddress = saved.address
        Prefs.setBluetoothAddress(this, saved.address)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    private fun openGithubRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubRepoUrl)))
        } catch (t: Throwable) {
            Toast.makeText(this, "No browser app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        when {
            !Shizuku.pingBinder() -> {
                log("Shizuku not running")
                Toast.makeText(this, getString(R.string.shizuku_not_running), Toast.LENGTH_LONG).show()
            }
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                Shizuku.requestPermission(shizukuRequestCode)
            }
            else -> {
                shizukuReady = true
                when (Prefs.getTransport(this)) {
                    Transport.USB       -> checkAndRequestUsb()
                    Transport.BLUETOOTH -> startBluetoothService()
                }
            }
        }
    }

    private fun startBluetoothService() {
        if (Prefs.getBluetoothAddress(this) == null) {
            Toast.makeText(this, "Select a paired Bluetooth device first", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, ControllerService::class.java))
        // Don't fake "connected" here — stateFlow will flip it once a real HID frame lands.
        serviceRunning = true
        log("Service started (Bluetooth)")
    }

    private fun checkAndRequestUsb() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == UsbConnectionManager.STEAM_VID
        }

        if (device == null) {
            log("No Steam Controller found — plug it in first")
            Toast.makeText(this, "No Steam Controller detected", Toast.LENGTH_LONG).show()
            return
        }

        if (usbManager.hasPermission(device)) {
            startControllerService(device)
        } else {
            val permIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(usbPermissionAction),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permIntent)
            log("Requesting USB permission...")
        }
    }

    private fun onDeviceAttached(device: UsbDevice) {
        if (device.vendorId != UsbConnectionManager.STEAM_VID) return
        log("Steam Controller attached")
        checkPermissionsAndStart()
    }

    private fun startControllerService(device: UsbDevice) {
        val intent = Intent(this, ControllerService::class.java).apply {
            putExtra(ControllerService.EXTRA_DEVICE, device)
        }
        startForegroundService(intent)
        serviceRunning = true
        log("Service started")
    }

    private fun stopControllerService() {
        val intent = Intent(this, ControllerService::class.java).apply {
            action = ControllerService.ACTION_STOP
        }
        startService(intent)
        serviceRunning = false
        hasSeenActiveMode = false
        log("Service stopped")
    }

    private fun log(msg: String) = Log.d(TAG, msg)

    override fun onResume() {
        super.onResume()
        shizukuReady = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        // Profile may have changed from the notification while paused.
        profile = Prefs.getProfile(this)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }
}
