package com.custom.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class MdmService : Service() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var dbRef: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var currentDeviceId: String = "phone_1"

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = MdmAdminReceiver.getComponentName(this)
        startMdmForeground()
        connectToFirebase()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMdmForeground()
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val savedId = prefs.getString("device_id", "phone_1") ?: "phone_1"
        if (savedId != currentDeviceId || listener == null) {
            currentDeviceId = savedId
            connectToFirebase()
        }
        return START_STICKY
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
                if (!snapshot.exists()) return

                val blockInstall = snapshot.child("blockInstall").getValue(Boolean::class.java) ?: false
                val blockUninstall = snapshot.child("blockUninstall").getValue(Boolean::class.java) ?: false
                val kioskMode = snapshot.child("kioskMode").getValue(Boolean::class.java) ?: false
                val kioskApps = snapshot.child("kioskApps").getValue(String::class.java) ?: ""
                val removeAdmin = snapshot.child("removeAdmin").getValue(Boolean::class.java) ?: false

                applyPolicies(blockInstall, blockUninstall, kioskMode, kioskApps, removeAdmin)
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        dbRef?.addValueEventListener(listener!!)
    }

    private fun applyPolicies(
        blockInstall: Boolean,
        blockUninstall: Boolean,
        kioskMode: Boolean,
        kioskApps: String,
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
            updateStatus("Connected (Not Device Owner yet)")
            sendBroadcast(Intent("com.custom.mdm.POLICY_UPDATED"))
            return
        }

        try {
            if (removeAdmin) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.setUninstallBlocked(adminComponent, packageName, false)
                dpm.setLockTaskPackages(adminComponent, emptyArray())

                dbRef?.child("removeAdmin")?.setValue(false)
                updateStatus("Device Owner Removed - Ready to Uninstall")

                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(packageName)
                sendBroadcast(Intent("com.custom.mdm.POLICY_UPDATED"))
                return
            }

            // 1. Block or Allow App Installations & Sideloading
            if (blockInstall) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }

            // 2. Block Uninstall & Self Protection
            if (blockUninstall) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.setUninstallBlocked(adminComponent, packageName, true)
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.setUninstallBlocked(adminComponent, packageName, false)
            }

            // 3. Configure Kiosk Mode Whitelist
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
            updateStatus("Online | InstallLock:$blockInstall | Kiosk:$kioskMode ($time)")
            sendBroadcast(Intent("com.custom.mdm.POLICY_UPDATED"))

        } catch (e: Exception) {
            updateStatus("Error: ${e.message}")
        }
    }

    private fun updateStatus(msg: String) {
        dbRef?.child("status")?.setValue(msg)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
