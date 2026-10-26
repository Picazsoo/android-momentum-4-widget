package com.example.m4control.gaia

/** Command identifiers and payload codecs (GAIA v3, Sennheiser vendor 0x0495). One table, base IDs only. */
object Cmd {
    // §5 session
    const val REGISTER_NOTIFICATION = 0x0007
    const val FIRMWARE_GET = 0x1201
    const val MODEL_GET = 0x1206

    // §6 noise control
    const val ANC_MODE_SET = 0x1A00
    const val ANC_MODE_GET = 0x1A01
    const val ANC_MODE_EVENT = 0x1A81
    const val TRANSPARENCY_SET = 0x1A02
    const val TRANSPARENCY_GET = 0x1A03
    const val TRANSPARENCY_EVENT = 0x1A83
    const val MASTER_SET = 0x1A04
    const val MASTER_GET = 0x1A05
    const val MASTER_EVENT = 0x1A85

    // §7 five-band EQ, §8 submode (Bass Boost) and audio mode
    const val EQ_CONFIG_GET = 0x1000
    const val EQ_GAIN_SET = 0x1001
    const val EQ_GAIN_GET = 0x1002
    const val EQ_GAINS_EVENT = 0x1082
    const val EQ_SUBMODE_SET = 0x1008
    const val EQ_SUBMODE_GET = 0x1009
    const val EQ_SUBMODE_EVENT = 0x1089
    const val AUDIO_MODE_SET = 0x0803
    const val AUDIO_MODE_GET = 0x0804
    const val AUDIO_MODE_EVENT = 0x0884

    // §10 telemetry
    const val CHARGING_GET = 0x0602
    const val CHARGING_EVENT = 0x0682
    const val BATTERY_GET = 0x0603
    const val BATTERY_EVENT = 0x0683
    const val CODEC_GET = 0x0800
    const val CODEC_EVENT = 0x0880

    // §11 optional controls: set / get
    const val SMART_PAUSE_SET = 0x080C
    const val SMART_PAUSE_GET = 0x080D
    const val ON_HEAD_SET = 0x0400
    const val ON_HEAD_GET = 0x0401
    const val TOUCH_LOCK_SET = 0x1606
    const val TOUCH_LOCK_GET = 0x1607
    const val TOUCH_LOCK_EVENT = 0x1687
    const val SIDETONE_SET = 0x0805
    const val SIDETONE_GET = 0x0806
    const val PAUSE_FULL_TRANSPARENCY_SET = 0x1800
    const val PAUSE_FULL_TRANSPARENCY_GET = 0x1801

    /** §5: features 3 (battery), 4 (generic audio), 8 (user EQ), 13 (ANC). */
    val CORE_FEATURES = intArrayOf(3, 4, 8, 13)

    fun response(base: Int) = base or 0x0100
    fun error(base: Int) = base or 0x0180

    const val MODE_WIND = 1
    const val MODE_COMFORT = 2
    const val MODE_ADAPTIVE = 3

    const val AUDIO_BYPASS = 0
    const val AUDIO_USER_EQ = 1
    const val AUDIO_PODCAST = 2
    const val AUDIO_PERSONALIZED = 3

    const val BASS_BIT = 0x01
}

/** Payload decoders. All return null for malformed input rather than inventing defaults. */
object Codec {
    fun u8(p: ByteArray, i: Int = 0): Int? = if (p.size > i) p[i].toInt() and 0xFF else null
    fun i8(p: ByteArray, i: Int = 0): Int? = if (p.size > i) p[i].toInt() else null
    fun u16(p: ByteArray, i: Int = 0): Int? =
        if (p.size >= i + 2) ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF) else null

    fun bool(p: ByteArray): Boolean? = u8(p)?.let { it != 0 }

    fun u16Bytes(v: Int) = intArrayOf((v shr 8) and 0xFF, v and 0xFF)

    /** §5: major, minor, patch as big-endian u16. */
    fun firmware(p: ByteArray): String? {
        val a = u16(p, 0) ?: return null
        val b = u16(p, 2) ?: return null
        val c = u16(p, 4) ?: return null
        return "$a.$b.$c"
    }

    fun string(p: ByteArray): String = String(p, Charsets.UTF_8).trimEnd('\u0000')

    /** §6: mode/state pairs, parsed by mode id; odd length is invalid. Unknown modes are kept. */
    fun modePairs(p: ByteArray): Map<Int, Int>? {
        if (p.isEmpty() || p.size % 2 != 0) return null
        val m = LinkedHashMap<Int, Int>()
        for (i in p.indices step 2) m[p[i].toInt() and 0xFF] = p[i + 1].toInt() and 0xFF
        return m
    }

    /** §7 configuration: bands, minGain, maxGain (signed tenths of dB), selectable presets, user presets. */
    data class EqConfig(val bands: Int, val minGain: Int, val maxGain: Int, val selectablePresets: Int, val userPresets: Int)

    fun eqConfig(p: ByteArray): EqConfig? {
        if (p.size < 5) return null
        return EqConfig(u8(p, 0)!!, i8(p, 1)!!, i8(p, 2)!!, u8(p, 3)!!, u8(p, 4)!!)
    }

    /** §7 gain snapshot: signed bytes in band order. */
    fun gains(p: ByteArray): List<Int> = p.map { it.toInt() }

    /** §10: first byte is the headband; FF = unknown. */
    fun battery(p: ByteArray): Int? = u8(p)?.takeIf { it != 0xFF }

    val CODECS = mapOf(
        0 to "SBC", 1 to "AAC", 2 to "aptX", 3 to "aptX LL", 4 to "MP3", 5 to "aptX HD",
        6 to "FastStream", 7 to "LHDC", 8 to "aptX Adaptive", 9 to "aptX Lossless", 10 to "LC3",
    )
}
