package dev.franklin.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {

    @Test
    fun hashingIsStableAcrossCalls() {
        assertEquals(Pin.hash("2026"), Pin.hash("2026"))
    }

    @Test
    fun differentPinsHashDifferently() {
        assertNotEquals(Pin.hash("2026"), Pin.hash("2027"))
        // Leading zeroes must survive rather than being read as a number.
        assertNotEquals(Pin.hash("0123"), Pin.hash("123"))
    }

    @Test
    fun hashIsSha256Hex() {
        val hash = Pin.hash("2026")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun theStoredHashIsNotThePinItself() {
        // Guards against someone "optimising" the hash away later.
        assertNotEquals("2026", Pin.hash("2026"))
        assertFalse(Pin.hash("2026").contains("2026"))
    }

    @Test
    fun acceptsFourToEightDigits() {
        assertTrue(Pin.isAcceptable("2026"))
        assertTrue(Pin.isAcceptable("0000"))
        assertTrue(Pin.isAcceptable("12345678"))
        assertTrue(Pin.isAcceptable(Pin.DEFAULT))
    }

    @Test
    fun rejectsWrongLengthOrNonDigits() {
        assertFalse(Pin.isAcceptable(""))
        assertFalse(Pin.isAcceptable("123"))
        assertFalse(Pin.isAcceptable("123456789"))
        assertFalse(Pin.isAcceptable("20a6"))
        assertFalse(Pin.isAcceptable("20 6"))
        assertFalse(Pin.isAcceptable("-123"))
    }
}
