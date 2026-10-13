package com.ayomide.guardian.core

/** Minimal IPv4/UDP handling for the DNS-only VPN. */
object Packets {

    class UdpPacket(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    /** Parses an IPv4 UDP packet; returns null for anything else or malformed input. */
    fun parseUdp(packet: ByteArray, length: Int): UdpPacket? {
        if (length < 28 || length > packet.size) return null
        if ((packet[0].toInt() shr 4) and 0xF != 4) return null
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if (packet[9].toInt() and 0xFF != 17) return null
        val total = minOf(u16(packet, 2), length)
        if (total < ihl + 8) return null
        val udpLen = u16(packet, ihl + 4)
        if (udpLen < 8) return null
        val start = ihl + 8
        val end = minOf(total, ihl + udpLen)
        if (end < start) return null
        return UdpPacket(
            packet.copyOfRange(12, 16), packet.copyOfRange(16, 20),
            u16(packet, ihl), u16(packet, ihl + 2),
            packet.copyOfRange(start, end),
        )
    }

    /** Builds an IPv4 UDP packet (UDP checksum 0, which IPv4 allows). */
    fun buildUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45
        put16(b, 2, total)
        put16(b, 6, 0x4000) // don't fragment
        b[8] = 64 // TTL
        b[9] = 17 // UDP
        System.arraycopy(srcIp, 0, b, 12, 4)
        System.arraycopy(dstIp, 0, b, 16, 4)
        put16(b, 10, checksum(b, 0, 20))
        put16(b, 20, srcPort)
        put16(b, 22, dstPort)
        put16(b, 24, 8 + payload.size)
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += u16(b, i); i += 2
        }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}

/** DNS message helpers. */
object Dns {

    /** Lower-case name of the first question, or null if malformed. */
    fun queryName(msg: ByteArray): String? = parseQuestion(msg)?.first

    /** Returns (name, index just after the first question) or null. */
    private fun parseQuestion(msg: ByteArray): Pair<String, Int>? {
        if (msg.size < 12) return null
        if (Packets.u16(msg, 4) < 1) return null // QDCOUNT
        if ((msg[2].toInt() and 0x80) != 0) return null // a response, not a query
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= msg.size) return null
            val len = msg[i].toInt() and 0xFF
            if (len == 0) {
                i++; break
            }
            if (len and 0xC0 != 0) return null // compression isn't used in questions
            if (i + 1 + len > msg.size || sb.length + len > 253) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..len) sb.append((msg[i + k].toInt() and 0xFF).toChar().lowercaseChar())
            i += 1 + len
        }
        if (i + 4 > msg.size) return null // QTYPE + QCLASS
        return sb.toString() to (i + 4)
    }

    /** "No such domain" answer to [query], echoing its ID and first question. */
    fun nxdomain(query: ByteArray): ByteArray? {
        val end = parseQuestion(query)?.second ?: return null
        val out = query.copyOf(end)
        out[2] = (0x80 or (query[2].toInt() and 0x79)).toByte() // QR=1, keep opcode + RD
        out[3] = (0x80 or 0x03).toByte() // RA=1, RCODE=NXDOMAIN
        out[4] = 0; out[5] = 1 // QDCOUNT = 1
        for (k in 6..11) out[k] = 0 // AN/NS/AR = 0
        return out
    }
}
