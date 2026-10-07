package dev.franklin.adblocker

/**
 * Small TTL-respecting cache of upstream answers, keyed by the question
 * section. Only successful, complete answers are stored, and everything is
 * dropped when the block rules or the upstream resolvers change, since a
 * cached answer was vetted against the rules in force when it was stored.
 */
class DnsCache(private val maxEntries: Int = 1000) {

    private class Entry(val response: ByteArray, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    /** Case-insensitive: DNS names compare that way and some clients 0x20-randomise. */
    fun key(query: ByteArray, parsed: Dns.Query): String =
        String(query, 12, parsed.questionEnd - 12, Charsets.ISO_8859_1).lowercase()

    @Synchronized
    fun get(key: String, now: Long = System.currentTimeMillis()): ByteArray? {
        val entry = map[key] ?: return null
        if (entry.expiresAt <= now) {
            map.remove(key)
            return null
        }
        return entry.response
    }

    @Synchronized
    fun put(key: String, response: ByteArray, answer: Dns.Answer, now: Long = System.currentTimeMillis()) {
        if (answer.rcode != 0 || answer.truncated || answer.answerCount == 0) return
        val ttl = answer.minTtl.coerceIn(0, MAX_TTL_SECONDS)
        if (ttl < MIN_TTL_SECONDS) return
        map[key] = Entry(response, now + ttl * 1000)
    }

    @Synchronized
    fun clear() = map.clear()

    private var ruleVersion = -1

    /** Drops everything if the block rules changed since the cache was last used. */
    @Synchronized
    fun sync(version: Int) {
        if (version != ruleVersion) {
            map.clear()
            ruleVersion = version
        }
    }

    companion object {
        private const val MIN_TTL_SECONDS = 5L
        private const val MAX_TTL_SECONDS = 300L
    }
}
