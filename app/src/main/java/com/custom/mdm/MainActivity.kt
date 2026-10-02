package com.custom.mdm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val dbBaseUrl = "https://protection-v40pro-default-rtdb.firebaseio.com/devices/"
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: SharedPreferences
    private val syncHandler = Handler(Looper.getMainLooper())

    private lateinit var slotSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var kioskAppsLayout: LinearLayout

    private lateinit var chkBlockInstall: CheckBox
    private lateinit var chkBlockUninstall: CheckBox
    private lateinit var chkBlockCamera: CheckBox
    private lateinit var chkBlockSensorsUsb: CheckBox
    private lateinit var chkBlockReset: CheckBox
    private lateinit var chkKioskMode: CheckBox

    private var isUpdatingUI = false
    private var currentSlot = "phone_1"
    private var firebaseSyncState = "Connecting..."

    private var isBlockInstall = true
    private var isBlockUninstall = true
    private var isBlockCamera = false
    private var isBlockSensorsUsb = false
    private var isBlockReset = true
    private var isKioskMode = false
    private var kioskAppsCsv = "com.whatsapp,com.android.dialer"

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            syncWithFirebase(false)
            syncHandler.postDelayed(this, 6000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MdmAdminReceiver::class.java)
        prefs = getSharedPreferences("MDM_V2_PREFS", Context.MODE_PRIVATE)

        loadLocalState()
        buildUserInterface()
        applyDeviceOwnerPolicies(false)

        syncWithFirebase(true)
        syncHandler.postDelayed(periodicSyncRunnable, 6000)
    }

    override fun onDestroy() {
        super.onDestroy()
        syncHandler.removeCallbacks(periodicSyncRunnable)
    }

    private fun loadLocalState() {
        currentSlot = prefs.getString("slot", "phone_1") ?: "phone_1"
        isBlockInstall = prefs.getBoolean("blockInstall", true)
        isBlockUninstall = prefs.getBoolean("blockUninstall", true)
        isBlockCamera = prefs.getBoolean("disableCamera", false)
        isBlockSensorsUsb = prefs.getBoolean("blockScreenshots", false)
        isBlockReset = prefs.getBoolean("blockFactoryReset", true)
        isKioskMode = prefs.getBoolean("kioskMode", false)
        kioskAppsCsv = prefs.getString("kioskApps", "com.whatsapp,com.android.dialer") ?: "com.whatsapp"
    }

    private fun saveLocalState() {
        prefs.edit()
            .putString("slot", currentSlot)
            .putBoolean("blockInstall", isBlockInstall)
            .putBoolean("blockUninstall", isBlockUninstall)
            .putBoolean("disableCamera", isBlockCamera)
            .putBoolean("blockScreenshots", isBlockSensorsUsb)
            .putBoolean("blockFactoryReset", isBlockReset)
            .putBoolean("kioskMode", isKioskMode)
            .putString("kioskApps", kioskAppsCsv)
            .apply()
    }

    private fun buildUserInterface() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF0B1120.toInt())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 55, 45, 65)
        }

        val title = TextView(this).apply {
            text = "🛡️ System Guard MDM & Kiosk v2.0"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
        }
        root.addView(title)

        val subTitle = TextView(this).apply {
            text = "Select Device Slot (For Web Dashboard):"
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 24, 0, 8)
        }
        root.addView(subTitle)

        val slots = arrayOf("phone_1", "phone_2", "phone_3", "phone_4")
        slotSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, slots)
            val idx = slots.indexOf(currentSlot)
            if (idx >= 0) setSelection(idx)
        }
        root.addView(slotSpinner)

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 20, 0, 20)
        }

        val bindBtn = Button(this).apply {
            text = "🔄 BIND & FORCE SYNC TO FIREBASE"
            setBackgroundColor(0xFF2563EB.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = btnParams
            setOnClickListener {
                currentSlot = slotSpinner.selectedItem.toString()
                saveLocalState()
                firebaseSyncState = "Syncing $currentSlot..."
                updateStatusUI()
                syncWithFirebase(true)
                Toast.makeText(this@MainActivity, "Syncing to $currentSlot...", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(bindBtn)

        statusText = TextView(this).apply {
            setTextColor(0xFF38BDF8.toInt())
            textSize = 14f
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xFF1E293B.toInt())
        }
        root.addView(statusText)

        val controlsHeader = TextView(this).apply {
            text = "⚙️ Direct On-Device Admin Controls:"
            setTextColor(0xFFFACC15.toInt())
            textSize = 17f
            setPadding(0, 32, 0, 12)
        }
        root.addView(controlsHeader)

        chkBlockInstall = createSwitch(root, "🚫 Prevent App Installation (Play Store & APK)", isBlockInstall) {
            isBlockInstall = it
            onControlToggled()
        }

        chkBlockUninstall = createSwitch(root, "🔒 Prevent App Uninstallation (Lock All Apps)", isBlockUninstall) {
            isBlockUninstall = it
            onControlToggled()
        }

        chkBlockCamera = createSwitch(root, "📷 Block Camera Sensors (Disable All Cameras)", isBlockCamera) {
            isBlockCamera = it
            onControlToggled()
        }

        chkBlockSensorsUsb = createSwitch(root, "🛡️ Block Screen Capture & USB Data Transfer", isBlockSensorsUsb) {
            isBlockSensorsUsb = it
            onControlToggled()
        }

        chkBlockReset = createSwitch(root, "🛑 Block Factory Reset & Safe Mode Boot", isBlockReset) {
            isBlockReset = it
            onControlToggled()
        }

        chkKioskMode = createSwitch(root, "📌 Enable Strict Kiosk Mode (Pin Allowed Apps)", isKioskMode) {
            isKioskMode = it
            onControlToggled()
        }

        val batteryBtn = Button(this).apply {
            text = "⚡ GRANT BATTERY EXEMPTION (VIVO / OPPO)"
            setBackgroundColor(0xFF10B981.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = btnParams
            setOnClickListener { requestBatteryExemption() }
        }
        root.addView(batteryBtn)

        val kioskHeader = TextView(this).apply {
            text = "📱 Allowed Kiosk Applications:"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 16f
            setPadding(0, 20, 0, 12)
        }
        root.addView(kioskHeader)

        kioskAppsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(kioskAppsLayout)

        scrollView.addView(root)
        setContentView(scrollView)
        updateStatusUI()
    }

    private fun createSwitch(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        onChanged: (Boolean) -> Unit
    ): CheckBox {
        val cb = CheckBox(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(16, 20, 16, 20)
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUI) onChanged(isChecked)
            }
        }
        parent.addView(cb)
        return cb
    }

    private fun onControlToggled() {
        saveLocalState()
        applyDeviceOwnerPolicies(false)
        updateStatusUI()
        syncWithFirebase(true)
        Toast.makeText(this, "Restriction Applied Immediately ✅", Toast.LENGTH_SHORT).show()
    }

    private fun syncWithFirebase(pushLocalSwitches: Boolean) {
        val slot = currentSlot
        Thread {
            try {
                val url = URL("$dbBaseUrl$slot.json")
                val getConn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                }

                val code = getConn.responseCode
                if (code == 401 || code == 403) {
                    firebaseSyncState = "BLOCKED BY FIREBASE RULES (HTTP $code) ❌"
                    runOnUiThread { updateStatusUI() }
                    return@Thread
                }

                val stream = if (code in 200..299) getConn.inputStream else getConn.errorStream
                val rawJson = stream?.bufferedReader()?.use(BufferedReader::readText)?.trim() ?: ""
                getConn.disconnect()

                val node = if (rawJson.isEmpty() || rawJson == "null") JSONObject() else JSONObject(rawJson)
                val shouldRemoveAdmin = node.optBoolean("removeAdmin", false)

                if (pushLocalSwitches) {
                    node.put("blockInstall", isBlockInstall)
                    node.put("blockUninstall", isBlockUninstall)
                    node.put("disableCamera", isBlockCamera)
                    node.put("blockScreenshots", isBlockSensorsUsb)
                    node.put("blockFactoryReset", isBlockReset)
                    node.put("kioskMode", isKioskMode)
                    if (!node.has("kioskApps")) node.put("kioskApps", kioskAppsCsv)
                } else {
                    if (node.has("blockInstall")) isBlockInstall = node.optBoolean("blockInstall", isBlockInstall)
                    if (node.has("blockUninstall")) isBlockUninstall = node.optBoolean("blockUninstall", isBlockUninstall)
                    if (node.has("disableCamera")) isBlockCamera = node.optBoolean("disableCamera", isBlockCamera)
                    if (node.has("blockScreenshots")) isBlockSensorsUsb = node.optBoolean("blockScreenshots", isBlockSensorsUsb)
                    if (node.has("blockFactoryReset")) isBlockReset = node.optBoolean("blockFactoryReset", isBlockReset)
                    if (node.has("kioskMode")) isKioskMode = node.optBoolean("kioskMode", isKioskMode)
                    if (node.has("kioskApps")) kioskAppsCsv = node.optString("kioskApps", kioskAppsCsv)
                    saveLocalState()
                }

                val isOwner = dpm.isDeviceOwnerApp(packageName)
                var batLevel = -1
                var charging = false
                val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryStatus != null) {
                    val l = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val s = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (l >= 0 && s > 0) batLevel = ((l.toFloat() / s.toFloat()) * 100).toInt()
                    val st = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
                }

                val timeNow = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())

                val telemetry = JSONObject().apply {
                    put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("isDeviceOwner", isOwner)
                    put("level", batLevel)
                    put("charging", charging)
                    put("lastSeen", timeNow)
                }

                node.put("status", if (isOwner) "Online & Protected 🛡️️ (v2.0)" else "Online (Not Device Owner)")
                node.put("telemetry", telemetry)
                if (shouldRemoveAdmin) node.put("removeAdmin", false)

                val putConn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    doOutput = true
                    connectTimeout = 7000
                    readTimeout = 7000
                }

                putConn.outputStream.use { os ->
                    os.write(node.toString().toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }

                val putCode = putConn.responseCode
                putConn.disconnect()

                firebaseSyncState = if (putCode in 200..299) {
                    "LIVE & SYNCED ✅ ($timeNow)"
                } else {
                    "WRITE ERROR (HTTP $putCode) ❌"
                }

                runOnUiThread {
                    applyDeviceOwnerPolicies(shouldRemoveAdmin)
                    updateStatusUI()
                }
            } catch (e: Exception) {
                firebaseSyncState = "NET ERROR: ${e.javaClass.simpleName} ❌"
                runOnUiThread { updateStatusUI() }
            }
        }.start()
    }

    private fun applyDeviceOwnerPolicies(shouldRemoveAdmin: Boolean) {
        if (!dpm.isDeviceOwnerApp(packageName)) return

        try {
            if (shouldRemoveAdmin) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
                dpm.setCameraDisabled(adminComponent, false)
                dpm.setScreenCaptureDisabled(adminComponent, false)
                dpm.setUninstallBlocked(adminComponent, packageName, false)
                stopLockTask()
                dpm.clearDeviceOwnerApp(packageName)
                return
            }

            if (isBlockInstall) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }

            if (isBlockUninstall) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.setUninstallBlocked(adminComponent, packageName, true)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.setUninstallBlocked(adminComponent, packageName, false)
            }

            dpm.setCameraDisabled(adminComponent, isBlockCamera)
            dpm.setScreenCaptureDisabled(adminComponent, isBlockSensorsUsb)

            if (isBlockSensorsUsb) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            }

            if (isBlockReset) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            }

            val pkgList = mutableListOf(packageName)
            kioskAppsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pkgList.add(it) }
            dpm.setLockTaskPackages(adminComponent, pkgList.toTypedArray())

            if (isKioskMode) {
                startLockTask()
            } else {
                stopLockTask()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateStatusUI() {
        isUpdatingUI = true
        if (::chkBlockInstall.isInitialized) chkBlockInstall.isChecked = isBlockInstall
        if (::chkBlockUninstall.isInitialized) chkBlockUninstall.isChecked = isBlockUninstall
        if (::chkBlockCamera.isInitialized) chkBlockCamera.isChecked = isBlockCamera
        if (::chkBlockSensorsUsb.isInitialized) chkBlockSensorsUsb.isChecked = isBlockSensorsUsb
        if (::chkBlockReset.isInitialized) chkBlockReset.isChecked = isBlockReset
        if (::chkKioskMode.isInitialized) chkKioskMode.isChecked = isKioskMode
        isUpdatingUI = false

        val isOwner = dpm.isDeviceOwnerApp(packageName)
        statusText.text = "• Device Slot: $currentSlot\n" +
                "• Cloud Sync: $firebaseSyncState\n" +
                "• Device Owner Active: ${if (isOwner) "YES ✅" else "NO ❌"}\n" +
                "• App Installs: ${if (isBlockInstall) "BLOCKED 🔒" else "ALLOWED 🔓"}\n" +
                "• App Uninstall: ${if (isBlockUninstall) "BLOCKED 🔒" else "ALLOWED 🔓"}\n" +
                "• Camera Sensors: ${if (isBlockCamera) "DISABLED 📷" else "ENABLED"}\n" +
                "• Factory Reset: ${if (isBlockReset) "BLOCKED 🛑" else "ALLOWED"}"

        kioskAppsLayout.removeAllViews()
        for (pkg in kioskAppsCsv.split(",")) {
            val cleanPkg = pkg.trim()
            if (cleanPkg.isEmpty()) continue
            val b = Button(this).apply {
                text = "OPEN: ${cleanPkg.uppercase(Locale.getDefault())}"
                setBackgroundColor(0xFF1E293B.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    val launch = packageManager.getLaunchIntentForPackage(cleanPkg)
                    if (launch != null) startActivity(launch)
                    else Toast.makeText(this@MainActivity, "App not installed: $cleanPkg", Toast.LENGTH_SHORT).show()
                }
            }
            kioskAppsLayout.addView(b)
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery Exemption Already Granted ✅", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
