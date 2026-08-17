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

    enum class Category { ADS, ADULT }

    /**
     * Downloadable sources, in hosts-file or plain-domain format.
     *
     * The id becomes the on-disk filename, so it must stay stable across
     * releases — renaming one orphans the file a previous version wrote.
     */
    val SOURCES = listOf(
        Source(
            "stevenblack",
            "StevenBlack unified",
            listOf(
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
            ),
            Category.ADS,
        ),
        Source(
            "adaway",
            "AdAway",
            listOf(
                "https://adaway.org/hosts.txt",
                "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
            ),
            Category.ADS,
        ),
        // Deliberately the curated ~61k-entry list rather than one of the
        // million-entry aggregates: domains are held as strings in memory, and
        // a million of them costs roughly 100 MB of heap — enough to be killed
        // outright on a mid-range device.
        Source(
            "sinfonietta-adult",
            "Sinfonietta adult",
            listOf(
                "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts",
                "https://cdn.jsdelivr.net/gh/Sinfonietta/hostfiles@master/pornography-hosts",
            ),
            Category.ADULT,
        ),
    )

    /**
     * [urls] are tried in order. Mirrors matter because some carriers and
     * networks block raw.githubusercontent.com outright, which otherwise looks
     * to the user like the whole feature is broken.
     */
    class Source(
        val id: String,
        val label: String,
        val urls: List<String>,
        val category: Category,
    )

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

    /**
     * Downloads every enabled source and deletes the files of disabled ones, so
     * turning a category off actually unblocks it rather than leaving a stale
     * copy on disk. Returns the resulting domain count. Blocking call.
     */
    @Synchronized
    fun update(context: Context): Int {
        val dir = listDir(context)
        dir.mkdirs()

        val adultEnabled = Prefs.adultFilterEnabled(context)
        val known = SOURCES.map { "list_${it.id}.txt" }.toSet()

        // Older versions named files by list index; drop anything unrecognised
        // so a renamed or removed source cannot linger and keep applying.
        dir.listFiles()?.forEach { file ->
            if (file.name !in known) file.delete()
        }

        var succeeded = 0
        val failures = mutableListOf<String>()

        for (source in SOURCES) {
            val target = File(dir, "list_${source.id}.txt")
            val enabled = source.category == Category.ADS || adultEnabled

            if (!enabled) {
                target.delete()
                continue
            }

            val temp = File(dir, "list_${source.id}.tmp")
            var lastError: String? = null
            var downloaded = false

            for (url in source.urls) {
                try {
                    download(url, temp)
                    if (temp.length() > 0) {
                        downloaded = true
                        break
                    }
                    lastError = "empty response"
                } catch (e: Exception) {
                    lastError = describe(e)
                }
            }

            if (downloaded) {
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) {
                    succeeded++
                } else {
                    failures += "${source.label}: could not save to storage"
                }
            } else {
                // Keep whatever copy of this list is already on disk.
                failures += "${source.label}: ${lastError ?: "unknown error"}"
            }

            if (temp.exists()) temp.delete()
        }

        if (succeeded == 0 && dir.listFiles().isNullOrEmpty()) {
            // Report what actually went wrong; a bare "download failed" leaves
            // nobody able to tell a blocked domain from a dead network.
            throw java.io.IOException(failures.joinToString("; ").ifEmpty { "no sources enabled" })
        }

        load(context)
        Prefs.setLastUpdate(context, System.currentTimeMillis())
        return blocked.size
    }

    /** Turns an exception into something a user can act on. */
    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException ->
            "cannot resolve ${e.message} (no internet, or DNS is blocked)"
        is java.net.SocketTimeoutException ->
            "timed out — connection too slow or blocked"
        is javax.net.ssl.SSLException ->
            "secure connection failed (${e.message ?: "SSL error"})"
        is java.net.ConnectException ->
            "cannot connect (${e.message ?: "refused"})"
        else -> e.message ?: e.javaClass.simpleName
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
