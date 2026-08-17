package dev.franklin.adblocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Registering as a device admin is what stops the app being uninstalled the
 * ordinary way — Android refuses to remove an app with an active admin.
 *
 * It is a speed bump rather than a lock: the admin can be deactivated from
 * Settings, and uninstalling then works normally. Genuinely preventing removal
 * needs device-owner provisioning, which requires a factory reset.
 */
class AdminReceiver : DeviceAdminReceiver() {

    /** Shown on the confirmation screen when someone tries to deactivate. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
