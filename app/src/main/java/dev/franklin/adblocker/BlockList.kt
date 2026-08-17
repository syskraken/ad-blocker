package dev.franklin.adblocker

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The set of domains to answer with a dead address, plus the user's allowlist.
 *
 * Held entirely in memory: a few hundred thousand strings costs on the order of
 * 20 MB, which is cheaper than a lookup structure that has to touch storage on
 * every DNS query.
 */
object BlockList {

    /** Downloadable sources, in hosts-file or plain-domain format. */
    val SOURCES = listOf(
        Source("StevenBlack unified", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
        Source("AdAway", "https://adaway.org/hosts.txt"),
    )

    class Source(val label: String, val url: String)

    private const val LIST_DIR = "lists"
    private const val ASSET = "default_blocklist.txt"

    // Hosts files point blocked names at these; they are addresses, not domains.
    private val SENTINELS = setOf("0.0.0.0", "127.0.0.1", "::1", "::", "255.255.255.255")
    private val NOISE = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts",
    )

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var custom: Set<String> = emptySet()
    @Volatile private var loaded = false

    fun size(): Int = blocked.size

    fun customSize(): Int = custom.size

    /** Exact membership, so the UI can avoid appending a duplicate allowlist line. */
    fun isAllowed(host: String): Boolean = allowed.contains(host.trimEnd('.').lowercase())

    fun isLoaded(): Boolean = loaded

    @Synchronized
    fun load(context: Context) {
        val domains = HashSet<String>(1 shl 16)

        try {
            context.assets.open(ASSET).use { readInto(BufferedReader(InputStreamReader(it)), domains) }
        } catch (e: Exception) {
            // A missing bundled list is survivable; downloaded lists may still be present.
        }

        listDir(context).listFiles()?.forEach { file ->
            try {
                file.bufferedReader().use { readInto(it, domains) }
            } catch (e: Exception) {
                // Skip a list that failed to read rather than losing the rest.
            }
        }

        blocked = domains
        allowed = Prefs.allowlist(context)
        custom = Prefs.blocklist(context)
        loaded = true
    }

    /**
     * Re-reads the user's own lists without rebuilding the (much larger) set
     * parsed from files. Both are volatile, so an edit takes effect on the very
     * next lookup even while the tunnel is running.
     */
    @Synchronized
    fun refreshUserLists(context: Context) {
        allowed = Prefs.allowlist(context)
        custom = Prefs.blocklist(context)
    }

    /**
     * A domain is blocked when it, or any parent of it, is listed. Checking the
     * allowlist at each level first means allowing `ads.example.com` still lets
     * a broader block on `example.com` stand for its siblings — and it means a
     * user's allowlist always beats their own custom blocklist.
     */
    fun isBlocked(host: String): Boolean {
        val name = host.trimEnd('.').lowercase()
        if (name.isEmpty()) return false

        val block = blocked
        val extra = custom
        val allow = allowed
        var i = 0
        while (true) {
            val candidate = if (i == 0) name else name.substring(i)
            if (allow.contains(candidate)) return false
            if (block.contains(candidate) || extra.contains(candidate)) return true
            val dot = name.indexOf('.', i)
            if (dot < 0) return false
            i = dot + 1
        }
    }

    /** Downloads every source. Returns the resulting domain count. Blocking call. */
    @Synchronized
    fun update(context: Context): Int {
        val dir = listDir(context)
        dir.mkdirs()

        var succeeded = 0
        SOURCES.forEachIndexed { index, source ->
            val target = File(dir, "list_$index.txt")
            val temp = File(dir, "list_$index.tmp")
            try {
                download(source.url, temp)
                if (temp.length() > 0) {
                    if (target.exists()) target.delete()
                    if (temp.renameTo(target)) succeeded++
                }
            } catch (e: Exception) {
                // Keep whatever copy of this list is already on disk.
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

        if (succeeded == 0 && dir.listFiles().isNullOrEmpty()) {
            throw java.io.IOException("Could not download any blocklist")
        }

        load(context)
        Prefs.setLastUpdate(context, System.currentTimeMillis())
        return blocked.size
    }

    private fun download(url: String, into: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "TripoleFlux/1.0 (Android)")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${connection.responseCode} for $url")
            }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            stream.use { input -> into.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun listDir(context: Context) = File(context.filesDir, LIST_DIR)

    /** Accepts both `0.0.0.0 ads.example.com` and a bare `ads.example.com`. */
    private fun readInto(reader: BufferedReader, into: MutableSet<String>) {
        reader.forEachLine { rawLine ->
            var line = rawLine
            val comment = line.indexOf('#')
            if (comment >= 0) line = line.substring(0, comment)
            line = line.trim()
            if (line.isEmpty()) return@forEachLine

            val fields = line.split(' ', '\t').filter { it.isNotEmpty() }
            val domain = when {
                fields.isEmpty() -> return@forEachLine
                fields.size == 1 -> fields[0]
                fields[0] in SENTINELS -> fields[1]
                else -> return@forEachLine
            }.trimEnd('.').lowercase()

            if (domain.isEmpty()) return@forEachLine
            if (domain in NOISE || domain in SENTINELS) return@forEachLine
            if (!domain.contains('.')) return@forEachLine
            into.add(domain)
        }
    }
}
