package dev.franklin.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Recency here comes from LinkedHashMap insertion order rather than timestamps,
 * so that repeated hits within the same millisecond still order correctly.
 * These tests pin that behaviour down.
 */
class BlockLogTest {

    @Before
    fun setUp() {
        BlockLog.clear()
    }

    @Test
    fun collapsesRepeatsIntoOneEntryWithACount() {
        repeat(5) { BlockLog.record("ads.example.com") }

        val entries = BlockLog.snapshot()
        assertEquals(1, entries.size)
        assertEquals("ads.example.com", entries[0].host)
        assertEquals(5L, entries[0].count)
    }

    @Test
    fun ordersMostRecentlyBlockedFirst() {
        BlockLog.record("a.com")
        BlockLog.record("b.com")
        BlockLog.record("c.com")

        assertEquals(listOf("c.com", "b.com", "a.com"), BlockLog.snapshot().map { it.host })

        // Re-blocking an existing domain moves it back to the front.
        BlockLog.record("a.com")
        assertEquals(listOf("a.com", "c.com", "b.com"), BlockLog.snapshot().map { it.host })
    }

    @Test
    fun evictsTheOldestOnceTheCapIsReached() {
        repeat(250) { BlockLog.record("host$it.example.com") }

        val entries = BlockLog.snapshot(250)
        assertEquals(200, entries.size)

        val hosts = entries.map { it.host }.toSet()
        assertFalse("oldest entry should have been evicted", hosts.contains("host0.example.com"))
        assertTrue("newest entry should be present", hosts.contains("host249.example.com"))
        assertEquals("host249.example.com", entries[0].host)
    }

    @Test
    fun honoursTheSnapshotLimit() {
        repeat(20) { BlockLog.record("host$it.example.com") }

        assertEquals(5, BlockLog.snapshot(5).size)
        assertEquals("host19.example.com", BlockLog.snapshot(5)[0].host)
    }

    @Test
    fun revisionChangesOnEveryMutation() {
        val start = BlockLog.revision()

        BlockLog.record("a.com")
        val afterRecord = BlockLog.revision()
        assertNotEquals(start, afterRecord)

        BlockLog.clear()
        assertNotEquals(afterRecord, BlockLog.revision())
        assertTrue(BlockLog.isEmpty())
    }

    @Test
    fun snapshotEntriesAreDetachedCopies() {
        BlockLog.record("a.com")
        val before = BlockLog.snapshot()[0]

        repeat(3) { BlockLog.record("a.com") }

        // The previously handed-out entry must not have mutated underneath the UI.
        assertEquals(1L, before.count)
        assertEquals(4L, BlockLog.snapshot()[0].count)
    }
}
