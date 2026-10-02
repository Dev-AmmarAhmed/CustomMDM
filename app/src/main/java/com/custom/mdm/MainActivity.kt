package com.custom.mdm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
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

class MainActivity : Activity() {

    private val dbBaseUrl = "https://protection-v40pro-default-rtdb.firebaseio.com/devices/"
    private val secretPin = "7302@123"

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var adminContainer: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var unlockBtn: Button
    private lateinit var slotSpinner: Spinner

    // All MDM Switches
    private lateinit var swInstall: Switch
    private lateinit var swUninstall: Switch
    private lateinit var swReset: Switch
    private lateinit var swStatusBar: Switch
    private lateinit var swDevMode: Switch
    private lateinit var swCamera: Switch
    private lateinit var swScreenCapture: Switch
    private lateinit var swUsbData: Switch
    private lateinit var swLocation: Switch
    private lateinit var swAccounts: Switch
    private lateinit var swNetworkReset: Switch

    private var isAdminUnlocked = false
    private var isUpdatingUI = false
    private var currentSlot = "phone_1"
    private var firebaseSyncState = "Connecting..."

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
        prefs = getSharedPreferences("MDM_V3_PREFS", Context.MODE_PRIVATE)

        buildDarkUI()
        applyDeviceOwnerPolicies(false)

        syncWithFirebase(true)
        mainHandler.postDelayed(periodicSyncRunnable, 10000)
    }

    private fun buildDarkUI() {
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 60, 50, 80) }

        val title = TextView(this).apply { text = "🛡️ System Guard v3.0 (PRO)"; setTextColor(Color.parseColor("#FFFFFF")); textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD) }
        root.addView(title)

        statusText = TextView(this).apply { 
            setTextColor(Color.parseColor("#4ADE80")) // Light Green
            textSize = 15f; setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 40) }
        }
        root.addView(statusText)

        val pinLabel = TextView(this).apply { text = "🔐 Admin Authentication:"; setTextColor(Color.parseColor("#FBBF24")); textSize = 16f; setPadding(0, 20, 0, 10) }
        root.addView(pinLabel)

        pinInput = EditText(this).apply { 
            hint = "Enter Admin PIN..."; setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.parseColor("#FFFFFF")); setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(30, 30, 30, 30); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD 
        }
        root.addView(pinInput)

        unlockBtn = Button(this).apply {
            text = "🔓 UNLOCK CONTROLS"
            setBackgroundColor(Color.parseColor("#6366F1")); setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 30) }
            setOnClickListener { toggleAdminLock() }
        }
        root.addView(unlockBtn)

        adminContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(30, 30, 30, 30); setBackgroundColor(Color.parseColor("#18181B")) // Darker Gray
        }

        val slots = arrayOf("phone_1", "phone_2", "phone_3", "phone_4")
        currentSlot = prefs.getString("slot", "phone_1") ?: "phone_1"
        slotSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, slots)
            val idx = slots.indexOf(currentSlot); if (idx >= 0) setSelection(idx)
        }
        adminContainer.addView(slotSpinner)

        val bindBtn = Button(this).apply {
            text = "🔄 SYNC TO CLOUD"
            setBackgroundColor(Color.parseColor("#2563EB")); setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 40) }
            setOnClickListener {
                currentSlot = slotSpinner.selectedItem.toString()
                prefs.edit().putString("slot", currentSlot).apply()
                syncWithFirebase(true)
                Toast.makeText(this@MainActivity, "Sync Triggered!", Toast.LENGTH_SHORT).show()
            }
        }
        adminContainer.addView(bindBtn)

        // Generate All Toggles
        swInstall = createToggle(adminContainer, "🚫 Block App Installs", "blockInstall", true)
        swUninstall = createToggle(adminContainer, "🗑️ Block App Uninstalls", "blockUninstall", true)
        swReset = createToggle(adminContainer, "🛑 Block Factory Reset", "blockReset", true)
        swStatusBar = createToggle(adminContainer, "📵 Block Status Bar", "blockStatusBar", false)
        swDevMode = createToggle(adminContainer, "🛠️ Block Dev Mode & ADB", "blockDevMode", true)
        swCamera = createToggle(adminContainer, "📷 Disable Camera", "blockCamera", false)
        swScreenCapture = createToggle(adminContainer, "🛡️ Block Screenshots", "blockScreenCapture", false)
        swUsbData = createToggle(adminContainer, "🔌 Block USB Data", "blockUsbData", false)
        swLocation = createToggle(adminContainer, "📍 Block Location Config", "blockLocation", false)
        swAccounts = createToggle(adminContainer, "👤 Block Account Mod", "blockAccounts", false)
        swNetworkReset = createToggle(adminContainer, "🛜 Block Network Reset", "blockNetworkReset", false)

        root.addView(adminContainer)
        scrollView.addView(root)
        setContentView(scrollView)
        updateStatusUI()
    }

    private fun createToggle(parent: LinearLayout, label: String, prefKey: String, default: Boolean): Switch {
        val sw = Switch(this).apply {
            text = label; setTextColor(Color.parseColor("#E5E7EB")); textSize = 15f
            setPadding(20, 35, 20, 35)
            isChecked = prefs.getBoolean(prefKey, default)
            setOnCheckedChangeListener { _, isChecked -> 
                if (!isUpdatingUI) {
                    prefs.edit().putBoolean(prefKey, isChecked).apply()
                    onControlToggled()
                }
            }
        }
        parent.addView(sw)
        return sw
    }

    private fun toggleAdminLock() {
        if (isAdminUnlocked) {
            isAdminUnlocked = false; adminContainer.visibility = View.GONE; unlockBtn.text = "🔓 UNLOCK CONTROLS"
        } else {
            if (pinInput.text.toString().trim() == secretPin) {
                isAdminUnlocked = true; adminContainer.visibility = View.VISIBLE; unlockBtn.text = "🔒 LOCK CONTROLS"; pinInput.setText("")
            } else Toast.makeText(this, "❌ Wrong PIN!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onControlToggled() {
        lastToggleTime = System.currentTimeMillis()
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
                    node.put("blockInstall", swInstall.isChecked); node.put("blockUninstall", swUninstall.isChecked)
                    node.put("blockReset", swReset.isChecked); node.put("blockStatusBar", swStatusBar.isChecked)
                    node.put("blockDevMode", swDevMode.isChecked); node.put("blockCamera", swCamera.isChecked)
                    node.put("blockScreenCapture", swScreenCapture.isChecked); node.put("blockUsbData", swUsbData.isChecked)
                    node.put("blockLocation", swLocation.isChecked); node.put("blockAccounts", swAccounts.isChecked)
                    node.put("blockNetworkReset", swNetworkReset.isChecked)
                } else {
                    val newHash = rawJson.hashCode()
                    if (newHash == lastFetchedStateHash) { pushTelemetryOnly(url, node); return@Thread }
                    lastFetchedStateHash = newHash

                    if (System.currentTimeMillis() - lastToggleTime > 15000) {
                        runOnUiThread {
                            isUpdatingUI = true
                            if (node.has("blockInstall")) swInstall.isChecked = node.optBoolean("blockInstall")
                            if (node.has("blockUninstall")) swUninstall.isChecked = node.optBoolean("blockUninstall")
                            if (node.has("blockReset")) swReset.isChecked = node.optBoolean("blockReset")
                            if (node.has("blockStatusBar")) swStatusBar.isChecked = node.optBoolean("blockStatusBar")
                            if (node.has("blockDevMode")) swDevMode.isChecked = node.optBoolean("blockDevMode")
                            if (node.has("blockCamera")) swCamera.isChecked = node.optBoolean("blockCamera")
                            if (node.has("blockScreenCapture")) swScreenCapture.isChecked = node.optBoolean("blockScreenCapture")
                            if (node.has("blockUsbData")) swUsbData.isChecked = node.optBoolean("blockUsbData")
                            if (node.has("blockLocation")) swLocation.isChecked = node.optBoolean("blockLocation")
                            if (node.has("blockAccounts")) swAccounts.isChecked = node.optBoolean("blockAccounts")
                            if (node.has("blockNetworkReset")) swNetworkReset.isChecked = node.optBoolean("blockNetworkReset")
                            
                            prefs.edit()
                                .putBoolean("blockInstall", swInstall.isChecked).putBoolean("blockUninstall", swUninstall.isChecked)
                                .putBoolean("blockReset", swReset.isChecked).putBoolean("blockStatusBar", swStatusBar.isChecked)
                                .putBoolean("blockDevMode", swDevMode.isChecked).putBoolean("blockCamera", swCamera.isChecked)
                                .putBoolean("blockScreenCapture", swScreenCapture.isChecked).putBoolean("blockUsbData", swUsbData.isChecked)
                                .putBoolean("blockLocation", swLocation.isChecked).putBoolean("blockAccounts", swAccounts.isChecked)
                                .putBoolean("blockNetworkReset", swNetworkReset.isChecked).apply()
                            
                            isUpdatingUI = false
                            applyDeviceOwnerPolicies(false)
                        }
                    }
                }
                pushTelemetryOnly(url, node)
            } catch (e: Exception) {}
        }.start()
    }

    private fun pushTelemetryOnly(url: URL, node: JSONObject) {
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        node.put("status", if (isOwner) "Online 🟢 (v3.0 PRO)" else "Online 🟡 (Not Device Owner)")
        try {
            val putConn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; setRequestProperty("Content-Type", "application/json"); doOutput = true }
            putConn.outputStream.use { it.write(node.toString().toByteArray()) }
            if (putConn.responseCode in 200..299) firebaseSyncState = "LIVE ✅"
            putConn.disconnect()
            runOnUiThread { updateStatusUI() }
        } catch (e: Exception) {}
    }

    private fun getStatusString() = "📱 Slot: $currentSlot\n☁️ Sync: $firebaseSyncState\n👑 Device Owner: ${if (dpm.isDeviceOwnerApp(packageName)) "YES ✅" else "NO ❌"}"

    private fun applyDeviceOwnerPolicies(shouldRemoveAdmin: Boolean) {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        try {
            if (swInstall.isChecked) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            if (swUninstall.isChecked) { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS); dpm.setUninstallBlocked(adminComponent, packageName, true) } else { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS); dpm.setUninstallBlocked(adminComponent, packageName, false) }
            if (swReset.isChecked) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.setStatusBarDisabled(adminComponent, swStatusBar.isChecked)
            if (swDevMode.isChecked) { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES); dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT) } else { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES); dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT) }
            dpm.setCameraDisabled(adminComponent, swCamera.isChecked)
            dpm.setScreenCaptureDisabled(adminComponent, swScreenCapture.isChecked)
            if (swUsbData.isChecked) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
            if (swLocation.isChecked) { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_LOCATION); dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION) } else { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_LOCATION); dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION) }
            if (swAccounts.isChecked) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            if (swNetworkReset.isChecked) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET) else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET)
        } catch (e: Exception) {}
    }

    private fun updateStatusUI() {
        statusText.text = getStatusString()
    }
}
