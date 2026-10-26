package cz.metlicka.m4control.state

import cz.metlicka.m4control.device.NoiseControl
import cz.metlicka.m4control.device.NoiseMode
import cz.metlicka.m4control.gaia.Codec

/** Everything we know about the headphones. Null = unknown. Also used for the session-confirmed copy. */
data class DeviceSnapshot(
    val master: Boolean? = null,
    val adaptive: Boolean? = null,
    val wind: Int? = null,
    val modePairs: Map<Int, Int> = emptyMap(),
    val transparency: Int? = null,
    val eqConfig: Codec.EqConfig? = null,
    val gains: List<Int>? = null,
    val submask: Int? = null,
    val audioMode: Int? = null,
    val battery: Int? = null,
    val charging: Int? = null,
    val codec: Int? = null,
    val firmware: String? = null,
    val model: String? = null,
    val settings: Map<String, Int> = emptyMap(),
    val unsupported: Set<String> = emptySet(),
) {
    val noiseMode: NoiseMode? get() = NoiseControl.mode(master, adaptive)
    val bass: Boolean? get() = submask?.let { it and 1 != 0 }
    fun noiseConfirmed() = NoiseControl.Confirmed(master, adaptive, transparency)
}

/** Order matters: the first applicable state wins (plan: Connection and freshness states). */
enum class LinkState { NO_PERMISSION, NO_DEVICE, DISCONNECTED, UNAVAILABLE, CONNECTED }

/** Requested values not yet confirmed by the headphones. */
data class Desired(
    val noise: NoiseControl.Target? = null,
    val gains: List<Int>? = null,
    val audioMode: Int? = null,
    val bass: Boolean? = null,
    val touchLock: Boolean? = null,
)

data class AppState(
    val link: LinkState = LinkState.NO_DEVICE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val connecting: Boolean = false,
    val sessionOpen: Boolean = false,
    val snapshot: DeviceSnapshot = DeviceSnapshot(),
    val lastSyncedAt: Long = 0,
    val stale: Boolean = true,
    val desired: Desired = Desired(),
    val error: String? = null,
    /** Remembered noise choices, restored by the switch and used while Off. */
    val lastOnMode: NoiseMode = NoiseMode.CUSTOM,
    val lastCustomLevel: Int = 83,
    /** Preset the current bands were applied from, for "Warm · edited" in the EQ editor. */
    val basePresetId: String? = null,
    /** Audio mode to restore when Bass Boost turned on User EQ. */
    val bassPrevMode: Int? = null,
    val firmwareSupported: Boolean = true,
) {
    /** What the UI should show for noise: the pending request if any, else the device. */
    val shownNoiseMode: NoiseMode?
        get() = when (val d = desired.noise) {
            NoiseControl.Target.Off -> NoiseMode.OFF
            NoiseControl.Target.Adaptive -> NoiseMode.ADAPTIVE
            is NoiseControl.Target.Custom -> NoiseMode.CUSTOM
            null -> snapshot.noiseMode
        }
    val shownLevel: Int
        get() = when (val d = desired.noise) {
            is NoiseControl.Target.Custom -> d.level
            else -> if (snapshot.noiseMode == NoiseMode.CUSTOM && snapshot.transparency != null)
                NoiseControl.levelFor(snapshot.transparency) else lastCustomLevel
        }
    val shownGains: List<Int>? get() = desired.gains ?: snapshot.gains
    val shownAudioMode: Int? get() = desired.audioMode ?: snapshot.audioMode
    val shownBass: Boolean? get() = desired.bass ?: snapshot.bass
    val noisePending get() = desired.noise != null
    val eqPending get() = desired.gains != null || desired.audioMode != null
    val bassPending get() = desired.bass != null
    /** Headphone touch-control lock (an optional setting, also on the large widget). */
    val shownTouchLock: Boolean? get() = desired.touchLock ?: snapshot.settings["touchLock"]?.let { it != 0 }
    val touchLockPending get() = desired.touchLock != null
}
