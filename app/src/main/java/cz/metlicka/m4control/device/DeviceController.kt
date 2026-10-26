package cz.metlicka.m4control.device

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import cz.metlicka.m4control.gaia.Cmd
import cz.metlicka.m4control.gaia.Codec
import cz.metlicka.m4control.gaia.Frame
import cz.metlicka.m4control.gaia.GaiaSession
import cz.metlicka.m4control.gaia.Reply
import cz.metlicka.m4control.gaia.SessionClosedException
import cz.metlicka.m4control.state.AppState
import cz.metlicka.m4control.state.DeviceSnapshot
import cz.metlicka.m4control.state.LinkState
import cz.metlicka.m4control.state.Store
import cz.metlicka.m4control.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single owner of the control connection. The activity, the widget service and the
 * ACL receiver all go through it. User actions become keyed transactions in one serialized queue; a
 * newer request for the same key replaces a queued one.
 */
object DeviceController {
    const val CONNECT_TIMEOUT_MS = 4000L
    const val RESPONSE_TIMEOUT_MS = 1500L
    const val IDLE_KEEPALIVE_MS = 4000L
    const val SUPPORTED_FIRMWARE = "3.38.3"

    private lateinit var app: Context
    lateinit var store: Store
        private set
    lateinit var link: BluetoothLink
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    data class Presets(val all: List<Preset>, val favorites: List<String>)
    private val _presets = MutableStateFlow(Presets(emptyList(), emptyList()))
    val presets: StateFlow<Presets> = _presets.asStateFlow()

    /** True while queued work or the keep-alive period is running; the widget service stops when false. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var session: GaiaSession? = null
    private var socket: BluetoothSocket? = null
    /** Values confirmed in the current session only; used to skip steps. */
    private var confirmed = DeviceSnapshot()
    private var unavailable = false
    /** Notification ids received this session; registration pushes most state, so those reads are skipped. */
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private class Tx(val key: String, val run: suspend (GaiaSession) -> Unit)
    private val queue = LinkedHashMap<String, Tx>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var idleJob: Job? = null
    private val holds = AtomicInteger(0)
    private var initialized = false

    // ---------------------------------------------------------------- setup

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        store = Store(app)
        link = BluetoothLink(app)
        _state.value = store.loadRuntime()
        _presets.value = Presets(store.presets(), store.favorites)
        link.bindProfiles()
        recomputeLink()
        scope.launch { worker() }
        scope.launch {
            state.combine(presets) { s, p -> s to p }.collectLatest { (s, _) ->
                delay(120)
                store.saveRuntime(s)
                WidgetUpdater.updateAll(app)
            }
        }
    }

    // ---------------------------------------------------------------- link state

    fun recomputeLink() {
        val addr = store.deviceAddress
        val st = when {
            !link.hasPermission() -> LinkState.NO_PERMISSION
            addr == null || link.device(addr) == null -> LinkState.NO_DEVICE
            session?.isClosed == false -> LinkState.CONNECTED
            link.isAudioConnected(addr) == false -> LinkState.DISCONNECTED
            unavailable -> LinkState.UNAVAILABLE
            else -> LinkState.CONNECTED
        }
        _state.update { it.copy(link = st, deviceAddress = addr, deviceName = store.deviceName) }
    }

    fun onPermissionChanged() {
        link.bindProfiles()
        recomputeLink()
    }

    fun selectDevice(address: String, name: String) {
        if (address != store.deviceAddress) {
            closeSession("device changed")
            _state.update { it.copy(snapshot = DeviceSnapshot(), lastSyncedAt = 0, stale = true) }
        }
        store.deviceAddress = address
        store.deviceName = name
        unavailable = false
        recomputeLink()
        refresh()
    }

    /** From the ACL receiver: only link state and staleness, never a socket. */
    fun onAcl(address: String, connected: Boolean) {
        link.onAcl(address, connected)
        if (address != store.deviceAddress) return
        unavailable = false
        if (connected) _state.update { it.copy(stale = true) } else closeSession("audio link lost")
        recomputeLink()
    }

    /** The activity keeps the session open while visible so notifications stay live. */
    fun hold(on: Boolean) {
        if (on) {
            holds.incrementAndGet()
            recomputeLink()
            refresh()
        } else if (holds.decrementAndGet() <= 0) {
            holds.set(0)
            scheduleIdle()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- queue

    private fun submit(key: String, mark: (AppState) -> AppState = { it }, run: suspend (GaiaSession) -> Unit) {
        _state.update(mark)
        synchronized(queue) {
            queue.remove(key)
            queue[key] = Tx(key, run)
        }
        idleJob?.cancel()
        _busy.value = true
        wake.trySend(Unit)
    }

    private suspend fun worker() {
        for (signal in wake) {
            while (true) {
                val tx = synchronized(queue) { queue.entries.firstOrNull()?.also { queue.remove(it.key) }?.value } ?: break
                execute(tx)
            }
            scheduleIdle()
        }
    }

    private suspend fun execute(tx: Tx) {
        recomputeLink()
        val st = _state.value.link
        if (st != LinkState.CONNECTED && st != LinkState.UNAVAILABLE) {
            fail(tx.key, when (st) {
                LinkState.NO_PERMISSION -> "Bluetooth permission needed"
                LinkState.NO_DEVICE -> "Choose your headphones first"
                else -> "Headphones not connected"
            })
            return
        }
        val s = try {
            ensureSession()
        } catch (e: Exception) {
            log("!! connect failed: ${e.message}")
            unavailable = true
            closeSession("connect failed")
            recomputeLink()
            fail(tx.key, if (_state.value.link == LinkState.UNAVAILABLE) "Control connection unavailable" else "Headphones not connected")
            return
        }
        try {
            tx.run(s)
        } catch (e: SessionClosedException) {
            log("!! session ended: ${e.message}")
            closeSession("session ended")
            fail(tx.key, "Control connection lost")
        } catch (e: Exception) {
            log("!! ${tx.key} failed: $e")
            fail(tx.key, e.message ?: "Something went wrong")
        }
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_KEEPALIVE_MS)
            if (synchronized(queue) { queue.isNotEmpty() }) return@launch
            if (holds.get() == 0) closeSession("idle")
            _busy.value = false
        }
    }

    private fun fail(key: String, message: String) {
        clearDesired(key)
        _state.update { it.copy(error = message) }
    }

    private fun clearDesired(key: String) = _state.update {
        it.copy(desired = when (key) {
            "noise" -> it.desired.copy(noise = null)
            "eq" -> it.desired.copy(gains = null, audioMode = null)
            "audio" -> it.desired.copy(audioMode = null)
            "bass" -> it.desired.copy(bass = null)
            "touch" -> it.desired.copy(touchLock = null)
            else -> it.desired
        })
    }

    // ---------------------------------------------------------------- session

    private suspend fun ensureSession(): GaiaSession {
        session?.takeIf { !it.isClosed }?.let { return it }
        val addr = store.deviceAddress ?: throw IllegalStateException("no device")
        _state.update { it.copy(connecting = true) }
        try {
            log("-- connecting to $addr")
            val sock = link.connect(addr, scope, CONNECT_TIMEOUT_MS)
            val s = GaiaSession(sock.inputStream, sock.outputStream, scope, ::onNotification, ::log) { cause ->
                scope.launch { onSessionClosed(cause) }
            }
            socket = sock
            session = s
            confirmed = DeviceSnapshot()
            seen.clear()
            s.start()
            initSession(s)
            unavailable = false
            _state.update { it.copy(sessionOpen = true) }
            recomputeLink()
            return s
        } finally {
            _state.update { it.copy(connecting = false) }
        }
    }

    private fun onSessionClosed(cause: Throwable?) {
        log("-- session closed${cause?.let { ": ${it.message}" } ?: ""}")
        session = null
        runCatching { socket?.close() }
        socket = null
        confirmed = DeviceSnapshot()
        _state.update { it.copy(sessionOpen = false) }
        recomputeLink()
    }

    private fun closeSession(reason: String) {
        val s = session ?: return
        log("-- closing session ($reason)")
        s.close()
    }

    /** Session start: identity, profile check, notification registration, full read. */
    private suspend fun initSession(s: GaiaSession) {
        val fw = s.get(Cmd.FIRMWARE_GET)?.let(Codec::firmware)
        val model = s.get(Cmd.MODEL_GET)?.let(Codec::string)
        log("-- firmware $fw, model $model")
        confirm { copy(firmware = fw, model = model) }
        _state.update { it.copy(firmwareSupported = fw == null || fw == SUPPORTED_FIRMWARE) }
        for (f in Cmd.CORE_FEATURES) s.request(Cmd.REGISTER_NOTIFICATION, intArrayOf(f))
        readAll(s, skipSeen = true)
    }

    /**
     * Full read. With [skipSeen], values the headphones already pushed as notifications
     * during registration are not read again; that keeps a widget tap's session setup short.
     */
    private suspend fun readAll(s: GaiaSession, skipSeen: Boolean = false) {
        fun need(event: Int) = !skipSeen || event !in seen
        readNoise(s, skipSeen)
        val cfg = s.get(Cmd.EQ_CONFIG_GET)?.let(Codec::eqConfig)
        confirm { copy(eqConfig = cfg) }
        if (cfg != null && cfg.bands in 1..10 && need(Cmd.EQ_GAINS_EVENT)) readGains(s, cfg.bands)
        if (need(Cmd.EQ_SUBMODE_EVENT)) s.get(Cmd.EQ_SUBMODE_GET)?.let { p -> confirm { copy(submask = Codec.u8(p)) } }
        if (need(Cmd.AUDIO_MODE_EVENT)) s.get(Cmd.AUDIO_MODE_GET)?.let { p -> confirm { copy(audioMode = Codec.u16(p)) } }
        if (need(Cmd.BATTERY_EVENT)) s.get(Cmd.BATTERY_GET)?.let { p -> confirm { copy(battery = Codec.battery(p)) } }
        if (need(Cmd.CHARGING_EVENT)) s.get(Cmd.CHARGING_GET)?.let { p -> confirm { copy(charging = Codec.u8(p)) } }
        if (need(Cmd.CODEC_EVENT)) s.get(Cmd.CODEC_GET)?.let { p -> confirm { copy(codec = Codec.u8(p)) } }
        // Optional controls are only shown in the app; widget sessions skip them.
        // Optional controls are shown in the app; widget sessions read only touch lock (large widget), which
        // sends no notification on the features we register.
        if (!skipSeen || holds.get() > 0) for (setting in Setting.entries) readSetting(s, setting)
        else readSetting(s, Setting.TOUCH_LOCK)
        _state.update { it.copy(lastSyncedAt = System.currentTimeMillis(), stale = false) }
    }

    private suspend fun readNoise(s: GaiaSession, skipSeen: Boolean = false) {
        if (!skipSeen || Cmd.MASTER_EVENT !in seen) s.get(Cmd.MASTER_GET)?.let { p -> confirm { copy(master = Codec.bool(p)) } }
        if (!skipSeen || Cmd.ANC_MODE_EVENT !in seen) s.get(Cmd.ANC_MODE_GET)?.let { p -> applyModePairs(Codec.modePairs(p)) }
        if (!skipSeen || Cmd.TRANSPARENCY_EVENT !in seen) s.get(Cmd.TRANSPARENCY_GET)?.let { p -> confirm { copy(transparency = Codec.u8(p)) } }
    }

    private suspend fun readGains(s: GaiaSession, bands: Int) {
        val gains = (0 until bands).map { i -> s.get(Cmd.EQ_GAIN_GET, intArrayOf(i))?.let { Codec.i8(it) } ?: return }
        confirm { copy(gains = gains) }
    }

    private suspend fun readBassAndMode(s: GaiaSession) {
        s.get(Cmd.EQ_SUBMODE_GET)?.let { p -> confirm { copy(submask = Codec.u8(p)) } }
        s.get(Cmd.AUDIO_MODE_GET)?.let { p -> confirm { copy(audioMode = Codec.u16(p)) } }
    }

    private suspend fun readSetting(s: GaiaSession, setting: Setting) {
        when (val r = s.request(setting.get)) {
            is Reply.Ok -> Codec.u8(r.payload)?.let { v -> confirm { copy(settings = settings + (setting.key to v)) } }
            is Reply.Rejected -> confirm { copy(unsupported = unsupported + setting.key) }
            Reply.Timeout -> Unit
        }
    }

    private fun applyModePairs(pairs: Map<Int, Int>?) {
        if (pairs == null) {
            log("!! malformed mode/state list ignored")
            return
        }
        confirm {
            copy(modePairs = modePairs + pairs, adaptive = pairs[Cmd.MODE_ADAPTIVE]?.let { it == 1 } ?: adaptive,
                wind = pairs[Cmd.MODE_WIND] ?: wind)
        }
    }

    private suspend fun GaiaSession.get(base: Int, payload: IntArray = IntArray(0)): ByteArray? =
        when (val r = request(base, payload, RESPONSE_TIMEOUT_MS)) {
            is Reply.Ok -> r.payload
            is Reply.Rejected -> { log("   %04X rejected (code %s)".format(base, r.code)); null }
            Reply.Timeout -> null
        }

    /** Applies a change to both the session-confirmed copy and the shown snapshot. */
    private fun confirm(f: DeviceSnapshot.() -> DeviceSnapshot) {
        confirmed = confirmed.f()
        _state.update { it.copy(snapshot = it.snapshot.f()) }
    }

    private fun onNotification(f: Frame) {
        val p = f.payload
        seen += f.command
        when (f.command) {
            Cmd.MASTER_EVENT -> confirm { copy(master = Codec.bool(p)) }
            Cmd.ANC_MODE_EVENT -> applyModePairs(Codec.modePairs(p))
            Cmd.TRANSPARENCY_EVENT -> confirm { copy(transparency = Codec.u8(p)) }
            Cmd.EQ_GAINS_EVENT -> confirm { copy(gains = Codec.gains(p)) }
            Cmd.EQ_SUBMODE_EVENT -> confirm { copy(submask = Codec.u8(p)) }
            Cmd.AUDIO_MODE_EVENT -> confirm { copy(audioMode = Codec.u16(p)) }
            Cmd.BATTERY_EVENT -> confirm { copy(battery = Codec.battery(p)) }
            Cmd.CHARGING_EVENT -> confirm { copy(charging = Codec.u8(p)) }
            Cmd.CODEC_EVENT -> confirm { copy(codec = Codec.u8(p)) }
            Cmd.TOUCH_LOCK_EVENT -> Codec.u8(p)?.let { v -> confirm { copy(settings = settings + (Setting.TOUCH_LOCK.key to v)) } }
            else -> Unit // e.g. 0898/089A/108B…1093 pushed on registration; undocumented, kept in the frame log only
        }
        _state.update { it.copy(lastSyncedAt = System.currentTimeMillis()) }
    }

    /** Runs steps in order; stops at the first failure and returns its description (null = all OK). */
    private suspend fun runSteps(s: GaiaSession, steps: List<Step>): String? {
        for (step in steps) {
            when (val r = s.request(step.base, step.payload, RESPONSE_TIMEOUT_MS)) {
                is Reply.Ok -> confirmStep(step)
                is Reply.Rejected -> return "Headphones rejected the change (code ${r.code ?: "?"})"
                Reply.Timeout -> return "Headphones didn't respond"
            }
        }
        return null
    }

    private fun confirmStep(step: Step) {
        val p = step.payload
        when (step.base) {
            Cmd.MASTER_SET -> confirm { copy(master = p[0] == 1) }
            Cmd.ANC_MODE_SET -> applyModePairs(mapOf(p[0] to p[1]))
            Cmd.TRANSPARENCY_SET -> confirm { copy(transparency = p[0]) }
            Cmd.EQ_GAIN_SET -> confirm {
                val g = (gains ?: List(eqConfig?.bands ?: 5) { 0 }).toMutableList()
                if (p[0] < g.size) g[p[0]] = p[1].toByte().toInt()
                copy(gains = g)
            }
            Cmd.AUDIO_MODE_SET -> confirm { copy(audioMode = (p[0] shl 8) or p[1]) }
            Cmd.EQ_SUBMODE_SET -> confirm { copy(submask = p[0]) }
            else -> Setting.entries.firstOrNull { it.set == step.base }?.let { st ->
                confirm { copy(settings = settings + (st.key to p[0])) }
            }
        }
    }

    // ---------------------------------------------------------------- actions

    fun refresh() = submit("refresh") { s ->
        when {
            confirmed.master == null -> readAll(s)
            // Session opened by a widget tap skipped the optional controls; the app needs them.
            holds.get() > 0 && confirmed.settings.isEmpty() && confirmed.unsupported.isEmpty() ->
                for (setting in Setting.entries) readSetting(s, setting)
        }
    }

    fun setNoise(target: NoiseControl.Target) = submit("noise", { st ->
        st.copy(
            desired = st.desired.copy(noise = target),
            lastOnMode = when (target) {
                NoiseControl.Target.Adaptive -> NoiseMode.ADAPTIVE
                is NoiseControl.Target.Custom -> NoiseMode.CUSTOM
                NoiseControl.Target.Off -> st.lastOnMode
            },
            lastCustomLevel = (target as? NoiseControl.Target.Custom)?.level ?: st.lastCustomLevel,
        )
    }) { s ->
        val failed = runSteps(s, NoiseControl.steps(target, confirmed.noiseConfirmed()))
        if (failed != null) {
            readNoise(s) // reconcile; never assume earlier steps rolled back
            _state.update { it.copy(error = failed) }
        }
        clearDesired("noise")
    }

    /** The switch: off, or back to the last requested mode. */
    fun toggleNoise() {
        val st = _state.value
        setNoise(
            if (st.shownNoiseMode != NoiseMode.OFF) NoiseControl.Target.Off
            else if (st.lastOnMode == NoiseMode.ADAPTIVE) NoiseControl.Target.Adaptive
            else NoiseControl.Target.Custom(st.lastCustomLevel),
        )
    }

    fun selectStop(index: Int) = setNoise(NoiseControl.Target.Custom(NoiseControl.STOPS[index]))

    fun setWind(value: Int) = submit("wind") { s ->
        val failed = runSteps(s, listOf(Step(Cmd.ANC_MODE_SET, Cmd.MODE_WIND, value)))
        if (failed != null) {
            s.get(Cmd.ANC_MODE_GET)?.let { applyModePairs(Codec.modePairs(it)) }
            _state.update { it.copy(error = failed) }
        }
    }

    /** Applies a full 5-band vector and activates User EQ. */
    fun applyGains(target: List<Int>) = submit("eq", {
        it.copy(desired = it.desired.copy(gains = target, audioMode = Cmd.AUDIO_USER_EQ))
    }) { s ->
        val cfg = confirmed.eqConfig ?: s.get(Cmd.EQ_CONFIG_GET)?.let(Codec::eqConfig)?.also { c -> confirm { copy(eqConfig = c) } }
        val invalid = EqModel.validate(target, cfg)
        if (invalid != null) {
            clearDesired("eq")
            _state.update { it.copy(error = invalid) }
            return@submit
        }
        if (confirmed.gains == null) readGains(s, cfg!!.bands)
        val failed = runSteps(s, EqModel.applySteps(target, confirmed.gains, confirmed.audioMode))
        if (failed != null) {
            readGains(s, cfg!!.bands)
            readBassAndMode(s)
            _state.update { it.copy(error = failed) }
        }
        _state.update { it.copy(bassPrevMode = null) }
        clearDesired("eq")
    }

    /** Applies a preset and remembers it as the starting point for band edits. */
    fun applyPreset(p: Preset) {
        _state.update { it.copy(basePresetId = p.id) }
        applyGains(p.gains)
    }

    fun applyFlat() {
        _state.update { it.copy(basePresetId = null) }
        applyGains(List(_state.value.shownGains?.size ?: 5) { 0 })
    }

    fun setBand(index: Int, gain: Int) {
        val base = _state.value.shownGains ?: List(5) { 0 }
        applyGains(base.toMutableList().also { it[index] = gain })
    }

    fun setAudioMode(mode: Int) = submit("audio", { it.copy(desired = it.desired.copy(audioMode = mode)) }) { s ->
        val failed = runSteps(s, listOf(Step(Cmd.AUDIO_MODE_SET, *Codec.u16Bytes(mode))))
        if (failed != null) {
            readBassAndMode(s)
            _state.update { it.copy(error = failed) }
        }
        _state.update { it.copy(bassPrevMode = null) }
        clearDesired("audio")
    }

    fun setBass(on: Boolean) = submit("bass", { it.copy(desired = it.desired.copy(bass = on)) }) { s ->
        val mask = confirmed.submask ?: s.get(Cmd.EQ_SUBMODE_GET)?.let { Codec.u8(it) }?.also { m -> confirm { copy(submask = m) } }
        if (mask == null) {
            clearDesired("bass")
            _state.update { it.copy(error = "Couldn't read the EQ submode") }
            return@submit
        }
        if (confirmed.audioMode == null) readBassAndMode(s)
        val steps = mutableListOf(Step(Cmd.EQ_SUBMODE_SET, EqModel.bassMask(mask, on)))
        var prev = _state.value.bassPrevMode
        var notice: String? = null
        val mode = confirmed.audioMode
        if (on && mode != Cmd.AUDIO_USER_EQ) {
            prev = mode
            steps += Step(Cmd.AUDIO_MODE_SET, *Codec.u16Bytes(Cmd.AUDIO_USER_EQ))
            notice = "Switched to User EQ for Bass Boost"
        } else if (!on && prev != null && mode == Cmd.AUDIO_USER_EQ) {
            steps += Step(Cmd.AUDIO_MODE_SET, *Codec.u16Bytes(prev))
            notice = "Restored ${EqModel.modeName(prev)}"
            prev = null
        }
        val failed = runSteps(s, steps)
        if (failed != null) {
            readBassAndMode(s)
            _state.update { it.copy(error = failed) }
        } else {
            _state.update { it.copy(bassPrevMode = prev, error = notice ?: it.error) }
        }
        clearDesired("bass")
    }

    fun setTouchLock(on: Boolean) = submit("touch", { it.copy(desired = it.desired.copy(touchLock = on)) }) { s ->
        val failed = runSteps(s, listOf(Step(Setting.TOUCH_LOCK.set, if (on) 1 else 0)))
        if (failed != null) {
            readSetting(s, Setting.TOUCH_LOCK)
            _state.update { it.copy(error = failed) }
        }
        clearDesired("touch")
    }

    fun toggleTouchLock() = setTouchLock(_state.value.shownTouchLock != true)

    fun setSetting(setting: Setting, value: Int) = submit("setting:${setting.key}") { s ->
        val failed = runSteps(s, listOf(Step(setting.set, value)))
        if (failed != null) {
            readSetting(s, setting)
            _state.update { it.copy(error = failed) }
        }
    }

    // ---------------------------------------------------------------- presets (app records, not stored on the headphones)

    private fun publishPresets() {
        _presets.value = Presets(store.presets(), store.favorites)
    }

    fun savePreset(name: String, gains: List<Int>): String? {
        EqModel.nameError(name, null, _presets.value.all)?.let { return it }
        val n = store.nextUserPresetNumber
        store.nextUserPresetNumber = n + 1
        store.saveUserPresets(store.userPresets() + Preset("user$n", name.trim(), gains, builtIn = false))
        publishPresets()
        return null
    }

    fun renamePreset(id: String, name: String): String? {
        EqModel.nameError(name, id, _presets.value.all)?.let { return it }
        store.saveUserPresets(store.userPresets().map { if (it.id == id) it.copy(name = name.trim()) else it })
        publishPresets()
        return null
    }

    class Deleted(val preset: Preset, val index: Int, val favorites: List<String>, val refill: String?)

    fun deletePreset(id: String): Deleted? {
        val users = store.userPresets()
        val index = users.indexOfFirst { it.id == id }
        if (index < 0) return null
        val before = store.favorites
        val remaining = users.filter { it.id != id }
        val r = EqModel.favoritesAfterDelete(before, id, store.presets().filter { it.id != id })
        store.saveUserPresets(remaining)
        store.favorites = r.favorites
        publishPresets()
        return Deleted(users[index], index, before, r.refill)
    }

    fun undoDelete(d: Deleted) {
        val users = store.userPresets().toMutableList()
        users.add(d.index.coerceAtMost(users.size), d.preset)
        store.saveUserPresets(users)
        store.favorites = d.favorites
        publishPresets()
    }

    /** Saves a new list order; favorites follow it on the widget. */
    fun reorderPresets(ids: List<String>) {
        store.presetOrder = ids
        publishPresets()
    }

    fun toggleFavorite(id: String): String? {
        val r = EqModel.toggleFavorite(store.favorites, id)
        if (r.error == null) {
            store.favorites = r.favorites
            publishPresets()
        }
        return r.error
    }

    // ---------------------------------------------------------------- frame log (debug)

    private val logLines = ArrayDeque<String>()
    private val _logVersion = MutableStateFlow(0)
    val logVersion: StateFlow<Int> = _logVersion.asStateFlow()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(line: String) {
        Log.d("M4", line)
        synchronized(logLines) {
            logLines.addLast("${timeFmt.format(Date())} $line")
            while (logLines.size > 400) logLines.removeFirst()
        }
        _logVersion.update { it + 1 }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }
}

/** Optional headphone controls. */
enum class Setting(val key: String, val set: Int, val get: Int) {
    SMART_PAUSE("smartPause", Cmd.SMART_PAUSE_SET, Cmd.SMART_PAUSE_GET),
    ON_HEAD("onHead", Cmd.ON_HEAD_SET, Cmd.ON_HEAD_GET),
    TOUCH_LOCK("touchLock", Cmd.TOUCH_LOCK_SET, Cmd.TOUCH_LOCK_GET),
    SIDETONE("sidetone", Cmd.SIDETONE_SET, Cmd.SIDETONE_GET),
    PAUSE_FULL_TRANSPARENCY("pauseFull", Cmd.PAUSE_FULL_TRANSPARENCY_SET, Cmd.PAUSE_FULL_TRANSPARENCY_GET),
}
