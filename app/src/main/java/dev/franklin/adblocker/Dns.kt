package dev.franklin.adblocker

/** Just enough DNS to read the question and forge a "nothing here" answer. */
object Dns {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    class Query(
        val id: Int,
        val flags: Int,
        val name: String,
        val type: Int,
        /** Offset just past the question section, relative to the start of the message. */
        val questionEnd: Int,
    )

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v shr 8) and 0xFF).toByte()
        b[i + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v shr 24) and 0xFF).toByte()
        b[i + 1] = ((v shr 16) and 0xFF).toByte()
        b[i + 2] = ((v shr 8) and 0xFF).toByte()
        b[i + 3] = (v and 0xFF).toByte()
    }

    /**
     * Returns null for anything that is not a plain single-question query.
     * The caller forwards those upstream untouched rather than guessing.
     */
    fun parseQuery(msg: ByteArray, length: Int): Query? {
        if (length < 12) return null

        val id = u16(msg, 0)
        val flags = u16(msg, 2)
        if (flags and 0x8000 != 0) return null           // already a response
        if ((flags shr 11) and 0x0F != 0) return null    // not a standard query
        if (u16(msg, 4) != 1) return null                // exactly one question

        val name = StringBuilder()
        var p = 12
        while (true) {
            if (p >= length) return null
            val labelLength = msg[p].toInt() and 0xFF
            p++
            if (labelLength == 0) break
            if (labelLength and 0xC0 != 0) return null   // compression pointer in a question
            if (p + labelLength > length) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(msg, p, labelLength, Charsets.US_ASCII))
            p += labelLength
        }
        if (p + 4 > length) return null
        val type = u16(msg, p)
        p += 4                                           // skip type and class

        return Query(id = id, flags = flags, name = name.toString(), type = type, questionEnd = p)
    }

    /**
     * Answers A with 0.0.0.0 and AAAA with ::, which makes the connection fail
     * instantly. Every other type gets NOERROR with no records, so callers stop
     * asking instead of retrying the way they do after NXDOMAIN.
     */
    fun buildBlockedResponse(query: ByteArray, parsed: Query): ByteArray {
        val questionLength = parsed.questionEnd - 12
        val recordLength = when (parsed.type) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> 0
        }
        val hasAnswer = recordLength > 0
        val answerLength = if (hasAnswer) 12 + recordLength else 0
        val out = ByteArray(12 + questionLength + answerLength)

        put16(out, 0, parsed.id)
        // QR=1, RA=1, RD copied from the query, RCODE=0.
        put16(out, 2, 0x8000 or 0x0080 or (parsed.flags and 0x0100))
        put16(out, 4, 1)                                 // question count
        put16(out, 6, if (hasAnswer) 1 else 0)           // answer count
        put16(out, 8, 0)                                 // authority count
        put16(out, 10, 0)                                // additional count
        System.arraycopy(query, 12, out, 12, questionLength)

        if (hasAnswer) {
            var p = 12 + questionLength
            out[p] = 0xC0.toByte()                       // name: pointer back to the question
            out[p + 1] = 0x0C
            p += 2
            put16(out, p, parsed.type); p += 2
            put16(out, p, 1); p += 2                     // class IN
            put32(out, p, 600); p += 4                   // TTL, seconds
            put16(out, p, recordLength)                  // rdata is left as zeroes
        }

        return out
    }
}
