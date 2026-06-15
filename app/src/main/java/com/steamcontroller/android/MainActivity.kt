package com.steamcontroller.android

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Filter
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
import com.steamcontroller.android.usb.UsbConnectionManager
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false
    private var pairedBtDevices: List<BluetoothDevice> = emptyList()

    private val usbPermissionAction = "com.steamcontroller.android.USB_PERMISSION"

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

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

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

        setupTransportDropdown()
        setupProfileDropdown()
        requestNotificationPermissionIfNeeded()

        binding.btnRefreshBt.setOnClickListener {
            log("Refreshing Bluetooth devices…")
            ensureBluetoothPermissionThenRefresh()
        }

        binding.btnHelp.setOnClickListener { showConnectionHelpDialog() }

        // Observe injection mode changes from the service.
        // Also detect the service being stopped externally (e.g. via the notification action)
        // and re-sync MainActivity's UI state so the button flips back to "Start".
        lifecycleScope.launch {
            ControllerService.modeFlow.collect { mode ->
                refreshModeLabel()

                if (mode == ControllerService.InjectionMode.NONE && serviceRunning) {
                    serviceRunning = false
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
                binding.dropdownProfile.setText(profile.displayName, false)
                refreshModeLabel()
            }
        }

        // Observe battery level from the controller (parsed from each state report)
        lifecycleScope.launch {
            ControllerService.batteryFlow.collect { pct ->
                binding.tvBattery.text = if (pct == null) "Battery: —" else "Battery: $pct%"
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
        val labels = pairedBtDevices.map { dev ->
            val name = try { dev.name } catch (_: SecurityException) { null } ?: "Unknown"
            "$name  •  ${dev.address}"
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

    private fun setupProfileDropdown() {
        val profiles = GamepadProfile.values()
        val names = profiles.map { it.displayName }

        binding.dropdownProfile.setAdapter(nonFilteringAdapter(names))
        binding.dropdownProfile.threshold = 0

        val current = Prefs.getProfile(this)
        binding.dropdownProfile.setText(current.displayName, false)

        binding.dropdownProfile.setOnItemClickListener { _, _, position, _ ->
            val picked = profiles[position]
            Prefs.setProfile(this, picked)
            log("Profile set: ${picked.displayName}")
            if (serviceRunning) {
                Toast.makeText(this, "Restart the service to apply", Toast.LENGTH_SHORT).show()
            }
        }
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
        updateStatus(connected = true)
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
        updateStatus(connected = true)
        binding.btnToggleService.text = getString(R.string.btn_stop)
        log("Service started")
    }

    private fun stopControllerService() {
        val intent = Intent(this, ControllerService::class.java).apply {
            action = ControllerService.ACTION_STOP
        }
        startService(intent)
        serviceRunning = false
        updateStatus(connected = false)
        binding.btnToggleService.text = getString(R.string.btn_start)
        log("Service stopped")
    }

    private fun updateShizukuStatus(ok: Boolean) {
        binding.tvShizukuStatus.text = if (ok) "Shizuku: ready" else "Shizuku: not ready"
    }

    private fun updateStatus(connected: Boolean) {
        binding.tvControllerStatus.text = if (connected) "Controller: active" else "Controller: disconnected"
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

        // Sync the profile dropdown — it may have been changed from the notification while paused
        binding.dropdownProfile.setText(Prefs.getProfile(this).displayName, false)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }
}
