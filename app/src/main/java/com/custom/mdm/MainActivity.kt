package com.custom.mdm

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var statusText: TextView
    private lateinit var appsContainer: LinearLayout
    private lateinit var phoneSpinner: Spinner

    private val policyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUiAndKiosk()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 56)
        }

        val title = TextView(this).apply {
            text = "🛡️ System Guard MDM v2.0"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        container.addView(title)

        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)

        // Firebase Email/Pass Login Box for Device Sync
        val emailInput = EditText(this).apply {
            hint = "Firebase Admin Email"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setText(prefs.getString("auth_email", ""))
        }
        val passInput = EditText(this).apply {
            hint = "Firebase Admin Password"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("auth_pass", ""))
        }
        container.addView(emailInput)
        container.addView(passInput)

        val phones = arrayOf("phone_1", "phone_2", "phone_3", "phone_4")
        phoneSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, phones)
        }
        val savedPhone = prefs.getString("device_id", "phone_1") ?: "phone_1"
        phoneSpinner.setSelection(phones.indexOf(savedPhone).coerceAtLeast(0))
        container.addView(phoneSpinner)

        val saveSlotBtn = Button(this).apply {
            text = "🔐 Authenticate & Bind Phone Slot"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val selected = phoneSpinner.selectedItem.toString()
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString().trim()
                prefs.edit()
                    .putString("device_id", selected)
                    .putString("auth_email", email)
                    .putString("auth_pass", pass)
                    .apply()

                if (email.isNotEmpty() && pass.isNotEmpty()) {
                    FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                        .addOnSuccessListener {
                            Toast.makeText(this@MainActivity, "Auth Success! Bound to $selected", Toast.LENGTH_SHORT).show()
                            startMdmService()
                            refreshUiAndKiosk()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this@MainActivity, "Auth Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                } else {
                    startMdmService()
                    refreshUiAndKiosk()
                }
            }
        }
        container.addView(saveSlotBtn)

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 28, 0, 28)
        }
        container.addView(statusText)

        val batteryBtn = Button(this).apply {
            text = "⚡ 1. Grant Battery Exemption (Vivo/OriginOS)"
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            setOnClickListener { requestBatteryOptimizationBypass() }
        }
        container.addView(batteryBtn)

        val usageBtn = Button(this).apply {
            text = "📊 2. Grant App Usage Access (For Dashboard)"
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        val usageParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 }
        container.addView(usageBtn, usageParams)

        val kioskHeader = TextView(this).apply {
            text = "\n📱 Allowed Kiosk Applications:"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 12)
        }
        container.addView(kioskHeader)

        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(appsContainer)

        val emergencyBtn = Button(this).apply {
            text = "🔓 Emergency Offline PIN Unlock"
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            setOnClickListener { showEmergencyPinDialog() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 36 }
        container.addView(emergencyBtn, params)

        root.addView(container)
        setContentView(root)

        startMdmService()
        refreshUiAndKiosk()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.custom.mdm.POLICY_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(policyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(policyReceiver, filter)
        }
        refreshUiAndKiosk()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(policyReceiver)
        } catch (_: Exception) {}
    }

    private fun startMdmService() {
        val intent = Intent(this, MdmService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshUiAndKiosk() {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", "phone_1") ?: "phone_1"
        val kioskMode = prefs.getBoolean("kiosk_mode", false)
        val blockInstall = prefs.getBoolean("block_install", false)
        val blockUninstall = prefs.getBoolean("block_uninstall", false)
        val kioskApps = prefs.getString("kiosk_apps", "com.whatsapp,com.android.dialer") ?: ""
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        val user = FirebaseAuth.getInstance().currentUser

        statusText.text = """
            • Device Slot: $deviceId
            • Firebase Auth: ${if (user != null) "LOGGED IN (${user.email}) ✅" else "NOT LOGGED IN ⚠️"}
            • Device Owner Active: ${if (isOwner) "YES ✅" else "NO ❌ (Run ADB Command)"}
            • Block New App Installs: ${if (blockInstall) "LOCKED 🔒" else "UNLOCKED 🔓"}
            • Block App Uninstall: ${if (blockUninstall) "LOCKED 🔒" else "UNLOCKED 🔓"}
            • Kiosk Mode: ${if (kioskMode) "ACTIVE 🛡️" else "OFF"}
        """.trimIndent()

        if (isOwner) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isInLockTask = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            if (kioskMode && !isInLockTask) {
                try { startLockTask() } catch (_: Exception) {}
            } else if (!kioskMode && isInLockTask) {
                try { stopLockTask() } catch (_: Exception) {}
            }
        }

        appsContainer.removeAllViews()
        val pkgs = kioskApps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (pkg in pkgs) {
            val btn = Button(this).apply {
                text = "Launch: $pkg"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setOnClickListener {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) startActivity(launchIntent)
                    else Toast.makeText(this@MainActivity, "Not installed: $pkg", Toast.LENGTH_SHORT).show()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
            appsContainer.addView(btn, lp)
        }
    }

    private fun requestBatteryOptimizationBypass() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Battery Optimization Already Bypassed!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEmergencyPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter Master PIN (Default: 9999)"
        }
        AlertDialog.Builder(this)
            .setTitle("Emergency Offline Unlock")
            .setView(input)
            .setPositiveButton("Unlock Kiosk") { _, _ ->
                if (input.text.toString() == "9999") {
                    try { stopLockTask() } catch (_: Exception) {}
                    getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("kiosk_mode", false).apply()
                    refreshUiAndKiosk()
                    Toast.makeText(this, "Kiosk Exited Locally", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("kiosk_mode", false)) {
            Toast.makeText(this, "Back button disabled in Kiosk Mode", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }
}
