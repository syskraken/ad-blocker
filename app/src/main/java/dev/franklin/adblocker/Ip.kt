package dev.franklin.adblocker

/**
 * Minimal IPv4/IPv6 + UDP reader and writer.
 *
 * Only enough of the IP stack to recognise a DNS datagram coming out of the tun
 * device and to hand a well-formed reply back in. Anything unusual (fragments,
 * extension headers, non-UDP) is rejected so the caller can drop the packet.
 */
object Ip {

    const val PROTO_UDP = 17

    class UdpPacket(
        val version: Int,
        val srcAddr: ByteArray,
        val dstAddr: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v shr 8) and 0xFF).toByte()
        b[i + 1] = (v and 0xFF).toByte()
    }

    /** Returns null when the buffer is not a complete, unfragmented UDP datagram. */
    fun parseUdp(buf: ByteArray, length: Int): UdpPacket? {
        if (length < 1) return null
        return when ((buf[0].toInt() and 0xF0) shr 4) {
            4 -> parseUdp4(buf, length)
            6 -> parseUdp6(buf, length)
            else -> null
        }
    }

    private fun parseUdp4(buf: ByteArray, length: Int): UdpPacket? {
        if (length < 20) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != PROTO_UDP) return null

        // Reassembling fragments is out of scope; DNS queries never need it.
        val fragmentOffset = ((buf[6].toInt() and 0x1F) shl 8) or (buf[7].toInt() and 0xFF)
        if (fragmentOffset != 0) return null

        val udpLength = u16(buf, ihl + 4)
        val payloadLength = (udpLength - 8).coerceIn(0, length - ihl - 8)

        return UdpPacket(
            version = 4,
            srcAddr = buf.copyOfRange(12, 16),
            dstAddr = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = u16(buf, ihl + 2),
            payloadOffset = ihl + 8,
            payloadLength = payloadLength,
        )
    }

    private fun parseUdp6(buf: ByteArray, length: Int): UdpPacket? {
        if (length < 48) return null
        // Only a bare UDP next-header is handled; extension headers are dropped.
        if ((buf[6].toInt() and 0xFF) != PROTO_UDP) return null

        val udpLength = u16(buf, 44)
        val payloadLength = (udpLength - 8).coerceIn(0, length - 48)

        return UdpPacket(
            version = 6,
            srcAddr = buf.copyOfRange(8, 24),
            dstAddr = buf.copyOfRange(24, 40),
            srcPort = u16(buf, 40),
            dstPort = u16(buf, 42),
            payloadOffset = 48,
            payloadLength = payloadLength,
        )
    }

    /** Builds a datagram addressed back to whoever sent [request]. */
    fun buildUdpReply(request: UdpPacket, payload: ByteArray): ByteArray =
        if (request.version == 4) buildUdpReply4(request, payload) else buildUdpReply6(request, payload)

    private fun buildUdpReply4(request: UdpPacket, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(20 + udpLength)

        out[0] = 0x45              // version 4, 5 x 32-bit words of header
        out[1] = 0                 // DSCP / ECN
        put16(out, 2, out.size)    // total length
        put16(out, 4, 0)           // identification
        put16(out, 6, 0x4000)      // don't fragment
        out[8] = 64                // TTL
        out[9] = PROTO_UDP.toByte()
        put16(out, 10, 0)          // header checksum, filled in below
        System.arraycopy(request.dstAddr, 0, out, 12, 4)
        System.arraycopy(request.srcAddr, 0, out, 16, 4)
        put16(out, 10, fold(sum(out, 0, 20)))

        put16(out, 20, request.dstPort)
        put16(out, 22, request.srcPort)
        put16(out, 24, udpLength)
        put16(out, 26, 0)          // UDP checksum, filled in below
        System.arraycopy(payload, 0, out, 28, payload.size)
        put16(out, 26, udpChecksum(out, addrOffset = 12, addrLength = 4, udpOffset = 20, udpLength = udpLength))

        return out
    }

    private fun buildUdpReply6(request: UdpPacket, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(40 + udpLength)

        out[0] = 0x60              // version 6, no traffic class
        put16(out, 4, udpLength)   // payload length
        out[6] = PROTO_UDP.toByte()
        out[7] = 64                // hop limit
        System.arraycopy(request.dstAddr, 0, out, 8, 16)
        System.arraycopy(request.srcAddr, 0, out, 24, 16)

        put16(out, 40, request.dstPort)
        put16(out, 42, request.srcPort)
        put16(out, 44, udpLength)
        put16(out, 46, 0)
        System.arraycopy(payload, 0, out, 48, payload.size)
        // Unlike IPv4, the UDP checksum is mandatory over IPv6.
        put16(out, 46, udpChecksum(out, addrOffset = 8, addrLength = 16, udpOffset = 40, udpLength = udpLength))

        return out
    }

    private fun sum(buf: ByteArray, offset: Int, length: Int): Long {
        var total = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            total += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) total += (buf[i].toInt() and 0xFF) shl 8
        return total
    }

    private fun fold(value: Long): Int {
        var v = value
        while ((v shr 16) != 0L) v = (v and 0xFFFF) + (v shr 16)
        return (v.inv() and 0xFFFF).toInt()
    }

    /**
     * Source and destination addresses sit next to each other in both IP versions,
     * so the pseudo-header addresses can be summed as one contiguous run.
     */
    private fun udpChecksum(out: ByteArray, addrOffset: Int, addrLength: Int, udpOffset: Int, udpLength: Int): Int {
        var total = sum(out, addrOffset, addrLength * 2)
        total += PROTO_UDP.toLong()
        total += udpLength.toLong()
        total += sum(out, udpOffset, udpLength)
        val checksum = fold(total)
        // A computed zero is transmitted as all-ones; zero means "no checksum".
        return if (checksum == 0) 0xFFFF else checksum
    }
}
