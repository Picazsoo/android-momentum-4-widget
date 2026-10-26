package cz.metlicka.m4control.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.metlicka.m4control.R
import cz.metlicka.m4control.device.DeviceController
import cz.metlicka.m4control.device.EqModel
import cz.metlicka.m4control.device.NoiseControl
import cz.metlicka.m4control.device.NoiseMode
import cz.metlicka.m4control.gaia.Codec
import cz.metlicka.m4control.state.AppState
import cz.metlicka.m4control.state.LinkState
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { M4Theme { App() } }
        if (cz.metlicka.m4control.BuildConfig.DEBUG) cz.metlicka.m4control.widget.WidgetUpdater.selfTest(this)
    }

    override fun onStart() {
        super.onStart()
        DeviceController.onPermissionChanged()
        DeviceController.hold(true)
    }

    override fun onStop() {
        DeviceController.hold(false)
        super.onStop()
    }
}

@Composable
private fun App() {
    val c = LocalM4.current
    val st by DeviceController.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("home") }
    val scaffold = rememberScaffoldState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        DeviceController.onPermissionChanged()
    }
    BackHandler(screen != "home") { screen = "home" }
    LaunchedEffect(st.error) {
        st.error?.let {
            scaffold.snackbarHostState.showSnackbar(it)
            DeviceController.clearError()
        }
    }
    val shown = when {
        st.link == LinkState.NO_PERMISSION -> "permission"
        st.link == LinkState.NO_DEVICE && screen == "home" -> "pick"
        else -> screen
    }
    Scaffold(scaffoldState = scaffold, backgroundColor = c.bg,
        snackbarHost = { SnackbarHost(it, Modifier.navigationBarsPadding()) }) { pad ->
        Box(Modifier.fillMaxSize().background(c.bg).padding(bottom = pad.calculateBottomPadding())) {
            when (shown) {
                "permission" -> PermissionScreen { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                "pick" -> PickerScreen(st, onBack = if (st.link == LinkState.NO_DEVICE) null else ({ screen = "home" })) { screen = "home" }
                "eq" -> EqualizerScreen(st, scaffold.snackbarHostState) { screen = "home" }
                "settings" -> SettingsScreen(st, onBack = { screen = "home" }, onPick = { screen = "pick" })
                else -> HomeScreen(st, onEq = { screen = "eq" }, onSettings = { screen = "settings" })
            }
        }
    }
}

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)
        .navigationBarsPadding()) {
        content()
        Gap(24)
    }
}

@Composable
fun StateBanners(st: AppState) {
    when (st.link) {
        LinkState.DISCONNECTED -> Banner(painterIconInfo(), "Headphones not connected. Showing last known settings from ${agoText(st.lastSyncedAt)}.")
        LinkState.UNAVAILABLE -> Banner(Icons.Default.Warning, "Control connection unavailable. Audio is connected, but the headphones didn't accept a control connection.",
            warn = true, action = "Retry") { DeviceController.refresh() }
        else -> Unit
    }
    if (!st.firmwareSupported) {
        Banner(Icons.Default.Info, "Untested firmware ${st.snapshot.firmware}. Controls should work, but this version hasn't been qualified.")
    }
}

private fun painterIconInfo() = Icons.Default.Info

@Composable
private fun HomeScreen(st: AppState, onEq: () -> Unit, onSettings: () -> Unit) {
    Column {
        AppBar("M4 Control") {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
        }
        ScreenColumn {
            StateBanners(st)
            DeviceCard(st)
            val dim = if (st.link == LinkState.CONNECTED) 1f else 0.45f
            Box(Modifier.alpha(dim)) { NoiseCard(st) }
            Box(Modifier.alpha(dim)) { EqCard(st, onEq) }
        }
    }
}

@Composable
private fun DeviceCard(st: AppState) {
    val c = LocalM4.current
    val s = st.snapshot
    // Compact: icon, name + link dot, and one row of pills (battery, codec, freshness).
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), backgroundColor = c.card, elevation = 2.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.selected), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_headset), null, tint = c.fg2, modifier = Modifier.size(20.dp))
            }
            RowGap(12)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.deviceName ?: "Headphones", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.fg,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    RowGap(8)
                    StatusDot(when (st.link) {
                        LinkState.CONNECTED -> c.ok
                        LinkState.UNAVAILABLE -> c.busy
                        else -> c.fg3
                    })
                    if (st.connecting) {
                        RowGap(8)
                        Caption("Connecting…")
                    }
                }
                Gap(3)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val battery = s.battery?.let { "$it%" } ?: "—"
                    Chip(if (s.charging == 1) "$battery ⚡" else battery)
                    if (st.link == LinkState.CONNECTED) s.codec?.let { Codec.CODECS[it] }?.let { Chip(it) }
                    Chip(if (st.sessionOpen) "Live" else if (st.stale) "Last known · ${agoText(st.lastSyncedAt)}" else "Updated ${agoText(st.lastSyncedAt)}")
                }
            }
        }
    }
}

@Composable
private fun NoiseCard(st: AppState) {
    val c = LocalM4.current
    var drag by remember { mutableStateOf<Float?>(null) }
    val mode = if (drag != null) NoiseMode.CUSTOM else st.shownNoiseMode
    val level = drag?.let { 100 - (it * 100).roundToInt() } ?: st.shownLevel
    val r = NoiseControl.readout(mode, level)
    val tone = when (r.tone) {
        NoiseControl.Tone.ANC -> c.ancText
        NoiseControl.Tone.TRANSPARENCY -> c.transText
        NoiseControl.Tone.ADAPTIVE -> c.accentText
        NoiseControl.Tone.BALANCED -> c.fg
        else -> c.fg3
    }
    M4Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Noise control", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.fg)
                Text(r.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tone,
                    modifier = Modifier.alpha(if (st.noisePending) 0.55f else 1f))
            }
            Switch(checked = mode != null && mode != NoiseMode.OFF, onCheckedChange = { DeviceController.toggleNoise() },
                colors = SwitchDefaults.colors(checkedThumbColor = c.accent, checkedTrackColor = c.accent))
        }
        Column(Modifier.alpha(if (mode == NoiseMode.OFF) 0.45f else 1f)) {
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Adaptive", mode == NoiseMode.ADAPTIVE, true, Modifier.weight(1f)) {
                    DeviceController.setNoise(NoiseControl.Target.Adaptive)
                }
                ModeButton("Custom", mode == NoiseMode.CUSTOM, false, Modifier.weight(1f)) {
                    DeviceController.setNoise(NoiseControl.Target.Custom(st.lastCustomLevel))
                }
            }
            Gap(4)
            val pos = (100 - level) / 100f
            val fill = if (level > 50) c.anc else c.trans
            CenterFillSlider(
                position = pos,
                onChange = { drag = it },
                onFinished = {
                    drag?.let { DeviceController.setNoise(NoiseControl.Target.Custom(100 - (it * 100).roundToInt())) }
                    drag = null
                },
                fill = fill,
                thumb = when (mode) {
                    NoiseMode.CUSTOM -> if (level == 50) c.fg2 else fill
                    NoiseMode.OFF -> c.track
                    else -> Color.Transparent
                },
                showFill = mode == NoiseMode.CUSTOM,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cancelling", fontSize = 12.sp, color = c.ancText)
                Text("Balanced", fontSize = 12.sp, color = c.fg3)
                Text("Transparency", fontSize = 12.sp, color = c.transText)
            }
            Gap(14)
            Caption("Wind reduction", Modifier.padding(bottom = 6.dp))
            ToggleGroup(listOf("Off", "Max", "Auto"), st.snapshot.wind, { DeviceController.setWind(it) })
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, accent: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalM4.current
    val bg = if (!selected) Color.Transparent else if (accent) c.accent else c.selected
    val fg = if (!selected) c.fg2 else if (accent) c.onAccent else c.fg
    Box(modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(bg)
        .then(if (selected && accent) Modifier else Modifier.border(1.dp, c.divider, RoundedCornerShape(8.dp)))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label.uppercase(), color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun EqCard(st: AppState, onEq: () -> Unit) {
    val c = LocalM4.current
    val presets by DeviceController.presets.collectAsStateWithLifecycle()
    val label = EqModel.label(st.shownAudioMode, st.shownGains, presets.all)
    val active = if (st.shownAudioMode == 1) EqModel.match(st.shownGains, presets.all) else null
    val favs = EqModel.favoritePresets(presets.all, presets.favorites)
    M4Card(padded = false) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    Text(label, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = c.fg,
                        modifier = Modifier.alpha(if (st.eqPending) 0.55f else 1f))
                }
                TextButton(onClick = onEq) { Text("ALL PRESETS", color = c.accentText, fontWeight = FontWeight.Medium) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Caption("Widget favorites", Modifier.padding(top = 6.dp, bottom = 6.dp))
            ToggleGroup(favs.map { it.name }, favs.indexOf(active).takeIf { it >= 0 },
                { DeviceController.applyPreset(favs[it]) }, dim = st.eqPending)
        }
        Gap(8)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.divider))
        SwitchRow("Bass Boost", st.shownBass, { DeviceController.setBass(it) },
            icon = ImageVector.vectorResource(R.drawable.ic_graphic_eq))
    }
}
