package com.custom.mdm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val dbBaseUrl = "https://protection-v40pro-default-rtdb.firebaseio.com/devices/"
    private val secretPin = "7302@123"
    private val kioskUnlockPin = "1234" // EMERGENCY KIOSK PIN

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var slotSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var adminContainer: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var unlockBtn: Button

    private lateinit var chkBlockInstall: CheckBox
    private lateinit var chkBlockUninstall: CheckBox
    private lateinit var chkBlockStatusBar: CheckBox
    private lateinit var chkBlockDevMode: CheckBox
    private lateinit var chkBlockCamera: CheckBox
    private lateinit var chkBlockSensorsUsb: CheckBox
    private lateinit var chkBlockReset: CheckBox
    private lateinit var chkKioskMode: CheckBox

    private var isAdminUnlocked = false
    private var isUpdatingUI = false
    private var currentSlot = "phone_1"
    private var firebaseSyncState = "Connecting..."

    private var isBlockInstall = true
    private var isBlockUninstall = true
    private var isBlockStatusBar = false
    private var isBlockDevMode = true
    private var isBlockCamera = false
    private var isBlockSensorsUsb = false
    private var isBlockReset = true
    private var isKioskMode = false
    private var kioskAppsCsv = "com.whatsapp,com.android.dialer"

    private var lastAppliedKioskState = false
    private var lastToggleTime = 0L
    private var lastFetchedStateHash = 0

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            syncWithFirebase(false)
            mainHandler.postDelayed(this, 10000)
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
        mainHandler.postDelayed(periodicSyncRunnable, 10000)
    }

    private fun loadLocalState() {
        currentSlot = prefs.getString("slot", "phone_1") ?: "phone_1"
        isBlockInstall = prefs.getBoolean("blockInstall", true)
        isBlockUninstall = prefs.getBoolean("blockUninstall", true)
        isBlockStatusBar = prefs.getBoolean("blockStatusBar", false)
        isBlockDevMode = prefs.getBoolean("blockDevMode", true)
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
            .putBoolean("blockStatusBar", isBlockStatusBar)
            .putBoolean("blockDevMode", isBlockDevMode)
            .putBoolean("disableCamera", isBlockCamera)
            .putBoolean("blockScreenshots", isBlockSensorsUsb)
            .putBoolean("blockFactoryReset", isBlockReset)
            .putBoolean("kioskMode", isKioskMode)
            .apply()
    }

    private fun buildUserInterface() {
        val scrollView = ScrollView(this).apply { setBackgroundColor(0xFF0B1120.toInt()) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 55, 45, 65) }

        val title = TextView(this).apply { text = "🛡️ System Guard MDM v2.5"; setTextColor(0xFFFFFFFF.toInt()); textSize = 20f }
        root.addView(title)

        statusText = TextView(this).apply { setTextColor(0xFF38BDF8.toInt()); textSize = 14f; setPadding(24, 20, 24, 20); setBackgroundColor(0xFF1E293B.toInt()) }
        val statusParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 24) }
        statusText.layoutParams = statusParams
        root.addView(statusText)

        // ================= EMERGENCY KIOSK UNLOCK UI =================
        val emergencyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 30, 30, 30); setBackgroundColor(0xFF450A0A.toInt()) }
        val emergencyLabel = TextView(this).apply { text = "🚨 KIOSK LOCKED? EMERGENCY UNLOCK"; setTextColor(0xFFFCA5A5.toInt()); textSize = 14f; setPadding(0, 0, 0, 10) }
        emergencyContainer.addView(emergencyLabel)

        val kioskPinInput = EditText(this).apply { hint = "Enter PIN (1234)..."; setHintTextColor(0xFF9CA3AF.toInt()); setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF1E293B.toInt()); setPadding(20, 20, 20, 20); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        emergencyContainer.addView(kioskPinInput)

        val btnUnlockKiosk = Button(this).apply {
            text = "🔓 FORCE UNLOCK KIOSK"
            setBackgroundColor(0xFFDC2626.toInt()); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 0) }
            setOnClickListener {
                if (kioskPinInput.text.toString() == kioskUnlockPin) {
                    isKioskMode = false
                    if (::chkKioskMode.isInitialized) chkKioskMode.isChecked = false
                    lastAppliedKioskState = false
                    try { stopLockTask() } catch(e: Exception) {}
                    saveLocalState()
                    syncWithFirebase(true)
                    kioskPinInput.setText("")
                    Toast.makeText(this@MainActivity, "✅ KIOSK STOPPED! Phone is normal now.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "❌ Wrong PIN!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        emergencyContainer.addView(btnUnlockKiosk)
        root.addView(emergencyContainer)
        // =============================================================

        val pinLabel = TextView(this).apply { text = "🔐 Enter Admin Key to Unlock Controls:"; setTextColor(0xFFFACC15.toInt()); textSize = 15f; setPadding(0, 40, 0, 0) }
        root.addView(pinLabel)

        pinInput = EditText(this).apply { hint = "Enter Hidden Admin Key..."; setHintTextColor(0xFF64748B.toInt()); setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF1E293B.toInt()); setPadding(24, 20, 24, 20); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(pinInput)

        unlockBtn = Button(this).apply {
            text = "🔓 UNLOCK ADMIN CONTROLS"
            setBackgroundColor(0xFF7C3AED.toInt()); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 20) }
            setOnClickListener {
                if (isAdminUnlocked) {
                    isAdminUnlocked = false
                    adminContainer.visibility = View.GONE
                    text = "🔓 UNLOCK ADMIN CONTROLS"
                } else {
                    if (pinInput.text.toString().trim() == secretPin) {
                        isAdminUnlocked = true
                        adminContainer.visibility = View.VISIBLE
                        text = "🔒 LOCK ADMIN CONTROLS"
                        pinInput.setText("")
                    } else Toast.makeText(this@MainActivity, "❌ Wrong Admin Key!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(unlockBtn)

        adminContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(20, 20, 20, 20); setBackgroundColor(0xFF111827.toInt()) }

        val slots = arrayOf("phone_1", "phone_2", "phone_3", "phone_4")
        slotSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, slots)
            val idx = slots.indexOf(currentSlot); if (idx >= 0) setSelection(idx)
        }
        adminContainer.addView(slotSpinner)

        val bindBtn = Button(this).apply {
            text = "🔄 BIND & FORCE SYNC TO FIREBASE"
            setBackgroundColor(0xFF2563EB.toInt()); setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                currentSlot = slotSpinner.selectedItem.toString()
                saveLocalState(); syncWithFirebase(true)
                Toast.makeText(this@MainActivity, "Force Sync Triggered!", Toast.LENGTH_SHORT).show()
            }
        }
        adminContainer.addView(bindBtn)

        chkBlockInstall = createSwitch(adminContainer, "🚫 Prevent App Installation", isBlockInstall) { isBlockInstall = it; onControlToggled() }
        chkBlockUninstall = createSwitch(adminContainer, "🔒 Prevent App Uninstallation", isBlockUninstall) { isBlockUninstall = it; onControlToggled() }
        chkBlockStatusBar = createSwitch(adminContainer, "📵 Block Notification Panel", isBlockStatusBar) { isBlockStatusBar = it; onControlToggled() }
        chkBlockDevMode = createSwitch(adminContainer, "🛠️ Block Developer Mode & ADB", isBlockDevMode) { isBlockDevMode = it; onControlToggled() }
        chkBlockCamera = createSwitch(adminContainer, "📷 Block Camera Sensors", isBlockCamera) { isBlockCamera = it; onControlToggled() }
        chkBlockSensorsUsb = createSwitch(adminContainer, "🛡️ Block Screenshots & USB Data", isBlockSensorsUsb) { isBlockSensorsUsb = it; onControlToggled() }
        chkBlockReset = createSwitch(adminContainer, "🛑 Block Factory Reset", isBlockReset) { isBlockReset = it; onControlToggled() }
        chkKioskMode = createSwitch(adminContainer, "📌 Strict Kiosk Mode", isKioskMode) { isKioskMode = it; onControlToggled() }

        root.addView(adminContainer)
        scrollView.addView(root)
        setContentView(scrollView)
        updateStatusUI()
    }

    private fun createSwitch(parent: LinearLayout, label: String, initial: Boolean, onChanged: (Boolean) -> Unit): CheckBox {
        val cb = CheckBox(this).apply {
            text = label; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; setPadding(16, 20, 16, 20); isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> if (!isUpdatingUI) onChanged(isChecked) }
        }
        parent.addView(cb)
        return cb
    }

    private fun onControlToggled() {
        lastToggleTime = System.currentTimeMillis()
        saveLocalState()
        applyDeviceOwnerPolicies(false)
        updateStatusUI()
        syncWithFirebase(true)
    }

    private fun syncWithFirebase(pushLocalSwitches: Boolean) {
        val slot = currentSlot
        Thread {
            try {
                val url = URL("$dbBaseUrl$slot.json")
                val getConn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
                val rawJson = getConn.inputStream.bufferedReader().use(BufferedReader::readText).trim()
                getConn.disconnect()
                val node = if (rawJson.isEmpty() || rawJson == "null") JSONObject() else JSONObject(rawJson)
                
                if (pushLocalSwitches) {
                    node.put("blockInstall", isBlockInstall); node.put("blockUninstall", isBlockUninstall)
                    node.put("blockStatusBar", isBlockStatusBar); node.put("blockDevMode", isBlockDevMode)
                    node.put("disableCamera", isBlockCamera); node.put("blockScreenshots", isBlockSensorsUsb)
                    node.put("blockFactoryReset", isBlockReset); node.put("kioskMode", isKioskMode)
                } else {
                    val newHash = rawJson.hashCode()
                    if (newHash == lastFetchedStateHash) { pushTelemetryOnly(url, node); return@Thread }
                    lastFetchedStateHash = newHash

                    if (System.currentTimeMillis() - lastToggleTime > 15000) {
                        if (node.has("blockInstall")) isBlockInstall = node.optBoolean("blockInstall", isBlockInstall)
                        if (node.has("blockUninstall")) isBlockUninstall = node.optBoolean("blockUninstall", isBlockUninstall)
                        if (node.has("blockStatusBar")) isBlockStatusBar = node.optBoolean("blockStatusBar", isBlockStatusBar)
                        if (node.has("blockDevMode")) isBlockDevMode = node.optBoolean("blockDevMode", isBlockDevMode)
                        if (node.has("disableCamera")) isBlockCamera = node.optBoolean("disableCamera", isBlockCamera)
                        if (node.has("blockScreenshots")) isBlockSensorsUsb = node.optBoolean("blockScreenshots", isBlockSensorsUsb)
                        if (node.has("blockFactoryReset")) isBlockReset = node.optBoolean("blockFactoryReset", isBlockReset)
                        if (node.has("kioskMode")) isKioskMode = node.optBoolean("kioskMode", isKioskMode)
                        saveLocalState()
                    }
                }

                val shouldRemoveAdmin = node.optBoolean("removeAdmin", false)
                pushTelemetryOnly(url, node)
                runOnUiThread { applyDeviceOwnerPolicies(shouldRemoveAdmin); updateStatusUI() }
            } catch (e: Exception) {}
        }.start()
    }

    private fun pushTelemetryOnly(url: URL, node: JSONObject) {
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        node.put("status", if (isOwner) "Online & Protected 🛡️ (v2.5)" else "Online (Not Device Owner)")
        try {
            val putConn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true }
            putConn.outputStream.use { it.write(node.toString().toByteArray()) }
            if (putConn.responseCode in 200..299) firebaseSyncState = "LIVE & SYNCED ✅"
            putConn.disconnect()
            runOnUiThread { statusText.text = statusText.text.toString().replaceRange(0, statusText.text.length, getStatusString()) }
        } catch (e: Exception) {}
    }

    private fun getStatusString() = "• Device Slot: $currentSlot\n• Cloud Sync: $firebaseSyncState\n• Device Owner: ${if (dpm.isDeviceOwnerApp(packageName)) "YES ✅" else "NO ❌"}"

    private fun applyDeviceOwnerPolicies(shouldRemoveAdmin: Boolean) {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        try {
            if (shouldRemoveAdmin) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.setStatusBarDisabled(adminComponent, false)
                try { stopLockTask() } catch (e: Exception) {}
                lastAppliedKioskState = false
                dpm.clearDeviceOwnerApp(packageName)
                return
            }

            if (isBlockInstall) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            if (isBlockUninstall) { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS); dpm.setUninstallBlocked(adminComponent, packageName, true) } else { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS); dpm.setUninstallBlocked(adminComponent, packageName, false) }
            dpm.setStatusBarDisabled(adminComponent, isBlockStatusBar)
            if (isBlockDevMode) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.setCameraDisabled(adminComponent, isBlockCamera)
            dpm.setScreenCaptureDisabled(adminComponent, isBlockSensorsUsb)
            if (isBlockReset) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)

            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (isKioskMode) dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                else dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            }

            if (isKioskMode) {
                if (!lastAppliedKioskState) { startLockTask(); lastAppliedKioskState = true }
            } else {
                try { stopLockTask() } catch (e: Exception) {}
                lastAppliedKioskState = false
            }
        } catch (e: Exception) {}
    }

    private fun updateStatusUI() {
        isUpdatingUI = true
        if (::chkBlockInstall.isInitialized) chkBlockInstall.isChecked = isBlockInstall
        if (::chkBlockUninstall.isInitialized) chkBlockUninstall.isChecked = isBlockUninstall
        if (::chkBlockStatusBar.isInitialized) chkBlockStatusBar.isChecked = isBlockStatusBar
        if (::chkBlockDevMode.isInitialized) chkBlockDevMode.isChecked = isBlockDevMode
        if (::chkBlockCamera.isInitialized) chkBlockCamera.isChecked = isBlockCamera
        if (::chkBlockSensorsUsb.isInitialized) chkBlockSensorsUsb.isChecked = isBlockSensorsUsb
        if (::chkBlockReset.isInitialized) chkBlockReset.isChecked = isBlockReset
        if (::chkKioskMode.isInitialized) chkKioskMode.isChecked = isKioskMode
        isUpdatingUI = false
        statusText.text = getStatusString()
    }
}
