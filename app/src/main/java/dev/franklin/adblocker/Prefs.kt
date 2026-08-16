package dev.franklin.adblocker

import android.content.Context

object Prefs {

    private const val FILE = "adblocker"
    private const val KEY_ALLOWLIST = "allowlist"
    private const val KEY_LAST_UPDATE = "last_update"

    private fun of(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Raw text as the user typed it, one domain per line. */
    fun allowlistText(context: Context): String =
        of(context).getString(KEY_ALLOWLIST, "") ?: ""

    fun setAllowlistText(context: Context, text: String) {
        of(context).edit().putString(KEY_ALLOWLIST, text).apply()
    }

    fun allowlist(context: Context): Set<String> =
        allowlistText(context)
            .split('\n', ',', ' ')
            .map { it.trim().trimEnd('.').lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    fun lastUpdate(context: Context): Long = of(context).getLong(KEY_LAST_UPDATE, 0L)

    fun setLastUpdate(context: Context, at: Long) {
        of(context).edit().putLong(KEY_LAST_UPDATE, at).apply()
    }
}
