package com.custom.mdm

import android.app.*
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class MdmService : Service() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var dbRef: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var currentDeviceId: String = "phone_1"
    private val handler = Handler(Looper.getMainLooper())

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            syncTelemetryToFirebase()
            handler.postDelayed(this, 45000) // Update every 45 seconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = MdmAdminReceiver.getComponentName(this)
        startMdmForeground()
        authenticateAndConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMdmForeground()
        authenticateAndConnect()
        return START_STICKY
    }

    private fun authenticateAndConnect() {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        currentDeviceId = prefs.getString("device_id", "phone_1") ?: "phone_1"
        val email = prefs.getString("auth_email", "") ?: ""
        val pass = prefs.getString("auth_pass", "") ?: ""

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            connectToFirebase()
        } else if (email.isNotEmpty() && pass.isNotEmpty()) {
            auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener { connectToFirebase() }
                .addOnFailureListener { connectToFirebase() }
        } else {
            connectToFirebase()
        }
    }

    private fun startMdmForeground() {
        val channelId = "mdm_guard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Guard Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Guard MDM Active")
            .setContentText("Monitoring policies for $currentDeviceId")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(101, notification)
        }
    }

    private fun connectToFirebase() {
        listener?.let { dbRef?.removeEventListener(it) }
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        currentDeviceId = prefs.getString("device_id", "phone_1") ?: "phone_1"

        dbRef = FirebaseDatabase.getInstance().getReference("devices").child(currentDeviceId)

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    syncTelemetryToFirebase()
                    return
                }

                val blockInstall = snapshot.child("blockInstall").getValue(Boolean::class.java) ?: false
                val blockUninstall = snapshot.child("blockUninstall").getValue(Boolean::class.java) ?: false
                val kioskMode = snapshot.child("kioskMode").getValue(Boolean::class.java) ?: false
                val kioskApps = snapshot.child("kioskApps").getValue(String::class.java) ?: "com.whatsapp,com.android.dialer"
                val disableCamera = snapshot.child("disableCamera").getValue(Boolean::class.java) ?: false
                val blockScreenshots = snapshot.child("blockScreenshots").getValue(Boolean::class.java) ?: false
                val blockUsb = snapshot.child("blockUsb").getValue(Boolean::class.java) ?: false
                val blockFactoryReset = snapshot.child("blockFactoryReset").getValue(Boolean::class.java) ?: true
                val suspendedApps = snapshot.child("suspendedApps").getValue(String::class.java) ?: ""
                val lockScreenNow = snapshot.child("lockScreenNow").getValue(Boolean::class.java) ?: false
                val refreshNow = snapshot.child("refreshNow").getValue(Boolean::class.java) ?: false
                val removeAdmin = snapshot.child("removeAdmin").getValue(Boolean::class.java) ?: false

                if (refreshNow) {
                    dbRef?.child("refreshNow")?.setValue(false)
                    syncTelemetryToFirebase()
                }

                applyPolicies(
                    blockInstall, blockUninstall, kioskMode, kioskApps,
                    disableCamera, blockScreenshots, blockUsb, blockFactoryReset,
                    suspendedApps, lockScreenNow, removeAdmin
                )
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        dbRef?.addValueEventListener(listener!!)
        handler.removeCallbacks(telemetryRunnable)
        handler.post(telemetryRunnable)
    }

    private fun applyPolicies(
        blockInstall: Boolean,
        blockUninstall: Boolean,
        kioskMode: Boolean,
        kioskApps: String,
        disableCamera: Boolean,
        blockScreenshots: Boolean,
        blockUsb: Boolean,
        blockFactoryReset: Boolean,
        suspendedApps: String,
        lockScreenNow: Boolean,
        removeAdmin: Boolean
    ) {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("kiosk_mode", kioskMode)
            .putString("kiosk_apps", kioskApps)
            .putBoolean("block_install", blockInstall)
            .putBoolean("block_uninstall", blockUninstall)
            .apply()

        if (!dpm.isDeviceOwnerApp(packageName)) {
            updateStatus("Connected (Run ADB Device Owner Command)")
            sendBroadcast(Intent("com.custom.mdm.POLICY_UPDATED"))
            return
        }

        try {
            if (removeAdmin) {
                val restrictions = listOf(
                    UserManager.DISALLOW_INSTALL_APPS,
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                    UserManager.DISALLOW_UNINSTALL_APPS,
                    UserManager.DISALLOW_FACTORY_RESET,
                    UserManager.DISALLOW_SAFE_BOOT,
                    UserManager.DISALLOW_USB_FILE_TRANSFER
                )
                restrictions.forEach { dpm.clearUserRestriction(adminComponent, it) }
                dpm.setCameraDisabled(adminComponent, false)
                dpm.setScreenCaptureDisabled(adminComponent, false)
                dpm.setUninstallBlocked(adminComponent, packageName, false)
                dpm.setLockTaskPackages(adminComponent, emptyArray())

                dbRef?.child("removeAdmin")?.setValue(false)
                updateStatus("Device Owner Removed - Ready to Uninstall")

                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(packageName)
                sendBroadcast(Intent("com.custom.mdm.POLICY_UPDATED"))
                return
            }

            // 1. App Installations & Sideloading
            if (blockInstall) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }

            // 2. App Uninstall & Self-Protection
            if (blockUninstall) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.setUninstallBlocked(adminComponent, packageName, true)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.setUninstallBlocked(adminComponent, packageName, false)
            }

            // 3. Factory Reset & Safe Mode Protection
            if (blockFactoryReset) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            }

            // 4. Hardware & Privacy Controls (Camera, Screenshots, USB)
            dpm.setCameraDisabled(adminComponent, disableCamera)
            dpm.setScreenCaptureDisabled(adminComponent, blockScreenshots)
            if (blockUsb) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
            }

            // 5. Per-App Suspend / Unsuspend
            val previouslySuspended = prefs.getString("prev_suspended", "") ?: ""
            val oldSet = previouslySuspended.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val newSet = suspendedApps.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != packageName }.toSet()

            val toUnsuspend = (oldSet - newSet).toTypedArray()
            if (toUnsuspend.isNotEmpty()) {
                dpm.setPackagesSuspended(adminComponent, toUnsuspend, false)
            }
            if (newSet.isNotEmpty()) {
                dpm.setPackagesSuspended(adminComponent, newSet.toTypedArray(), true)
            }
            prefs.edit().putString("prev_suspended", newSet.joinToString(",")).apply()

            // 6. Remote Instant Lock Screen
            if (lockScreenNow) {
                dbRef?.child("lockScreenNow")?.setValue(false)
                dpm.lockNow()
            }

            // 7. Kiosk Mode Whitelist
            val allowedList = kioskApps.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            if (!allowedList.contains(packageName)) {
                allowedList.add(packageName)
            }

            if (kioskMode) {
                dpm.setLockTaskPackages(adminComponent, allowedList.toTypedArray())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(
                        adminComponent,
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                    )
                }
                val lockIntent = Intent(this, MainActivity::class.java)
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(lockIntent)
            } else {
                dpm.setLockTaskPackages(adminComponent, emptyArray())
            }

            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            updateStatus("Online | Owner:YES | Synced at $time")
            sendBroadcast(Intent("com.custom.mdm.POLICY_UPDATED"))

        } catch (e: Exception) {
            updateStatus("Error: ${e.message}")
        }
    }

    private fun syncTelemetryToFirebase() {
        val ref = dbRef ?: return
        try {
            // 1. Battery Status
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging
            val batteryMap = mapOf(
                "level" to level,
                "charging" to isCharging,
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "isDeviceOwner" to dpm.isDeviceOwnerApp(packageName),
                "lastSeen" to SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
            )
            ref.child("telemetry").setValue(batteryMap)

            // 2. Installed User Apps List
            val pm = packageManager
            val installedList = mutableListOf<Map<String, String>>()
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val hasLaunch = pm.getLaunchIntentForPackage(app.packageName) != null
                if (!isSystem || hasLaunch) {
                    val label = pm.getApplicationLabel(app).toString()
                    installedList.add(
                        mapOf(
                            "name" to label,
                            "pkg" to app.packageName,
                            "type" to if (isSystem) "System" else "User"
                        )
                    )
                }
            }
            installedList.sortBy { it["name"]?.lowercase() }
            ref.child("installedApps").setValue(installedList.take(120))

            // 3. App Usage Stats (Last 24 Hours)
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (1000 * 60 * 60 * 24)
            val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            val usageList = usageStats
                ?.filter { it.totalTimeInForeground > 60000 }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.take(20)
                ?.map {
                    val mins = (it.totalTimeInForeground / 60000).toInt()
                    val appName = try {
                        val ai = pm.getApplicationInfo(it.packageName, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        it.packageName
                    }
                    mapOf(
                        "pkg" to it.packageName,
                        "name" to appName,
                        "minutes" to mins
                    )
                } ?: emptyList()

            ref.child("appUsage").setValue(usageList)

        } catch (_: Exception) {}
    }

    private fun updateStatus(msg: String) {
        dbRef?.child("status")?.setValue(msg)
    }

    override fun onDestroy() {
        handler.removeCallbacks(telemetryRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
