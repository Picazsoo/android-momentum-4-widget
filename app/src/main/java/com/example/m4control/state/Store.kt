package com.example.m4control.state

import android.content.Context
import android.content.SharedPreferences
import com.example.m4control.device.EqModel
import com.example.m4control.device.NoiseMode
import com.example.m4control.device.Preset
import com.example.m4control.gaia.Codec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent state in SharedPreferences: chosen device, last known snapshot (for the widget when the
 * process was dead), remembered noise choices, user presets and favorites. Synchronous reads keep the
 * widget provider simple.
 */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("m4", Context.MODE_PRIVATE)
    private val builtIns: List<Preset> = loadBuiltIns(context)

    var deviceAddress: String?
        get() = prefs.getString("device_address", null)
        set(v) = prefs.edit().putString("device_address", v).apply()
    var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(v) = prefs.edit().putString("device_name", v).apply()

    fun saveRuntime(s: AppState) {
        prefs.edit()
            .putString("snapshot", snapshotToJson(s.snapshot).toString())
            .putLong("last_synced", s.lastSyncedAt)
            .putBoolean("stale", s.stale)
            .putString("last_on_mode", s.lastOnMode.name)
            .putInt("last_custom_level", s.lastCustomLevel)
            .putInt("bass_prev_mode", s.bassPrevMode ?: -1)
            .putString("base_preset", s.basePresetId)
            .apply()
    }

    fun loadRuntime(): AppState = AppState(
        deviceAddress = deviceAddress,
        deviceName = deviceName,
        snapshot = prefs.getString("snapshot", null)?.let { runCatching { snapshotFromJson(JSONObject(it)) }.getOrNull() }
            ?: DeviceSnapshot(),
        lastSyncedAt = prefs.getLong("last_synced", 0),
        stale = prefs.getBoolean("stale", true),
        lastOnMode = runCatching { NoiseMode.valueOf(prefs.getString("last_on_mode", "CUSTOM")!!) }.getOrDefault(NoiseMode.CUSTOM),
        lastCustomLevel = prefs.getInt("last_custom_level", 83),
        bassPrevMode = prefs.getInt("bass_prev_mode", -1).takeIf { it >= 0 },
        basePresetId = prefs.getString("base_preset", null),
    )

    // ---- presets ----

    fun presets(): List<Preset> = EqModel.ordered(builtIns + userPresets(), presetOrder)

    // ---- widget lock (per widget instance) ----

    /** Settings → "Lock button on widget". Turning it off unlocks every widget. */
    var widgetLockEnabled: Boolean
        get() = prefs.getBoolean("widget_lock_enabled", false)
        set(v) {
            val e = prefs.edit().putBoolean("widget_lock_enabled", v)
            if (!v) prefs.all.keys.filter { it.startsWith("widget_locked_") }.forEach { e.remove(it) }
            e.apply()
        }

    fun isWidgetLocked(id: Int) = prefs.getBoolean("widget_locked_$id", false)
    fun setWidgetLocked(id: Int, locked: Boolean) = prefs.edit().putBoolean("widget_locked_$id", locked).apply()
    fun clearWidgetLocks(ids: IntArray) = prefs.edit().apply { ids.forEach { remove("widget_locked_$it") } }.apply()

    /** User-chosen list order (preset ids). Ids of deleted presets may linger; they are harmless. */
    var presetOrder: List<String>
        get() = prefs.getString("preset_order", null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = prefs.edit().putString("preset_order", v.joinToString(",")).apply()

    fun userPresets(): List<Preset> {
        val arr = JSONArray(prefs.getString("user_presets", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Preset(o.getString("id"), o.getString("name"), o.getJSONArray("gains").toIntList(), builtIn = false)
        }
    }

    fun saveUserPresets(list: List<Preset>) {
        val arr = JSONArray()
        list.filter { !it.builtIn }.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("profile", PROFILE).put("gainsTenthsDb", JSONArray(it.gains)).put("gains", JSONArray(it.gains)))
        }
        prefs.edit().putString("user_presets", arr.toString()).apply()
    }

    /** Saved favorites that still exist; if none do (e.g. the built-in presets changed), the defaults. */
    var favorites: List<String>
        get() {
            val known = presets().map { it.id }.toSet()
            return prefs.getString("favorites", null)?.split(',')?.filter { it in known }?.takeIf { it.isNotEmpty() }
                ?: EqModel.DEFAULT_FAVORITES
        }
        set(v) = prefs.edit().putString("favorites", v.joinToString(",")).apply()

    var nextUserPresetNumber: Int
        get() = prefs.getInt("next_user_preset", 1)
        set(v) = prefs.edit().putInt("next_user_preset", v).apply()

    companion object {
        const val PROFILE = "momentum4-graphic-eq-v2"
        private fun loadBuiltIns(context: Context): List<Preset> {
            val json = JSONObject(context.assets.open("presets.json").bufferedReader().readText())
            val arr = json.getJSONArray("presets")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                Preset(id, o.getString("name"), o.getJSONArray("gainsTenthsDb").toIntList(), builtIn = true)
            }
        }

        private fun JSONArray.toIntList() = (0 until length()).map { getInt(it) }

        fun snapshotToJson(s: DeviceSnapshot): JSONObject = JSONObject().apply {
            s.master?.let { put("master", it) }
            s.adaptive?.let { put("adaptive", it) }
            s.wind?.let { put("wind", it) }
            s.transparency?.let { put("transparency", it) }
            s.eqConfig?.let { put("eqConfig", JSONArray(listOf(it.bands, it.minGain, it.maxGain, it.selectablePresets, it.userPresets))) }
            s.gains?.let { put("gains", JSONArray(it)) }
            s.submask?.let { put("submask", it) }
            s.audioMode?.let { put("audioMode", it) }
            s.battery?.let { put("battery", it) }
            s.charging?.let { put("charging", it) }
            s.codec?.let { put("codec", it) }
            s.firmware?.let { put("firmware", it) }
            s.model?.let { put("model", it) }
            put("settings", JSONObject(s.settings as Map<*, *>))
        }

        fun snapshotFromJson(o: JSONObject) = DeviceSnapshot(
            master = o.optBool("master"),
            adaptive = o.optBool("adaptive"),
            wind = o.optIntOrNull("wind"),
            transparency = o.optIntOrNull("transparency"),
            eqConfig = o.optJSONArray("eqConfig")?.toIntList()?.let { Codec.EqConfig(it[0], it[1], it[2], it[3], it[4]) },
            gains = o.optJSONArray("gains")?.toIntList(),
            submask = o.optIntOrNull("submask"),
            audioMode = o.optIntOrNull("audioMode"),
            battery = o.optIntOrNull("battery"),
            charging = o.optIntOrNull("charging"),
            codec = o.optIntOrNull("codec"),
            firmware = o.optString("firmware").ifEmpty { null },
            model = o.optString("model").ifEmpty { null },
            settings = o.optJSONObject("settings")?.let { js -> js.keys().asSequence().associateWith { js.getInt(it) } } ?: emptyMap(),
        )

        private fun JSONObject.optBool(k: String): Boolean? = if (has(k)) getBoolean(k) else null
        private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k)) getInt(k) else null
    }
}
