package dev.franklin.adblocker

import java.security.MessageDigest

/**
 * The PIN that guards turning blocking off.
 *
 * Stored as a hash so the value is not sitting in plain text in preferences.
 * That is worth doing, but it is not a security boundary: anything with access
 * to app data can clear it, and Android's own VPN and force-stop controls never
 * pass through here at all.
 */
object Pin {

    /** Seeded on first run; changeable from inside the app. */
    const val DEFAULT = "2026"

    private const val MIN_LENGTH = 4
    private const val MAX_LENGTH = 8

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isAcceptable(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }
}
