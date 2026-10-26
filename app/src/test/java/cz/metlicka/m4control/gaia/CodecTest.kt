package cz.metlicka.m4control.gaia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecTest {
    private fun payload(hex: String) = FrameParser().feed(hex.hexToBytes()).single().payload

    @Test fun firmware() = assertEquals("3.38.3", Codec.firmware(payload("FF 03 00 06 04 95 13 01 00 03 00 26 00 03")))

    @Test fun eqConfiguration() {
        val c = Codec.eqConfig(payload("FF 03 00 05 04 95 11 00 05 C4 3C 00 00"))!!
        assertEquals(5, c.bands)
        assertEquals(-60, c.minGain)
        assertEquals(60, c.maxGain)
    }

    @Test fun signedGains() {
        assertEquals(listOf(20, -10, 0, 10, -20), Codec.gains(payload("FF 03 00 05 04 95 10 82 14 F6 00 0A EC")))
        assertEquals(-25, Codec.i8(payload("FF 03 00 01 04 95 11 02 E7")))
    }

    @Test fun battery() {
        assertEquals(80, Codec.battery(payload("FF 03 00 01 04 95 07 03 50")))
        assertNull(Codec.battery(payload("FF 03 00 01 04 95 06 83 FF")))
        assertEquals(64, Codec.battery(byteArrayOf(64, 70)))
    }

    @Test fun audioModeIsU16() = assertEquals(1, Codec.u16(payload("FF 03 00 02 04 95 08 84 00 01")))

    @Test fun modePairsById() {
        val m = Codec.modePairs(payload("FF 03 00 06 04 95 1A 81 01 02 02 00 03 00"))!!
        assertEquals(mapOf(1 to 2, 2 to 0, 3 to 0), m)
        assertEquals(mapOf(3 to 1, 9 to 4), Codec.modePairs(byteArrayOf(3, 1, 9, 4)))
        assertNull(Codec.modePairs(byteArrayOf(3, 1, 9)))
        assertNull(Codec.modePairs(ByteArray(0)))
    }
}
