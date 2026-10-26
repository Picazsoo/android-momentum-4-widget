package cz.metlicka.m4control.device

import cz.metlicka.m4control.gaia.Cmd
import kotlin.math.abs

/** One write in a transaction. */
class Step(val base: Int, vararg val payload: Int) {
    override fun toString() = "%04X%s".format(base, payload.joinToString("") { " %02X".format(it) })
    override fun equals(other: Any?) = other is Step && base == other.base && payload.contentEquals(other.payload)
    override fun hashCode() = base * 31 + payload.contentHashCode()
}

enum class NoiseMode { OFF, ADAPTIVE, CUSTOM }

/**
 * Noise control: Off = master off; Adaptive = master on + adaptive on;
 * Custom = master on + adaptive off + transparency (100 - level). Level 100 = full noise cancelling,
 * 50 = balanced, 0 = full transparency.
 */
object NoiseControl {
    val STOPS = intArrayOf(100, 83, 67, 50, 33, 17, 0)

    fun transparencyFor(level: Int) = 100 - level.coerceIn(0, 100)
    fun levelFor(transparency: Int) = 100 - transparency.coerceIn(0, 100)

    /** Index into [STOPS] nearest to [level]; ties snap toward the center. */
    fun nearestStop(level: Int): Int = STOPS.indices.minWith(
        compareBy<Int> { abs(STOPS[it] - level) }.thenBy { abs(STOPS[it] - 50) },
    )

    fun mode(master: Boolean?, adaptive: Boolean?): NoiseMode? = when {
        master == null -> null
        !master -> NoiseMode.OFF
        adaptive == true -> NoiseMode.ADAPTIVE
        adaptive == false -> NoiseMode.CUSTOM
        else -> null
    }

    enum class Tone { ANC, TRANSPARENCY, BALANCED, ADAPTIVE, OFF, UNKNOWN }
    data class Readout(val text: String, val short: String, val tone: Tone)

    fun readout(mode: NoiseMode?, level: Int): Readout = when (mode) {
        null -> Readout("—", "—", Tone.UNKNOWN)
        NoiseMode.OFF -> Readout("Off", "Off", Tone.OFF)
        NoiseMode.ADAPTIVE -> Readout("Adaptive", "Adaptive", Tone.ADAPTIVE)
        NoiseMode.CUSTOM -> when {
            level == 50 -> Readout("Balanced", "Balanced", Tone.BALANCED)
            level > 50 -> ((level - 50) * 2).let { Readout("Noise Cancelling $it%", "ANC $it%", Tone.ANC) }
            else -> ((50 - level) * 2).let { Readout("Transparency $it%", "Transp. $it%", Tone.TRANSPARENCY) }
        }
    }

    sealed interface Target {
        data object Off : Target
        data object Adaptive : Target
        data class Custom(val level: Int) : Target
    }

    /** Confirmed values from the current session only; null = not confirmed. */
    data class Confirmed(val master: Boolean?, val adaptive: Boolean?, val transparency: Int?)

    /** Enable master, disable/enable adaptive, set transparency; skip only steps confirmed this session. */
    fun steps(target: Target, c: Confirmed): List<Step> = buildList {
        when (target) {
            Target.Off -> if (c.master != false) add(Step(Cmd.MASTER_SET, 0))
            Target.Adaptive -> {
                if (c.master != true) add(Step(Cmd.MASTER_SET, 1))
                if (c.adaptive != true) add(Step(Cmd.ANC_MODE_SET, Cmd.MODE_ADAPTIVE, 1))
            }
            is Target.Custom -> {
                val t = transparencyFor(target.level)
                if (c.master != true) add(Step(Cmd.MASTER_SET, 1))
                if (c.adaptive != false) add(Step(Cmd.ANC_MODE_SET, Cmd.MODE_ADAPTIVE, 0))
                if (c.transparency != t) add(Step(Cmd.TRANSPARENCY_SET, t))
            }
        }
    }
}
