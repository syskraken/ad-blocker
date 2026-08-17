package dev.franklin.adblocker

import java.util.concurrent.atomic.AtomicLong

/**
 * What has been blocked this session, collapsed per domain.
 *
 * A plain list of every hit would be dominated by whichever tracker is
 * chattiest, so each domain gets one row carrying a count instead. Insertion
 * order in the map doubles as recency: re-recording a domain moves it to the
 * end, and the oldest entry is evicted once the map is full.
 */
object BlockLog {

    private const val MAX_DOMAINS = 200

    class Entry(val host: String, val count: Long, val lastSeen: Long)

    private class Mutable(val host: String, var count: Long, var lastSeen: Long)

    private val entries = LinkedHashMap<String, Mutable>()

    /** Bumped on every change so the UI can skip rebuilding an unchanged list. */
    private val revision = AtomicLong()

    fun revision(): Long = revision.get()

    fun record(host: String) {
        val now = System.currentTimeMillis()
        synchronized(entries) {
            val existing = entries.remove(host)
            if (existing != null) {
                existing.count++
                existing.lastSeen = now
                entries[host] = existing
            } else {
                entries[host] = Mutable(host, 1, now)
                while (entries.size > MAX_DOMAINS) {
                    val oldest = entries.keys.iterator()
                    oldest.next()
                    oldest.remove()
                }
            }
        }
        revision.incrementAndGet()
    }

    /** Most recently blocked first. Entries are copies, safe to hold on the UI thread. */
    fun snapshot(limit: Int = MAX_DOMAINS): List<Entry> = synchronized(entries) {
        entries.values
            .reversed()
            .take(limit)
            .map { Entry(it.host, it.count, it.lastSeen) }
    }

    fun isEmpty(): Boolean = synchronized(entries) { entries.isEmpty() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        revision.incrementAndGet()
    }
}
