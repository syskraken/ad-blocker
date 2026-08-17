package dev.franklin.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {

    private val hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun readsTheHashFromSha256sumOutput() {
        // The format sha256sum writes: hash, two spaces, filename.
        assertEquals(hash, Updater.parseSha256("$hash  tripoleflux-1.0.2.apk"))
    }

    @Test
    fun toleratesSingleSpaceAndTrailingNewline() {
        assertEquals(hash, Updater.parseSha256("$hash tripoleflux-1.0.2.apk\n"))
        assertEquals(hash, Updater.parseSha256("$hash  tripoleflux-1.0.2.apk\r\n"))
    }

    @Test
    fun acceptsABareHash() {
        assertEquals(hash, Updater.parseSha256(hash))
        assertEquals(hash, Updater.parseSha256("  $hash  "))
    }

    @Test
    fun normalisesUppercaseToLowercase() {
        assertEquals(hash, Updater.parseSha256(hash.uppercase() + "  file.apk"))
    }

    @Test
    fun skipsLinesThatAreNotHashes() {
        val text = """
            # checksums for this release
            not-a-hash  something.txt
            $hash  tripoleflux-1.0.2.apk
        """.trimIndent()
        assertEquals(hash, Updater.parseSha256(text))
    }

    @Test
    fun returnsNullWhenNothingLooksLikeAHash() {
        assertNull(Updater.parseSha256(""))
        assertNull(Updater.parseSha256("no checksum here"))
        // Right character set, wrong length - must not be accepted.
        assertNull(Updater.parseSha256("abc123"))
        assertNull(Updater.parseSha256(hash.dropLast(1)))
        assertNull(Updater.parseSha256(hash + "aa"))
    }

    @Test
    fun rejectsNonHexOfTheCorrectLength() {
        val sixtyFourNonHex = "z".repeat(64)
        assertNull(Updater.parseSha256(sixtyFourNonHex))
    }
}
