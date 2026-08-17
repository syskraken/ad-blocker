package dev.franklin.adblocker

import android.content.Context

object Prefs {

    private const val FILE = "adblocker"
    private const val KEY_ALLOWLIST = "allowlist"
    private const val KEY_BLOCKLIST = "custom_blocklist"
    private const val KEY_LAST_UPDATE = "last_update"

    private fun of(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Raw text as the user typed it, one domain per line. */
    fun allowlistText(context: Context): String =
        of(context).getString(KEY_ALLOWLIST, "") ?: ""

    fun setAllowlistText(context: Context, text: String) {
        of(context).edit().putString(KEY_ALLOWLIST, text).apply()
    }

    fun allowlist(context: Context): Set<String> = parseDomains(allowlistText(context))

    fun blocklistText(context: Context): String =
        of(context).getString(KEY_BLOCKLIST, "") ?: ""

    fun setBlocklistText(context: Context, text: String) {
        of(context).edit().putString(KEY_BLOCKLIST, text).apply()
    }

    fun blocklist(context: Context): Set<String> = parseDomains(blocklistText(context))

    /** Tolerates newlines, commas, or spaces, and ignores comment lines. */
    private fun parseDomains(text: String): Set<String> =
        text.split('\n', ',', ' ')
            .map { it.trim().trimEnd('.').lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    fun lastUpdate(context: Context): Long = of(context).getLong(KEY_LAST_UPDATE, 0L)

    fun setLastUpdate(context: Context, at: Long) {
        of(context).edit().putLong(KEY_LAST_UPDATE, at).apply()
    }
}
