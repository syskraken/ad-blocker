package dev.franklin.adblocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A VPN that carries almost nothing.
 *
 * The tun interface is advertised as the system DNS server and only that one
 * address is routed into it. Every other packet on the device keeps its normal
 * path, so there is no proxying, no throughput cost, and no traffic to inspect —
 * the only thing this service ever sees is DNS.
 */
class AdVpnService : VpnService() {

    companion object {
        private const val TAG = "AdVpnService"

        const val ACTION_START = "dev.franklin.adblocker.START"
        const val ACTION_STOP = "dev.franklin.adblocker.STOP"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        // Addresses inside the tunnel. Both ranges are private and unroutable.
        private const val ADDRESS_V4 = "10.111.222.2"
        private const val DNS_V4 = "10.111.222.1"
        private const val ADDRESS_V6 = "fd00:1:adb1::2"
        private const val DNS_V6 = "fd00:1:adb1::1"

        private val FALLBACK_DNS = listOf("1.1.1.1", "9.9.9.9", "8.8.8.8")

        @Volatile
        var isRunning: Boolean = false
            private set

        val queryCount = AtomicLong()
        val blockedCount = AtomicLong()
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var output: FileOutputStream? = null
    private var pool: ExecutorService? = null
    private var upstreams: List<InetAddress> = emptyList()
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        if (!isRunning) startTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        // The user switched to another VPN, or revoked ours in system settings.
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (!BlockList.isLoaded()) BlockList.load(applicationContext)
        BlockList.refreshUserLists(applicationContext)

        // Read the real resolvers before we become the active network.
        upstreams = discoverUpstreams()

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            // establish() hands back a non-blocking descriptor by default, which
            // would turn the read loop into a busy spin.
            .setBlocking(true)
            .addAddress(ADDRESS_V4, 32)
            .addDnsServer(DNS_V4)
            .addRoute(DNS_V4, 32)

        try {
            builder.addAddress(ADDRESS_V6, 128)
            builder.addDnsServer(DNS_V6)
            builder.addRoute(DNS_V6, 128)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "IPv6 unavailable on this device, continuing with IPv4 only", e)
        }

        try {
            // Our own upstream lookups must never re-enter the tunnel.
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not exclude self from the tunnel", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }

        if (pfd == null) {
            Log.e(TAG, "VPN permission missing or another VPN is active")
            stopForegroundCompat()
            stopSelf()
            return
        }

        queryCount.set(0)
        blockedCount.set(0)
        BlockLog.clear()

        tunnel = pfd
        output = FileOutputStream(pfd.fileDescriptor)
        pool = Executors.newFixedThreadPool(8)
        isRunning = true

        reader = Thread({ readLoop(pfd) }, "dns-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val read = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "tun read ended", e)
                break
            }
            if (read <= 0) continue

            val packet = Ip.parseUdp(buffer, read) ?: continue
            if (packet.dstPort != 53) continue

            val payload = buffer.copyOfRange(
                packet.payloadOffset,
                packet.payloadOffset + packet.payloadLength,
            )
            if (payload.isEmpty()) continue

            try {
                pool?.execute { handleQuery(packet, payload) }
            } catch (e: Exception) {
                // Pool shutting down; the client will retry the lookup.
            }
        }
    }

    private fun handleQuery(packet: Ip.UdpPacket, payload: ByteArray) {
        queryCount.incrementAndGet()

        val query = Dns.parseQuery(payload, payload.size)
        if (query != null && BlockList.isBlocked(query.name)) {
            blockedCount.incrementAndGet()
            BlockLog.record(query.name)
            write(Ip.buildUdpReply(packet, Dns.buildBlockedResponse(payload, query)))
            return
        }

        val answer = forward(payload) ?: return
        write(Ip.buildUdpReply(packet, answer))
    }

    private fun forward(payload: ByteArray): ByteArray? {
        for (server in upstreams) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                // Without this the query would loop straight back into the tunnel.
                if (!protect(socket)) continue
                socket.soTimeout = 4000
                socket.send(DatagramPacket(payload, payload.size, server, 53))

                val buffer = ByteArray(4096)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                return buffer.copyOf(response.length)
            } catch (e: Exception) {
                // Fall through to the next resolver.
            } finally {
                socket?.close()
            }
        }
        Log.w(TAG, "No upstream resolver answered")
        return null
    }

    private fun write(packet: ByteArray) {
        val stream = output ?: return
        synchronized(writeLock) {
            try {
                stream.write(packet)
            } catch (e: Exception) {
                // Tunnel closed underneath us; the read loop will notice.
            }
        }
    }

    /**
     * Prefers the resolvers handed out by Wi-Fi or mobile data so split-horizon
     * names on the local network keep resolving, and falls back to public ones.
     */
    private fun discoverUpstreams(): List<InetAddress> {
        val servers = mutableListOf<InetAddress>()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val properties = cm.getLinkProperties(network) ?: continue
                for (server in properties.dnsServers) {
                    if (!server.isAnyLocalAddress && !servers.contains(server)) servers.add(server)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read system resolvers", e)
        }

        for (literal in FALLBACK_DNS) {
            try {
                val address = InetAddress.getByName(literal)
                if (!servers.contains(address)) servers.add(address)
            } catch (e: Exception) {
                // Literal parsing cannot really fail, but never let it stop startup.
            }
        }
        return servers
    }

    private fun stopTunnel() {
        isRunning = false

        reader?.interrupt()
        reader = null

        pool?.shutdownNow()
        pool = null

        try {
            output?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        output = null

        try {
            tunnel?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        tunnel = null
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AdVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
