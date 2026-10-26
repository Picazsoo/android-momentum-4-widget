package com.example.m4control.gaia

/**
 * GAIA v3 stream parser: tolerates fragmented headers and payloads and several frames per read,
 * never resynchronizes inside a valid incomplete payload, rejects headers with an unsupported
 * version or non-zero flags, and bounds both the buffer size and how long a frame may take to arrive.
 */
class FrameParser(
    private val maxBuffer: Int = 4096,
    private val assemblyTimeoutMs: Long = 2000,
) {
    private var buf = ByteArray(0)
    private var partialSince: Long = -1

    /** Diagnostics for rejected bytes; the receiver logs them. */
    val rejected = mutableListOf<String>()

    fun feed(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): List<Frame> {
        if (partialSince >= 0 && nowMs - partialSince > assemblyTimeoutMs && buf.isNotEmpty()) {
            reject("frame assembly timed out", 1)
        }
        buf += bytes
        val frames = mutableListOf<Frame>()
        while (true) {
            if (buf.isEmpty()) break
            if (u(0) != Frame.SYNC) {
                val next = buf.indexOfFirst { (it.toInt() and 0xFF) == Frame.SYNC }
                reject("no sync", if (next < 0) buf.size else next)
                continue
            }
            if (buf.size < 2) break
            if (u(1) != Frame.VERSION) { reject("bad version", 1); continue }
            if (buf.size < 3) break
            if (u(2) != 0) { reject("unsupported flags %02X".format(u(2)), 1); continue }
            if (buf.size < 4) break
            val len = u(3)
            val total = Frame.HEADER_SIZE + len
            if (buf.size < total) break
            val vendor = (u(4) shl 8) or u(5)
            val command = (u(6) shl 8) or u(7)
            frames += Frame(vendor, command, buf.copyOfRange(Frame.HEADER_SIZE, total))
            buf = buf.copyOfRange(total, buf.size)
        }
        if (buf.size > maxBuffer) reject("buffer overflow", buf.size)
        partialSince = if (buf.isEmpty()) -1 else if (partialSince < 0) nowMs else partialSince
        return frames
    }

    private fun u(i: Int) = buf[i].toInt() and 0xFF

    private fun reject(reason: String, count: Int) {
        val n = count.coerceIn(1, buf.size)
        rejected += "$reason: ${buf.copyOfRange(0, n).toHex()}"
        buf = buf.copyOfRange(n, buf.size)
        partialSince = -1
    }
}
