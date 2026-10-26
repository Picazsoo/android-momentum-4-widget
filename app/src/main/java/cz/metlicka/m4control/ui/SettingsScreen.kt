package cz.metlicka.m4control.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.metlicka.m4control.BuildConfig
import cz.metlicka.m4control.R
import cz.metlicka.m4control.device.DeviceController
import cz.metlicka.m4control.device.Setting
import cz.metlicka.m4control.state.AppState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(st: AppState, onBack: () -> Unit, onPick: () -> Unit) {
    val c = LocalM4.current
    val s = st.snapshot
    var showLog by remember { mutableStateOf(false) }
    Column {
        AppBar("Settings", onBack = onBack)
        ScreenColumn {
            StateBanners(st)
            M4Card(padded = false) {
                SectionTitle("Headphones")
                fun bool(set: Setting) = s.settings[set.key]?.let { it != 0 }
                fun ok(set: Setting) = set.key !in s.unsupported
                SwitchRow("Smart Pause", bool(Setting.SMART_PAUSE), { DeviceController.setSetting(Setting.SMART_PAUSE, if (it) 1 else 0) },
                    subtitle = "Pause when you take the headphones off", enabled = ok(Setting.SMART_PAUSE))
                SwitchRow("On-head detection", bool(Setting.ON_HEAD), { DeviceController.setSetting(Setting.ON_HEAD, if (it) 1 else 0) },
                    enabled = ok(Setting.ON_HEAD))
                SwitchRow("Lock touch controls", st.shownTouchLock, { DeviceController.setTouchLock(it) },
                    enabled = ok(Setting.TOUCH_LOCK))
                SidetoneRow(s.settings[Setting.SIDETONE.key], ok(Setting.SIDETONE))
                SwitchRow("Pause at full transparency", bool(Setting.PAUSE_FULL_TRANSPARENCY),
                    { DeviceController.setSetting(Setting.PAUSE_FULL_TRANSPARENCY, if (it) 1 else 0) },
                    enabled = ok(Setting.PAUSE_FULL_TRANSPARENCY), badge = "Unverified")
            }
            M4Card(padded = false) {
                SectionTitle("Device")
                InfoRow("Headphones", st.deviceName ?: "Not chosen", chevron = true, onClick = onPick)
                InfoRow("Firmware", s.firmware?.let { it + if (st.firmwareSupported) " · supported" else " · untested" } ?: "—")
                InfoRow("Model", s.model ?: "—")
            }
            M4Card(padded = false) {
                SectionTitle("About")
                val ctx = LocalContext.current
                InfoRow("Add widget to home screen", "Noise control, favorites and Bass Boost", chevron = true) {
                    val mgr = ctx.getSystemService(android.appwidget.AppWidgetManager::class.java)
                    if (mgr.isRequestPinAppWidgetSupported) {
                        mgr.requestPinAppWidget(android.content.ComponentName(ctx, cz.metlicka.m4control.widget.MomentumWidgetProvider::class.java), null, null)
                    }
                }
                var lockEnabled by remember { mutableStateOf(DeviceController.store.widgetLockEnabled) }
                SwitchRow("Lock button on widget", lockEnabled, {
                    lockEnabled = it
                    DeviceController.store.widgetLockEnabled = it
                    cz.metlicka.m4control.widget.WidgetUpdater.updateAll(ctx)
                }, subtitle = "A lock in the widget's corner blocks every tap on it, including opening the app")
                InfoRow("Frame log", "Recent control traffic", chevron = true) { showLog = true }
                InfoRow("M4 Control", "Version ${BuildConfig.VERSION_NAME} (sideload)")
            }
        }
    }
    if (showLog) FrameLogDialog { showLog = false }
}

@Composable
private fun SidetoneRow(value: Int?, enabled: Boolean) {
    val c = LocalM4.current
    var drag by remember { mutableStateOf<Float?>(null) }
    val v = drag?.roundToInt() ?: value
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sidetone", fontSize = 15.sp, color = if (enabled) c.fg else c.fg3)
                Text("Hear your own voice in calls", fontSize = 13.sp, color = c.fg2)
            }
            Text(v?.toString() ?: "—", fontSize = 13.sp, color = c.fg2)
        }
        CenterFillSlider(
            position = (v ?: 0) / 4f, onChange = { drag = it * 4 },
            onFinished = { drag?.roundToInt()?.let { DeviceController.setSetting(Setting.SIDETONE, it) }; drag = null },
            fill = c.accent, thumb = c.accent, center = 0f, steps = 3, enabled = enabled && value != null,
        )
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String, chevron: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalM4.current
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.fg)
            Text(subtitle, fontSize = 13.sp, color = c.fg2)
        }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.fg3)
    }
}

@Composable
private fun FrameLogDialog(onDismiss: () -> Unit) {
    val c = LocalM4.current
    val ctx = LocalContext.current
    val version by DeviceController.logVersion.collectAsStateWithLifecycle()
    val text = remember(version) { DeviceController.logText() }
    AlertDialog(
        onDismissRequest = onDismiss,
        backgroundColor = c.card,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Frame log", color = c.fg) },
        text = {
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState())) {
                Text(text.ifEmpty { "No traffic yet." }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = c.fg)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = c.accentText) } },
        dismissButton = {
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("M4 frame log", text))
            }) { Text("COPY", color = c.accentText) }
        },
    )
}

@Composable
fun PickerScreen(st: AppState, onBack: (() -> Unit)?, onDone: () -> Unit) {
    val c = LocalM4.current
    val devices = remember(st.link) { DeviceController.link.bondedDevices() }
    var chosen by remember { mutableStateOf(st.deviceAddress ?: devices.firstOrNull()?.address) }
    Column {
        AppBar("Choose headphones", onBack = onBack)
        ScreenColumn {
            M4Card(padded = false) {
                SectionTitle("Paired devices")
                if (devices.isEmpty()) Text("No paired devices.", color = c.fg2, modifier = Modifier.padding(16.dp))
                devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = d.address }.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == d.address, onClick = { chosen = d.address },
                            colors = RadioButtonDefaults.colors(selectedColor = c.accentText))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.name, fontSize = 15.sp, color = c.fg)
                                if (d.name.contains("MOMENTUM 4", ignoreCase = true)) {
                                    Text(" RECOMMENDED", fontSize = 10.sp, color = c.accentText, fontWeight = FontWeight.Medium)
                                }
                            }
                            Text(d.address, fontSize = 13.sp, color = c.fg2)
                        }
                        if (d.isHeadset) Icon(painterResource(R.drawable.ic_headset), null, tint = c.fg3, modifier = Modifier.padding(end = 12.dp).size(20.dp))
                    }
                }
            }
            Banner(Icons.Default.Info, "Not listed? Pair the headphones in Android Bluetooth settings first, then come back.")
            Gap(8)
            Button(
                onClick = {
                    val d = devices.firstOrNull { it.address == chosen } ?: return@Button
                    DeviceController.selectDevice(d.address, d.name)
                    onDone()
                },
                enabled = chosen != null,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = c.accent, contentColor = c.onAccent),
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            ) { Text("USE THIS DEVICE", fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    val c = LocalM4.current
    Column {
        AppBar("M4 Control")
        ScreenColumn {
            M4Card {
                Text("Allow Nearby devices", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = c.fg)
                Gap(8)
                Text("M4 Control talks to your paired MOMENTUM 4 over Bluetooth. Android needs your permission for the app to connect to paired devices.",
                    color = c.fg2, fontSize = 14.sp)
                Gap(16)
                Button(onClick = onRequest, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = c.accent, contentColor = c.onAccent)) {
                    Text("ALLOW", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
