package com.ayomide.guardian.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DnsTest {
    /** A real-shaped query for "ads.example.com" type A, id 0x1234, RD set. */
    private fun query(name: String = "ads.example.com"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length); out.write(label.toByteArray())
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    @Test fun parsesName() {
        assertEquals("ads.example.com", Dns.queryName(query()))
        assertEquals("mixed.case.com", Dns.queryName(query("MiXeD.Case.com")))
    }

    @Test fun rejectsMalformed() {
        assertNull(Dns.queryName(ByteArray(5)))
        val q = query()
        assertNull(Dns.queryName(q.copyOf(q.size - 3))) // truncated QTYPE/QCLASS
        val resp = q.copyOf(); resp[2] = (resp[2].toInt() or 0x80).toByte()
        assertNull(Dns.queryName(resp)) // it's a response
    }

    @Test fun nxdomainAnswer() {
        val q = query()
        val r = Dns.nxdomain(q)!!
        assertEquals(q.size, r.size)
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(0x81, r[2].toInt() and 0xFF) // QR + RD
        assertEquals(0x83, r[3].toInt() and 0xFF) // RA + NXDOMAIN
        assertEquals(1, Packets.u16(r, 4))
        assertEquals(0, Packets.u16(r, 6))
    }

    @Test fun udpRoundTrip() {
        val src = byteArrayOf(10, 0, 0, 2)
        val dst = byteArrayOf(10, 111, 222.toByte(), 2)
        val payload = query()
        val pkt = Packets.buildUdp(src, dst, 40000, 53, payload)
        assertEquals(0, Packets.checksum(pkt, 0, 20)) // valid header checksum sums to 0
        val p = Packets.parseUdp(pkt, pkt.size)!!
        assertArrayEquals(src, p.srcIp)
        assertArrayEquals(dst, p.dstIp)
        assertEquals(40000, p.srcPort)
        assertEquals(53, p.dstPort)
        assertArrayEquals(payload, p.payload)
    }

    @Test fun ignoresNonUdp() {
        val pkt = Packets.buildUdp(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1, 53, ByteArray(4))
        pkt[9] = 6 // TCP
        assertNull(Packets.parseUdp(pkt, pkt.size))
        assertNull(Packets.parseUdp(ByteArray(10), 10))
    }
}

class BlocklistTest {
    private val list = Blocklist(
        Blocklist.parse("# comment\n0.0.0.0 doubleclick.net\n127.0.0.1 ads.example.com\ntracker.io\n0.0.0.0 localhost\nnot a line here\n"),
        setOf("good.ads.example.com"),
    )

    @Test fun parsing() {
        assertEquals(3, list.size)
        assertEquals(setOf("a.com"), Blocklist.parse("0.0.0.0 a.com  # x\n0.0.0.0 0.0.0.0\n0.0.0.0 com\n"))
    }

    @Test fun matching() {
        assertTrue(list.isBlocked("doubleclick.net"))
        assertTrue(list.isBlocked("stats.g.doubleclick.net"))
        assertTrue(list.isBlocked("ADS.example.com."))
        assertTrue(list.isBlocked("x.tracker.io"))
        assertFalse(list.isBlocked("example.com"))
        assertFalse(list.isBlocked("notdoubleclick.net"))
        assertFalse(list.isBlocked("good.ads.example.com")) // allow list wins
        assertFalse(list.isBlocked("www.google.com"))
        assertFalse(list.isBlocked(""))
    }
}

class DetectorTest {
    @Test fun pocketNeedsCoverThenUncover() {
        val d = PocketDetector(1000)
        assertFalse(d.onSample(false, 0)) // in hand: nothing
        assertFalse(d.onSample(true, 100))
        assertFalse(d.onSample(false, 500)) // brief cover doesn't count
        assertFalse(d.onSample(true, 600))
        assertFalse(d.onSample(true, 1700))
        assertEquals(PocketDetector.State.IN_POCKET, d.state)
        assertTrue(d.onSample(false, 5000))
        assertFalse(d.onSample(false, 5100)) // only once
    }

    @Test fun motionAfterBaseline() {
        val d = MotionDetector(threshold = 1.5f, baselineSamples = 5, needed = 2)
        repeat(5) { assertFalse(d.onSample(0f, 0f, 9.8f)) }
        assertFalse(d.onSample(0.1f, 0f, 9.9f)) // tiny jitter
        assertFalse(d.onSample(3f, 0f, 9.8f)) // one spike
        assertFalse(d.onSample(0f, 0f, 9.8f)) // resets
        assertFalse(d.onSample(3f, 1f, 9f))
        assertTrue(d.onSample(2f, 2f, 8f))
        assertTrue(d.triggered)
    }
}

class SmsTest {
    @Test fun secretMatching() {
        assertTrue(SmsCommand.matches("  Pineapple ", "pineapple"))
        assertFalse(SmsCommand.matches("pineapple please", "pineapple"))
        assertFalse(SmsCommand.matches("abc", "abc")) // too short a secret
        assertFalse(SmsCommand.matches(null, "pineapple"))
    }

    @Test fun trustedNumbers() {
        assertTrue(SmsCommand.isTrusted("+2348031234567", emptyList()))
        assertTrue(SmsCommand.isTrusted("+2348031234567", listOf("0803 123 4567")))
        assertTrue(SmsCommand.isTrusted("08031234567", listOf("+234 803 123 4567")))
        assertFalse(SmsCommand.isTrusted("08099999999", listOf("08031234567")))
        assertFalse(SmsCommand.isTrusted(null, listOf("08031234567")))
    }

    @Test fun replyText() {
        val r = SmsCommand.reply(54, 6.52437, 3.37921, 12f)
        assertTrue(r.contains("Battery 54%"))
        assertTrue(r.contains("https://maps.google.com/?q=6.52437,3.37921"))
        assertTrue(SmsCommand.reply(null, null, null, null).contains("Location unavailable"))
    }

    @Test fun pins() {
        assertTrue(Pin.isValid("1234")); assertFalse(Pin.isValid("12a4")); assertFalse(Pin.isValid("123"))
        assertEquals(Pin.hash("1234", "s"), Pin.hash("1234", "s"))
        assertFalse(Pin.hash("1234", "s") == Pin.hash("1235", "s"))
        assertEquals(64, Pin.hash("1234", "s").length)
    }
}

class DataMathTest {
    @Test fun periods() {
        assertEquals(LocalDate.of(2026, 10, 5), DataMath.periodStart(5, LocalDate.of(2026, 10, 13)))
        assertEquals(LocalDate.of(2026, 9, 5), DataMath.periodStart(5, LocalDate.of(2026, 10, 4)))
        assertEquals(LocalDate.of(2026, 10, 5), DataMath.periodStart(5, LocalDate.of(2026, 10, 5)))
        assertEquals(LocalDate.of(2026, 2, 28), DataMath.periodStart(31, LocalDate.of(2026, 3, 10)))
        assertEquals(LocalDate.of(2026, 1, 31), DataMath.periodStart(31, LocalDate.of(2026, 2, 27)))
    }

    @Test fun levels() {
        val gb = 1024 * DataMath.MB
        assertEquals(0, DataMath.level(500 * DataMath.MB, gb, 80))
        assertEquals(1, DataMath.level(820 * DataMath.MB, gb, 80))
        assertEquals(2, DataMath.level(gb, gb, 80))
        assertEquals(0, DataMath.level(5 * gb, 0, 80))
    }

    @Test fun formatting() {
        assertEquals("312 MB", DataMath.format(312 * DataMath.MB))
        assertEquals("4.6 GB", DataMath.format((4.6 * 1024 * DataMath.MB).toLong()))
        assertEquals("900 B", DataMath.format(900))
        assertEquals(31, DataMath.percent(312, 1000))
    }
}
