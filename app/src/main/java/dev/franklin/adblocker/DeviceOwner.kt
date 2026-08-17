package dev.franklin.adblocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * The only mechanism Android offers that genuinely prevents a user turning
 * blocking off or removing the app.
 *
 * All of it requires the app to be the device owner, which can only be set on a
 * phone with no accounts configured — in practice, straight after a factory
 * reset, over ADB. Every call here is a no-op otherwise, so the feature simply
 * stays unavailable on an ordinary device rather than misbehaving.
 */
object DeviceOwner {

    fun component(context: Context) = ComponentName(context, AdminReceiver::class.java)

    private fun policyManager(context: Context): DevicePolicyManager? =
        try {
            context.getSystemService(DevicePolicyManager::class.java)
        } catch (e: Exception) {
            null
        }

    fun isDeviceOwner(context: Context): Boolean = try {
        policyManager(context)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /** Whether the lock-down has actually been applied, not merely available. */
    fun isLockedDown(context: Context): Boolean = try {
        policyManager(context)?.isUninstallBlocked(component(context), context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Each policy is applied independently so one unsupported call cannot take
     * the others down with it. Returns a line per policy describing what
     * happened, which is the only way to tell which ones an OEM honoured.
     */
    fun lockDown(context: Context): List<String> {
        val manager = policyManager(context) ?: return listOf("Device policy service unavailable")
        val admin = component(context)
        val notes = mutableListOf<String>()

        try {
            manager.setUninstallBlocked(admin, context.packageName, true)
            notes += "Uninstall blocked"
        } catch (e: Exception) {
            notes += "Uninstall block failed: ${e.message ?: e.javaClass.simpleName}"
        }

        try {
            // This is what removes Disconnect from the system VPN dialog.
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            notes += "VPN settings locked"
        } catch (e: Exception) {
            notes += "VPN lock failed: ${e.message ?: e.javaClass.simpleName}"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                // lockdown = true also blocks traffic whenever the tunnel is down,
                // so killing the app does not quietly restore unfiltered access.
                manager.setAlwaysOnVpnPackage(admin, context.packageName, true)
                notes += "Always-on VPN enabled"
            } catch (e: Exception) {
                notes += "Always-on failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        return notes
    }

    fun release(context: Context): List<String> {
        val manager = policyManager(context) ?: return listOf("Device policy service unavailable")
        val admin = component(context)
        val notes = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                manager.setAlwaysOnVpnPackage(admin, null, false)
                notes += "Always-on VPN cleared"
            } catch (e: Exception) {
                notes += "Always-on clear failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        try {
            manager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            notes += "VPN settings unlocked"
        } catch (e: Exception) {
            notes += "VPN unlock failed: ${e.message ?: e.javaClass.simpleName}"
        }

        try {
            manager.setUninstallBlocked(admin, context.packageName, false)
            notes += "Uninstall allowed"
        } catch (e: Exception) {
            notes += "Uninstall unblock failed: ${e.message ?: e.javaClass.simpleName}"
        }

        return notes
    }
}
