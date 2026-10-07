package dev.franklin.adblocker

import android.content.Context
import android.provider.Settings

/**
 * Strict Private DNS (DNS-over-TLS to a named provider) is resolved by the
 * system directly against that provider, so those lookups never reach the
 * tunnel and nothing can be blocked.
 */
object PrivateDns {

    fun isStrict(context: Context): Boolean = try {
        Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"
    } catch (e: Exception) {
        false
    }
}
