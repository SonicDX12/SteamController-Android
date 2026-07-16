package com.steamcontroller.android

import android.app.DownloadManager
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
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.databinding.ActivityMainBinding
import com.steamcontroller.android.service.ControllerService
import com.steamcontroller.android.uinput.GamepadProfile
import com.steamcontroller.android.update.UpdateChecker
import com.steamcontroller.android.update.UpdateInstaller
import com.steamcontroller.android.usb.UsbConnectionManager
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false
    private var pairedBtDevices: List<BluetoothDevice> = emptyList()
    // Tracks whether the current "started" session has reached a working injection mode.
    // Used so the modeFlow observer doesn't mistake StateFlow's initial NONE replay for
    // an external service stop right after the user pressed Start.
    private var hasSeenActiveMode = false

    private val usbPermissionAction = "com.steamcontroller.android.USB_PERMISSION"
    private val githubRepoUrl = "https://github.com/SonicDX12/SteamController-Android"

    private var pendingUpdateDownloadId: Long = -1L
    private var pendingUpdateApkFile: File? = null

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
                    updateStatus(connected = false)
                    log("Controller disconnected")
                }
            }
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L || id != pendingUpdateDownloadId) return
            val apkFile = pendingUpdateApkFile ?: return
            pendingUpdateDownloadId = -1L
            pendingUpdateApkFile = null
            UpdateInstaller.install(this@MainActivity, apkFile)
        }
    }

    private val shizukuRequestCode = 1001

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted")
            updateShizukuStatus(true)
            checkAndRequestUsb()
        } else {
            log("Shizuku permission denied")
            Toast.makeText(this, getString(R.string.shizuku_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSubtitle.text = "${binding.tvSubtitle.text} · v${BuildConfig.VERSION_NAME}"

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        binding.btnToggleService.setOnClickListener {
            if (serviceRunning) {
                stopControllerService()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        binding.btnCalibration.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.btnMapping.setOnClickListener {
            startActivity(Intent(this, MappingActivity::class.java))
        }

        binding.btnGameProfiles.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        setupTransportDropdown()
        setupControlModeToggle()
        setupGamepadVariantRadios()
        requestNotificationPermissionIfNeeded()

        binding.btnRefreshBt.setOnClickListener {
            log("Refreshing Bluetooth devices…")
            ensureBluetoothPermissionThenRefresh()
        }

        binding.btnHelp.setOnClickListener { showConnectionHelpDialog() }
        binding.btnGithub.setOnClickListener { openGithubRepo() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }

        maybeAutoCheckForUpdates()

        // Observe injection mode changes from the service.
        // Also detect the service being stopped externally (e.g. via the notification action)
        // and re-sync MainActivity's UI state so the button flips back to "Start".
        //
        // Subtlety: a fresh `collect` on a StateFlow immediately replays its current value,
        // which is `NONE` when no service ever ran. If the user taps Start *before* that
        // initial replay runs on the Main thread, we'd see `mode==NONE && serviceRunning==true`
        // and incorrectly reset the button back to "Start". `hasSeenActiveMode` defends
        // against that — we only treat a NONE as "service stopped" once we've previously
        // observed a working mode in this session.
        lifecycleScope.launch {
            ControllerService.modeFlow.collect { mode ->
                refreshModeLabel()

                if (mode != ControllerService.InjectionMode.NONE) {
                    hasSeenActiveMode = true
                    // Catch-up sync: activity re-entered while service was already running.
                    // Without this, the toggle button stays "Start" even though the service is live.
                    if (!serviceRunning) {
                        serviceRunning = true
                        binding.btnToggleService.text = getString(R.string.btn_stop)
                    }
                } else if (serviceRunning && hasSeenActiveMode) {
                    serviceRunning = false
                    hasSeenActiveMode = false
                    updateStatus(connected = false)
                    binding.btnToggleService.text = getString(R.string.btn_start)
                    log("Service stopped")
                }
            }
        }

        // Observe profile changes — e.g. when the user cycles via the notification action.
        // We need a dedicated flow because modeFlow doesn't re-emit when the profile changes within UINPUT.
        lifecycleScope.launch {
            ControllerService.profileFlow.collect { profileId ->
                if (profileId == null) return@collect
                val profile = com.steamcontroller.android.uinput.GamepadProfile.fromId(profileId)
                syncControlModeToggle(profile)
                refreshModeLabel()
            }
        }

        // Observe battery level from the controller (parsed from each state report)
        lifecycleScope.launch {
            ControllerService.batteryFlow.collect { pct ->
                binding.tvBattery.text = if (pct == null) "Battery: —" else "Battery: $pct%"
            }
        }

        // Observe real connection state — `stateFlow` only carries a non-null value
        // once at least one HID frame has been parsed from the controller. That's the
        // signal we trust for "controller actually plugged in / paired and streaming".
        lifecycleScope.launch {
            ControllerService.stateFlow.collect { state ->
                updateStatus(connected = state != null)
            }
        }

        // Handle intent if launched by USB attach event
        intent?.let { handleIntent(it) }
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

    private fun setupTransportDropdown() {
        // Reflect the saved transport in the toggle group
        val current = Prefs.getTransport(this)
        val initialButtonId = when (current) {
            Transport.USB       -> R.id.btnTransportUsb
            Transport.BLUETOOTH -> R.id.btnTransportBt
        }
        binding.toggleTransport.check(initialButtonId)
        updateBtPickerVisibility(current)

        binding.toggleTransport.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener  // only react when something is selected
            val picked = when (checkedId) {
                R.id.btnTransportUsb -> Transport.USB
                R.id.btnTransportBt  -> Transport.BLUETOOTH
                else -> return@addOnButtonCheckedListener
            }
            if (picked == Prefs.getTransport(this)) return@addOnButtonCheckedListener  // no-op
            Prefs.setTransport(this, picked)
            updateBtPickerVisibility(picked)
            // Status pill shows the active transport — refresh on change
            updateShizukuStatus(
                Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
            log("Transport set: ${picked.displayName}")
            if (serviceRunning) {
                Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBtPickerVisibility(t: Transport) {
        if (t == Transport.BLUETOOTH) {
            binding.btDeviceRow.visibility = View.VISIBLE
            ensureBluetoothPermissionThenRefresh()
        } else {
            binding.btDeviceRow.visibility = View.GONE
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
            binding.dropdownBtDevice.setAdapter(nonFilteringAdapter(listOf("No paired Steam Controller found")))
            binding.dropdownBtDevice.setText("No paired Steam Controller found", false)
            log("No Steam Controller paired — pair via Android Bluetooth settings first")
            return
        }
        // Show just the friendly name — the MAC address took an extra wrapped line
        // and the user never types it manually. Address is still saved to Prefs.
        val labels = pairedBtDevices.map { dev ->
            try { dev.name } catch (_: SecurityException) { null } ?: "Unknown"
        }
        binding.dropdownBtDevice.setAdapter(nonFilteringAdapter(labels))
        binding.dropdownBtDevice.threshold = 0

        val savedAddress = Prefs.getBluetoothAddress(this)
        val currentIdx = pairedBtDevices.indexOfFirst { it.address == savedAddress }.coerceAtLeast(0)
        binding.dropdownBtDevice.setText(labels[currentIdx], false)
        Prefs.setBluetoothAddress(this, pairedBtDevices[currentIdx].address)

        binding.dropdownBtDevice.setOnItemClickListener { _, _, position, _ ->
            val picked = pairedBtDevices[position]
            Prefs.setBluetoothAddress(this, picked.address)
            log("BT device: ${picked.address}")
        }
    }

    /**
     * ArrayAdapter with a no-op Filter so every item is always shown when the dropdown opens,
     * regardless of the text already in the field. Works around an M3 quirk where filtering
     * is applied even after [MaterialAutoCompleteTextView.setSimpleItems].
     */
    private fun nonFilteringAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, items) {
            private val noFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults =
                    FilterResults().apply { values = items; count = items.size }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
            override fun getFilter(): Filter = noFilter
        }

    private fun refreshModeLabel() {
        val mode = ControllerService.modeFlow.value
        binding.tvMode.text = when (mode) {
            ControllerService.InjectionMode.UINPUT         -> "Mode: ${Prefs.getProfile(this).displayName} (uinput) ✓"
            ControllerService.InjectionMode.SHIZUKU_INJECT -> "Mode: Shizuku inject (limited)"
            ControllerService.InjectionMode.NONE           -> "Mode: —"
        }
    }

    private fun showConnectionHelpDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_connection_help, null)

        fun fillBullet(id: Int, html: String) {
            val row = view.findViewById<View>(id)
            val tv = row.findViewById<android.widget.TextView>(R.id.bulletText)
            tv.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }

        fillBullet(R.id.bulletPuckRight,
            "<b>Puck (right slot)</b> — hold <b>A + R1 + Steam</b>, chime + white LED.")
        fillBullet(R.id.bulletPuckLeft,
            "<b>Puck (left slot)</b> — hold <b>A + L1 + Steam</b>, chime + white LED.")
        fillBullet(R.id.bulletBluetooth,
            "<b>Bluetooth</b> — hold <b>B + R1 + Steam</b>, chime + blue LED.")
        fillBullet(R.id.bulletWiredOff,
            "Controller is <b>off</b> — plug it into the device. Chime + green LED.")
        fillBullet(R.id.bulletWiredOn,
            "Controller is <b>on</b> in another mode — hold <b>Steam</b> while plugging it in. Chime + green LED.")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    /**
     * Control Mode toggle (Gamepad ↔ Desktop). Replaces the 5-profile dropdown.
     * Desktop ⇔ GamepadProfile.MOUSE. Gamepad ⇔ the user's last-chosen gamepad
     * profile (defaults to Xbox 360 on first run). Fine-grained sub-choice
     * (Xbox 360 vs One vs DS4 vs DualSense) lands in the Profiles panel (Phase 2).
     */
    private fun setupControlModeToggle() {
        syncControlModeToggle(Prefs.getProfile(this))

        binding.toggleControlMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val picked = when (checkedId) {
                R.id.btnModeGamepad -> Prefs.getLastGamepadProfile(this)
                R.id.btnModeDesktop -> GamepadProfile.MOUSE
                else -> return@addOnButtonCheckedListener
            }
            if (picked.id == Prefs.getProfile(this).id) {
                // No profile change, but the user still clicked — re-sync visibility
                // in case the section was out of sync (e.g. service not running so
                // profileFlow won't re-emit).
                syncControlModeToggle(picked)
                return@addOnButtonCheckedListener
            }
            Prefs.setProfile(this, picked)
            // Drive visibility + radio sync directly so the gamepad-variant section
            // hides immediately when the user picks Desktop, even when no service
            // is running (profileFlow only emits with the service alive).
            syncControlModeToggle(picked)
            log("Control mode → ${picked.displayName}")
            if (serviceRunning) {
                Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Programmatically sync the Gamepad/Desktop toggle to the given profile (no re-emit feedback loop). */
    private fun syncControlModeToggle(profile: GamepadProfile) {
        val targetId = if (profile.isMouseMode) R.id.btnModeDesktop else R.id.btnModeGamepad
        if (binding.toggleControlMode.checkedButtonId != targetId) {
            binding.toggleControlMode.check(targetId)
        }
        // Show/hide the gamepad-variant radios and select the right one.
        binding.gamepadVariantSection?.visibility =
            if (profile.isMouseMode) View.GONE else View.VISIBLE
        if (!profile.isMouseMode) syncGamepadVariant(profile)
    }

    /**
     * Wires the 4 Xbox/PS radio buttons, split across two RadioGroups (2 per row)
     * since a single RadioGroup can't lay out a 2×2 grid — it only auto-manages
     * mutual exclusion among its own *direct* children (nested ViewGroups don't
     * count), so a flat 2-column arrangement forces two separate groups. Each
     * group still gets native exclusion + accessibility semantics ("radio button
     * 1 of 2") within its row; the two rows are cross-cleared manually so only
     * one of the 4 is ever checked at a time.
     */
    private fun setupGamepadVariantRadios() {
        val radioToProfile = mapOf(
            R.id.rbXbox360    to GamepadProfile.XBOX_360,
            R.id.rbXboxOne    to GamepadProfile.XBOX_ONE,
            R.id.rbDualShock4 to GamepadProfile.DUALSHOCK_4,
            R.id.rbDualSense  to GamepadProfile.DUALSENSE,
        )
        val row1 = binding.radioGroupGamepadRow1
        val row2 = binding.radioGroupGamepadRow2

        fun onRowChecked(checkedId: Int, otherRow: RadioGroup?) {
            val picked = radioToProfile[checkedId] ?: return
            otherRow?.clearCheck()
            if (picked.id == Prefs.getProfile(this).id) return
            Prefs.setProfile(this, picked)
            log("Emulated controller → ${picked.displayName}")
            if (serviceRunning) {
                Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
            }
        }

        row1?.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID) onRowChecked(checkedId, row2)
        }
        row2?.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID) onRowChecked(checkedId, row1)
        }
        // Initial check based on current pref.
        syncGamepadVariant(Prefs.getProfile(this).takeUnless { it.isMouseMode } ?: Prefs.getLastGamepadProfile(this))
    }

    /** Set the right radio to `checked = true` without triggering its listener side-effects. */
    private fun syncGamepadVariant(profile: GamepadProfile) {
        binding.rbXbox360?.isChecked    = (profile == GamepadProfile.XBOX_360)
        binding.rbXboxOne?.isChecked    = (profile == GamepadProfile.XBOX_ONE)
        binding.rbDualShock4?.isChecked = (profile == GamepadProfile.DUALSHOCK_4)
        binding.rbDualSense?.isChecked  = (profile == GamepadProfile.DUALSENSE)
    }

    private fun openGithubRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubRepoUrl)))
        } catch (t: Throwable) {
            Toast.makeText(this, "No browser app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeAutoCheckForUpdates() {
        val elapsed = System.currentTimeMillis() - Prefs.getLastUpdateCheckAt(this)
        if (elapsed < TimeUnit.HOURS.toMillis(24)) return
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        lifecycleScope.launch {
            Prefs.setLastUpdateCheckAt(this@MainActivity, System.currentTimeMillis())
            val release = UpdateChecker.fetchLatestRelease()
            if (release == null) {
                if (manual) {
                    Toast.makeText(this@MainActivity, R.string.update_toast_check_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!UpdateChecker.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_toast_up_to_date, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            if (!manual && Prefs.getSkippedUpdateVersion(this@MainActivity) == release.versionName) return@launch
            showUpdateAvailableDialog(release)
        }
    }

    private fun showUpdateAvailableDialog(release: UpdateChecker.ReleaseInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_dialog_title))
            .setMessage(release.notes.ifBlank { release.tagName })
            .setPositiveButton(R.string.update_dialog_button_update) { _, _ -> downloadAndInstall(release) }
            .setNeutralButton(R.string.update_dialog_button_skip) { _, _ ->
                Prefs.setSkippedUpdateVersion(this, release.versionName)
            }
            .setNegativeButton(R.string.update_dialog_button_later, null)
            .show()
    }

    private fun downloadAndInstall(release: UpdateChecker.ReleaseInfo) {
        if (!UpdateInstaller.canInstall(this)) {
            Toast.makeText(this, R.string.update_toast_grant_install_permission, Toast.LENGTH_LONG).show()
            UpdateInstaller.requestInstallPermission(this)
            return
        }
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Steam Controller ${release.versionName}")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, release.apkName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        pendingUpdateApkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), release.apkName)
        pendingUpdateDownloadId = downloadManager.enqueue(request)
        Toast.makeText(this, R.string.update_toast_downloading, Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissionsAndStart() {
        when {
            !Shizuku.pingBinder() -> {
                log("Shizuku not running")
                Toast.makeText(this, getString(R.string.shizuku_not_running), Toast.LENGTH_LONG).show()
            }
            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                Shizuku.requestPermission(shizukuRequestCode)
            }
            else -> {
                updateShizukuStatus(true)
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
        val intent = Intent(this, ControllerService::class.java)
        startForegroundService(intent)
        serviceRunning = true
        // Don't fake "connected" here — stateFlow will flip it once a real HID frame lands.
        binding.btnToggleService.text = getString(R.string.btn_stop)
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
        // Pill colour + status text flip when stateFlow emits the first parsed HID frame.
        binding.btnToggleService.text = getString(R.string.btn_stop)
        log("Service started")
    }

    private fun stopControllerService() {
        val intent = Intent(this, ControllerService::class.java).apply {
            action = ControllerService.ACTION_STOP
        }
        startService(intent)
        serviceRunning = false
        hasSeenActiveMode = false
        updateStatus(connected = false)
        binding.btnToggleService.text = getString(R.string.btn_start)
        log("Service stopped")
    }

    private fun updateShizukuStatus(ok: Boolean) {
        val transport = Prefs.getTransport(this).displayName
        binding.tvShizukuStatus.text = if (ok)
            "Shizuku: ready  •  $transport"
        else
            "Shizuku: not ready  •  $transport"
    }

    /**
     * Drives both the bottom-of-card "Controller: ..." label AND the top status pill colour.
     * The pill flips to a green tint as soon as HID frames are actually flowing, which is
     * a much more honest signal than "the user pressed Start".
     */
    private fun updateStatus(connected: Boolean) {
        binding.tvControllerStatus.text = if (connected) "Controller: Ready" else "Controller: disconnected"

        val containerColor = if (connected)
            ContextCompat.getColor(this, R.color.status_connected_container)
        else
            ContextCompat.getColor(this, R.color.status_idle_container)
        val textColor = if (connected)
            ContextCompat.getColor(this, R.color.status_connected_on_container)
        else
            ContextCompat.getColor(this, R.color.status_idle_on_container)

        // statusPillCard only exists in the phone layout; sw600dp/TV use a different layout.
        binding.statusPillCard?.setCardBackgroundColor(containerColor)
        binding.tvShizukuStatus.setTextColor(textColor)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val current = binding.tvLog.text.toString()
        val lines = current.lines().takeLast(9)
        binding.tvLog.text = (lines + msg).joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus(Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED)

        // Sync Control Mode toggle — profile may have changed from the notification while paused
        syncControlModeToggle(Prefs.getProfile(this))

        maybeAutoCheckForUpdates()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        unregisterReceiver(usbReceiver)
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }
}
