package dev.franklin.adblocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsAnswerTest {

    private fun name(vararg labels: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (l in labels) { out.write(l.length); out.write(l.toByteArray()) }
        out.write(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8); write(v and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) { u16((v shr 16).toInt() and 0xFFFF); u16((v and 0xFFFF).toInt()) }

    /** Query for www.site.com answered by a CNAME to cdn.tracker.net and an A record. */
    private fun response(ttlCname: Long = 300, ttlA: Long = 60, rcode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(0xABCD); out.u16(0x8180 or rcode); out.u16(1); out.u16(2); out.u16(0); out.u16(0)
        out.write(name("www", "site", "com")); out.u16(1); out.u16(1)
        // CNAME answer, name compressed to the question, target uses a pointer into itself.
        out.write(0xC0); out.write(12); out.u16(5); out.u16(1); out.u32(ttlCname)
        val target = name("cdn", "tracker", "net")
        out.u16(target.size); out.write(target)
        // A answer for the CNAME target.
        out.write(target); out.u16(1); out.u16(1); out.u32(ttlA); out.u16(4)
        out.write(byteArrayOf(1, 2, 3, 4))
        return out.toByteArray()
    }

    @Test
    fun findsCnameTargetsAndMinimumTtl() {
        val msg = response()
        val answer = Dns.parseAnswer(msg, msg.size)!!
        assertEquals(listOf("cdn.tracker.net"), answer.cnames)
        assertEquals(60L, answer.minTtl)
        assertEquals(2, answer.answerCount)
        assertEquals(0, answer.rcode)
    }

    @Test
    fun resolvesCompressedCnameTarget() {
        val msg = response()
        // Rewrite the CNAME rdata to a pointer at the first answer's own target name.
        val answer = Dns.parseAnswer(msg, msg.size)!!
        assertEquals("cdn.tracker.net", answer.cnames[0])
    }

    @Test
    fun rejectsTruncatedAndNonResponses() {
        val msg = response()
        assertNull(Dns.parseAnswer(msg, msg.size - 3))
        val query = msg.copyOf(); query[2] = 0x01
        assertNull(Dns.parseAnswer(query, query.size))
    }

    @Test
    fun pointerLoopDoesNotHang() {
        val out = ByteArrayOutputStream()
        out.u16(1); out.u16(0x8180); out.u16(0); out.u16(1); out.u16(0); out.u16(0)
        out.write(0xC0); out.write(12); out.u16(5); out.u16(1); out.u32(10)
        out.u16(2); out.write(0xC0); out.write(26) // rdata is a pointer to itself
        val msg = out.toByteArray()
        assertNull(Dns.parseAnswer(msg, msg.size))
    }

    @Test
    fun cacheServesUntilExpiryAndSkipsFailures() {
        val cache = DnsCache()
        val msg = response()
        val answer = Dns.parseAnswer(msg, msg.size)!!
        cache.put("k", msg, answer, now = 1000)
        assertNotNull(cache.get("k", now = 1000 + 59_000))
        assertNull(cache.get("k", now = 1000 + 61_000))

        val nx = response(rcode = 3)
        cache.put("nx", nx, Dns.parseAnswer(nx, nx.size)!!, now = 0)
        assertNull(cache.get("nx", now = 1))
    }

    @Test
    fun cacheDropsEntriesWhenRulesChange() {
        val cache = DnsCache()
        val msg = response()
        cache.sync(1)
        cache.put("k", msg, Dns.parseAnswer(msg, msg.size)!!)
        cache.sync(1)
        assertNotNull(cache.get("k"))
        cache.sync(2)
        assertNull(cache.get("k"))
    }

    @Test
    fun nxdomainEchoesQuestion() {
        val q = ByteArrayOutputStream()
        q.u16(0x1234); q.u16(0x0100); q.u16(1); q.u16(0); q.u16(0); q.u16(0)
        q.write(name("use-application-dns", "net")); q.u16(1); q.u16(1)
        val bytes = q.toByteArray()
        val parsed = Dns.parseQuery(bytes, bytes.size)!!
        val r = Dns.buildNxdomain(bytes, parsed)
        assertEquals(3, ((r[2].toInt() and 0xFF) shl 8 or (r[3].toInt() and 0xFF)) and 0xF)
        assertArrayEquals(bytes.copyOfRange(12, bytes.size), r.copyOfRange(12, r.size))
        val re = Dns.withId(r, 0xBEEF)
        assertEquals(0xBE.toByte(), re[0])
        assertEquals(0xEF.toByte(), re[1])
    }
}
