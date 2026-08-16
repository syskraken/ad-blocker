package dev.franklin.adblocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packet and DNS code is the part that fails silently — a bad checksum just
 * means the kernel drops the reply and lookups hang. These tests recompute the
 * checksums independently rather than reusing the production helpers.
 */
class PacketTest {

    // --- independent checksum verification -------------------------------------------------

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
        return (v and 0xFFFF).toInt()
    }

    /** A correct checksum makes the sum over the covered bytes come out all-ones. */
    private fun assertChecksumValid(actual: Int) {
        assertEquals("checksum does not verify", 0xFFFF, actual)
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    // --- packet builders used as test input ------------------------------------------------

    private fun ipv4Query(payload: ByteArray, srcPort: Int = 41234): ByteArray {
        val out = ByteArray(20 + 8 + payload.size)
        out[0] = 0x45
        out[2] = (out.size shr 8).toByte()
        out[3] = (out.size and 0xFF).toByte()
        out[8] = 64
        out[9] = 17
        // 10.111.222.2 -> 10.111.222.1
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(out, 12)
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(out, 16)
        out[20] = (srcPort shr 8).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = 0
        out[23] = 53
        val udpLength = 8 + payload.size
        out[24] = (udpLength shr 8).toByte()
        out[25] = (udpLength and 0xFF).toByte()
        payload.copyInto(out, 28)
        return out
    }

    private fun ipv6Query(payload: ByteArray, srcPort: Int = 41234): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(40 + udpLength)
        out[0] = 0x60
        out[4] = (udpLength shr 8).toByte()
        out[5] = (udpLength and 0xFF).toByte()
        out[6] = 17
        out[7] = 64
        out[8] = 0xFD.toByte(); out[23] = 2      // fd00::2
        out[24] = 0xFD.toByte(); out[39] = 1     // fd00::1
        out[40] = (srcPort shr 8).toByte()
        out[41] = (srcPort and 0xFF).toByte()
        out[42] = 0
        out[43] = 53
        out[44] = (udpLength shr 8).toByte()
        out[45] = (udpLength and 0xFF).toByte()
        payload.copyInto(out, 48)
        return out
    }

    /** Encodes a standard query, e.g. "ads.example.com" type A. */
    private fun dnsQuery(name: String, type: Int = Dns.TYPE_A, id: Int = 0x1234): ByteArray {
        val labels = name.split('.')
        val nameLength = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(12 + nameLength + 4)
        out[0] = (id shr 8).toByte()
        out[1] = (id and 0xFF).toByte()
        out[2] = 0x01                            // RD set
        out[5] = 1                               // one question
        var p = 12
        for (label in labels) {
            out[p] = label.length.toByte()
            p++
            label.toByteArray(Charsets.US_ASCII).copyInto(out, p)
            p += label.length
        }
        out[p] = 0
        p++
        out[p] = (type shr 8).toByte()
        out[p + 1] = (type and 0xFF).toByte()
        out[p + 2] = 0
        out[p + 3] = 1                           // class IN
        return out
    }

    // --- IP parsing ------------------------------------------------------------------------

    @Test
    fun parsesIpv4UdpDatagram() {
        val payload = dnsQuery("ads.example.com")
        val packet = Ip.parseUdp(ipv4Query(payload), 20 + 8 + payload.size)

        assertNotNull(packet)
        packet!!
        assertEquals(4, packet.version)
        assertEquals(53, packet.dstPort)
        assertEquals(41234, packet.srcPort)
        assertEquals(payload.size, packet.payloadLength)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), packet.dstAddr)
    }

    @Test
    fun parsesIpv6UdpDatagram() {
        val payload = dnsQuery("ads.example.com")
        val packet = Ip.parseUdp(ipv6Query(payload), 40 + 8 + payload.size)

        assertNotNull(packet)
        packet!!
        assertEquals(6, packet.version)
        assertEquals(53, packet.dstPort)
        assertEquals(payload.size, packet.payloadLength)
        assertEquals(16, packet.dstAddr.size)
    }

    @Test
    fun rejectsNonUdpAndFragments() {
        val payload = dnsQuery("ads.example.com")

        val tcp = ipv4Query(payload)
        tcp[9] = 6
        assertNull(Ip.parseUdp(tcp, tcp.size))

        val fragment = ipv4Query(payload)
        fragment[7] = 8                          // non-zero fragment offset
        assertNull(Ip.parseUdp(fragment, fragment.size))

        assertNull(Ip.parseUdp(ByteArray(0), 0))
        assertNull(Ip.parseUdp(ByteArray(4) { 0x45.toByte() }, 4))
    }

    // --- reply construction ----------------------------------------------------------------

    @Test
    fun ipv4ReplyHasValidChecksumsAndSwappedEndpoints() {
        val payload = dnsQuery("ads.example.com")
        val request = Ip.parseUdp(ipv4Query(payload), 20 + 8 + payload.size)!!
        val answer = ByteArray(40) { 0x07.toByte() }

        val reply = Ip.buildUdpReply(request, answer)

        assertEquals(20 + 8 + answer.size, reply.size)
        assertEquals(0x45, reply[0].toInt() and 0xFF)
        assertEquals(reply.size, u16(reply, 2))
        assertEquals(17, reply[9].toInt() and 0xFF)

        // Addresses and ports come back reversed.
        assertArrayEquals(request.dstAddr, reply.copyOfRange(12, 16))
        assertArrayEquals(request.srcAddr, reply.copyOfRange(16, 20))
        assertEquals(53, u16(reply, 20))
        assertEquals(request.srcPort, u16(reply, 22))
        assertArrayEquals(answer, reply.copyOfRange(28, reply.size))

        assertChecksumValid(fold(sum(reply, 0, 20)))

        val udpLength = 8 + answer.size
        var pseudo = sum(reply, 12, 8)           // source and destination addresses
        pseudo += 17L
        pseudo += udpLength.toLong()
        pseudo += sum(reply, 20, udpLength)
        assertChecksumValid(fold(pseudo))
    }

    @Test
    fun ipv6ReplyHasValidChecksumAndSwappedEndpoints() {
        val payload = dnsQuery("ads.example.com")
        val request = Ip.parseUdp(ipv6Query(payload), 40 + 8 + payload.size)!!
        val answer = ByteArray(40) { 0x07.toByte() }

        val reply = Ip.buildUdpReply(request, answer)

        assertEquals(40 + 8 + answer.size, reply.size)
        assertEquals(0x60, reply[0].toInt() and 0xFF)
        assertEquals(8 + answer.size, u16(reply, 4))
        assertEquals(17, reply[6].toInt() and 0xFF)
        assertArrayEquals(request.dstAddr, reply.copyOfRange(8, 24))
        assertArrayEquals(request.srcAddr, reply.copyOfRange(24, 40))
        assertEquals(53, u16(reply, 40))

        val udpLength = 8 + answer.size
        var pseudo = sum(reply, 8, 32)           // source and destination addresses
        pseudo += 17L
        pseudo += udpLength.toLong()
        pseudo += sum(reply, 40, udpLength)
        assertChecksumValid(fold(pseudo))
    }

    // --- DNS -------------------------------------------------------------------------------

    @Test
    fun parsesQuestion() {
        val query = dnsQuery("ads.example.com", Dns.TYPE_A, id = 0xBEEF)
        val parsed = Dns.parseQuery(query, query.size)

        assertNotNull(parsed)
        parsed!!
        assertEquals(0xBEEF, parsed.id)
        assertEquals("ads.example.com", parsed.name)
        assertEquals(Dns.TYPE_A, parsed.type)
        assertEquals(query.size, parsed.questionEnd)
    }

    @Test
    fun rejectsMalformedAndNonQueryMessages() {
        assertNull(Dns.parseQuery(ByteArray(4), 4))

        val response = dnsQuery("ads.example.com")
        response[2] = 0x81.toByte()              // QR set: this is an answer
        assertNull(Dns.parseQuery(response, response.size))

        val truncated = dnsQuery("ads.example.com")
        assertNull(Dns.parseQuery(truncated, truncated.size - 6))

        val overlongLabel = dnsQuery("ads.example.com")
        overlongLabel[12] = 99                   // label runs past the message
        assertNull(Dns.parseQuery(overlongLabel, overlongLabel.size))
    }

    @Test
    fun blockedAnswerForAPointsAtZeroAddress() {
        val query = dnsQuery("ads.example.com", Dns.TYPE_A, id = 0xBEEF)
        val parsed = Dns.parseQuery(query, query.size)!!

        val response = Dns.buildBlockedResponse(query, parsed)

        assertEquals(0xBEEF, u16(response, 0))
        assertTrue("QR bit must be set", u16(response, 2) and 0x8000 != 0)
        assertEquals("RD must be echoed", 0x0100, u16(response, 2) and 0x0100)
        assertEquals("RCODE must be NOERROR", 0, u16(response, 2) and 0x000F)
        assertEquals(1, u16(response, 4))        // question count
        assertEquals(1, u16(response, 6))        // answer count

        // The question section is echoed verbatim.
        assertArrayEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, query.size))

        var p = query.size
        assertEquals("answer name must be a pointer to offset 12", 0xC00C, u16(response, p)); p += 2
        assertEquals(Dns.TYPE_A, u16(response, p)); p += 2
        assertEquals(1, u16(response, p)); p += 2 // class IN
        p += 4                                   // TTL
        assertEquals(4, u16(response, p)); p += 2 // rdata length
        assertArrayEquals(ByteArray(4), response.copyOfRange(p, p + 4))
        assertEquals(p + 4, response.size)
    }

    @Test
    fun blockedAnswerForAaaaPointsAtUnspecifiedAddress() {
        val query = dnsQuery("ads.example.com", Dns.TYPE_AAAA)
        val parsed = Dns.parseQuery(query, query.size)!!

        val response = Dns.buildBlockedResponse(query, parsed)

        assertEquals(1, u16(response, 6))
        assertEquals(16, u16(response, response.size - 18))
        assertArrayEquals(ByteArray(16), response.copyOfRange(response.size - 16, response.size))
    }

    @Test
    fun blockedAnswerForOtherTypesIsEmptyNoError() {
        val query = dnsQuery("ads.example.com", type = 33) // SRV
        val parsed = Dns.parseQuery(query, query.size)!!

        val response = Dns.buildBlockedResponse(query, parsed)

        assertEquals("no answer records", 0, u16(response, 6))
        assertEquals("RCODE must stay NOERROR", 0, u16(response, 2) and 0x000F)
        assertEquals(query.size, response.size)
    }
}
