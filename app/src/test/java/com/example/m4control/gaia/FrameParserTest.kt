package com.example.m4control.gaia

import com.example.m4control.MiniJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class FrameParserTest {
    private val fixtures = MiniJson.parse(
        javaClass.classLoader!!.getResource("protocol-vectors.json")!!.readText(),
    ) as Map<String, Any?>
    private val vectors = fixtures["vectors"] as List<Map<String, Any?>>
    private val streams = fixtures["stream_cases"] as List<Map<String, Any?>>

    private fun hexInt(v: Any?) = (v as String).removePrefix("0x").toInt(16)
    private fun payload(v: Map<String, Any?>) = (v["payload"] as List<Number>).map { it.toInt().toByte() }.toByteArray()
    private fun stream(name: String) = streams.first { it["name"] == name }
    private fun chunks(c: Map<String, Any?>) = (c["chunks"] as List<String>).map { it.hexToBytes() }

    @Test fun everyVectorEncodesToItsHex() {
        for (v in vectors) {
            val f = Frame(hexInt(v["vendor"]), hexInt(v["command_id"]), payload(v))
            assertEquals(v["name"] as String, (v["hex"] as String).uppercase(), f.hex())
        }
    }

    @Test fun everyVectorDecodesFromItsHex() {
        for (v in vectors) {
            val frames = FrameParser().feed((v["hex"] as String).hexToBytes())
            assertEquals(v["name"] as String, listOf(Frame(hexInt(v["vendor"]), hexInt(v["command_id"]), payload(v))), frames)
        }
    }

    @Test fun vectorKindsMatchTypeBits() {
        val kinds = mapOf("command" to Frame.TYPE_COMMAND, "response" to Frame.TYPE_RESPONSE, "notification" to Frame.TYPE_NOTIFICATION)
        for (v in vectors) assertEquals(v["name"] as String, kinds[v["kind"]], Frame(0x0495, hexInt(v["command_id"])).type)
    }

    @Test fun splitHeader() {
        val p = FrameParser()
        val c = chunks(stream("split_header"))
        assertTrue(p.feed(c[0]).isEmpty())
        assertEquals(listOf(0x1A05), p.feed(c[1]).map { it.command })
    }

    @Test fun twoFramesInOneRead() {
        val frames = FrameParser().feed(chunks(stream("two_frames"))[0])
        assertEquals(listOf(0x1B05, 0x1A83), frames.map { it.command })
    }

    @Test fun flagsByteIsNotALength() {
        val p = FrameParser()
        assertTrue(p.feed(chunks(stream("reject_flags_as_length"))[0]).isEmpty())
        assertTrue(p.rejected.any { it.startsWith("unsupported flags") })
        // Parser recovers for the next valid frame.
        assertEquals(1, p.feed("FF 03 00 00 04 95 1A 05".hexToBytes()).size)
    }

    @Test fun syncBytesInsidePartialPayload() {
        val p = FrameParser()
        val c = chunks(stream("sync_inside_partial_payload"))
        assertTrue(p.feed(c[0]).isEmpty())
        val f = p.feed(c[1]).single()
        val expected = (stream("sync_inside_partial_payload")["expected_payload"] as List<Number>).map { it.toInt().toByte() }
        assertArrayEquals(expected.toByteArray(), f.payload)
    }

    @Test fun vendorMismatchStillParses() {
        val f = FrameParser().feed(chunks(stream("vendor_mismatch"))[0]).single()
        assertEquals(0x001D, f.vendor)
        assertEquals(0x1B05, f.command)
    }

    @Test fun garbageBeforeSyncIsSkipped() {
        val p = FrameParser()
        val f = p.feed("00 12 FF 03 00 01 04 95 1B 05 01".hexToBytes()).single()
        assertEquals(0x1B05, f.command)
        assertEquals(1, p.rejected.size)
    }

    @Test fun incompleteFrameExpires() {
        val p = FrameParser(assemblyTimeoutMs = 100)
        assertTrue(p.feed("FF 03 00 05 04 95 11 00".hexToBytes(), nowMs = 0).isEmpty())
        // After the deadline the stale partial frame is dropped and a fresh frame parses.
        val frames = p.feed("FF 03 00 00 04 95 1A 05".hexToBytes(), nowMs = 1000)
        assertEquals(listOf(0x1A05), frames.map { it.command })
    }

    @Test fun bufferIsBounded() {
        val p = FrameParser(maxBuffer = 64)
        p.feed("FF 03 00 FF".hexToBytes())
        p.feed(ByteArray(100) { 0x41 })
        assertTrue(p.rejected.any { it.startsWith("buffer overflow") })
    }

    @Test fun typeAndBaseDecode() {
        val f = Frame(0x0495, 0x1B84)
        assertEquals(13, f.feature)
        assertEquals(Frame.TYPE_ERROR, f.type)
        assertEquals(0x1A04, f.base)
        assertEquals(Frame.TYPE_NOTIFICATION, Frame(0x0495, 0x1A85).type)
    }
}
