package com.example.m4control.device

import com.example.m4control.gaia.Cmd
import com.example.m4control.gaia.Codec

/** Application preset record. Gains are signed tenths of a dB in band order. */
data class Preset(
    val id: String,
    val name: String,
    val gains: List<Int>,
    val builtIn: Boolean,
)

object EqModel {
    const val NAME_MAX = 30
    const val FAVORITES = 4
    val BAND_LABELS = listOf("63 Hz", "250 Hz", "1 kHz", "4 kHz", "8 kHz")
    val DEFAULT_FAVORITES = listOf("warm", "bright", "vocal", "deepBass")

    fun modeName(mode: Int): String = when (mode) {
        Cmd.AUDIO_BYPASS -> "Bypass"
        Cmd.AUDIO_USER_EQ -> "User EQ"
        Cmd.AUDIO_PODCAST -> "Podcast"
        Cmd.AUDIO_PERSONALIZED -> "Personalized"
        else -> "Unknown mode ($mode)"
    }

    fun match(gains: List<Int>?, presets: List<Preset>): Preset? =
        gains?.let { g -> presets.firstOrNull { it.gains == g } }

    /** Plan "What the EQ label shows". */
    fun label(audioMode: Int?, gains: List<Int>?, presets: List<Preset>): String = when {
        audioMode == null -> "—"
        audioMode != Cmd.AUDIO_USER_EQ -> modeName(audioMode)
        gains == null -> "User EQ"
        else -> match(gains, presets)?.name ?: if (gains.all { it == 0 }) "Flat EQ" else "Custom"
    }

    /**
     * Title for the EQ editor: what the user is editing. Like [label], but bands changed away from the
     * preset they started from read "Warm · edited" instead of "Custom".
     */
    fun editorTitle(audioMode: Int?, gains: List<Int>?, presets: List<Preset>, basePresetId: String?): String {
        val plain = label(audioMode, gains, presets)
        if (plain != "Custom") return plain
        return presets.firstOrNull { it.id == basePresetId }?.let { "${it.name} · edited" } ?: plain
    }

    /** Validate the complete vector against the device configuration; never clamp. */
    fun validate(gains: List<Int>, cfg: Codec.EqConfig?): String? {
        if (cfg == null) return "EQ configuration not read yet"
        if (gains.size != cfg.bands) return "Preset has ${gains.size} bands, headphones have ${cfg.bands}"
        val bad = gains.indexOfFirst { it < cfg.minGain || it > cfg.maxGain }
        if (bad >= 0) return "Band ${BAND_LABELS.getOrElse(bad) { "$bad" }} is outside " +
            "${fmtDb(cfg.minGain)} to ${fmtDb(cfg.maxGain)}"
        return null
    }

    /** Changed bands, then user-EQ mode if needed (gains alone don't activate the graphic EQ). */
    fun applySteps(target: List<Int>, confirmedGains: List<Int>?, audioMode: Int?): List<Step> = buildList {
        target.forEachIndexed { i, g -> if (confirmedGains?.getOrNull(i) != g) add(Step(Cmd.EQ_GAIN_SET, i, g and 0xFF)) }
        if (audioMode != Cmd.AUDIO_USER_EQ) add(Step(Cmd.AUDIO_MODE_SET, *Codec.u16Bytes(Cmd.AUDIO_USER_EQ)))
    }

    /** Bass Boost is bit 0 of the EQ submode; other bits are preserved. */
    fun bassMask(old: Int, on: Boolean) = if (on) old or Cmd.BASS_BIT else old and Cmd.BASS_BIT.inv() and 0xFF

    fun fmtDb(tenths: Int): String {
        val sign = if (tenths > 0) "+" else if (tenths < 0) "−" else ""
        return sign + "%.1f dB".format(kotlin.math.abs(tenths) / 10.0)
    }

    fun nameError(name: String, selfId: String?, presets: List<Preset>): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Enter a name"
        if (n.length > NAME_MAX) return "Up to $NAME_MAX characters"
        if (presets.any { it.id != selfId && it.name.equals(n, ignoreCase = true) }) return "A preset with this name already exists"
        return null
    }

    /** Presets in the user's order; ids not in [order] (e.g. newly saved) keep their place at the end. */
    fun ordered(presets: List<Preset>, order: List<String>): List<Preset> {
        val rank = order.withIndex().associate { it.value to it.index }
        return presets.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** Favorites as shown on the widget and home screen: in preset-list order. */
    fun favoritePresets(presets: List<Preset>, favorites: List<String>): List<Preset> =
        presets.filter { it.id in favorites }.take(FAVORITES)

    /** Returns the new favorites, or null with [error] when the change isn't allowed. */
    data class FavResult(val favorites: List<String>, val error: String?)

    fun toggleFavorite(favs: List<String>, id: String): FavResult = when {
        id in favs && favs.size <= 1 -> FavResult(favs, "Keep at least one favorite")
        id in favs -> FavResult(favs - id, null)
        favs.size >= FAVORITES -> FavResult(favs, "The widget shows $FAVORITES favorites — unstar one first")
        else -> FavResult(favs + id, null)
    }

    /** After deleting [deletedId]: refill its favorite slot with the first built-in not yet a favorite. */
    data class DeleteResult(val favorites: List<String>, val refill: String?)

    fun favoritesAfterDelete(favs: List<String>, deletedId: String, remaining: List<Preset>): DeleteResult {
        val pos = favs.indexOf(deletedId)
        if (pos < 0) return DeleteResult(favs, null)
        val refill = remaining.firstOrNull { it.builtIn && it.id !in favs }?.id
        val out = favs.toMutableList()
        if (refill != null) out[pos] = refill else out.removeAt(pos)
        return DeleteResult(out, refill)
    }
}
