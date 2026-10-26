package cz.metlicka.m4control.gaia

/** One GAIA v3 frame. [command] is the full 16-bit identifier including the type bits. */
class Frame(val vendor: Int, val command: Int, val payload: ByteArray = ByteArray(0)) {

    /** feature = (id >> 9) & 0x7f, type = (id >> 7) & 0x03, base = id & 0xfe7f. */
    val feature: Int get() = (command shr 9) and 0x7f
    val type: Int get() = (command shr 7) and 0x03
    val base: Int get() = command and 0xfe7f

    fun encode(): ByteArray {
        require(payload.size <= 0xFF) { "payload too long: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = SYNC.toByte()
        out[1] = VERSION.toByte()
        out[2] = 0 // flags
        out[3] = payload.size.toByte()
        out[4] = (vendor shr 8).toByte()
        out[5] = vendor.toByte()
        out[6] = (command shr 8).toByte()
        out[7] = command.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    fun hex(): String = encode().toHex()

    override fun toString(): String = "Frame(%04X %04X [%s])".format(vendor, command, payload.toHex())

    override fun equals(other: Any?): Boolean =
        other is Frame && vendor == other.vendor && command == other.command && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (vendor * 31 + command) * 31 + payload.contentHashCode()

    companion object {
        const val SYNC = 0xFF
        const val VERSION = 0x03
        const val HEADER_SIZE = 8
        const val VENDOR_SENNHEISER = 0x0495

        const val TYPE_COMMAND = 0
        const val TYPE_NOTIFICATION = 1
        const val TYPE_RESPONSE = 2
        const val TYPE_ERROR = 3

        fun command(command: Int, vararg payload: Int): Frame =
            Frame(VENDOR_SENNHEISER, command, ByteArray(payload.size) { payload[it].toByte() })
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val parts = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
}
