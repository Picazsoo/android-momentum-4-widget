package cz.metlicka.m4control.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.metlicka.m4control.device.DeviceController

/** App-specific Material 1 colors on top of MaterialTheme (same palette as the widget mockup). */
@Immutable
data class M4Colors(
    val bar: Color,
    val bg: Color,
    val card: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val divider: Color,
    val selected: Color,
    val track: Color,
    val anc: Color,
    val ancText: Color,
    val trans: Color,
    val transText: Color,
    val accent: Color,
    val accentText: Color,
    val onAccent: Color,
    val ok: Color,
    val busy: Color,
    val busyBg: Color,
    val infoBg: Color,
)

private val Light = M4Colors(
    bar = Color(0xFF455A64), bg = Color(0xFFFAFAFA), card = Color.White,
    fg = Color(0xDE000000), fg2 = Color(0x8A000000), fg3 = Color(0x61000000),
    divider = Color(0x1F000000), selected = Color(0x14000000), track = Color(0x42000000),
    anc = Color(0xFF1E88E5), ancText = Color(0xFF1976D2), trans = Color(0xFFFB8C00), transText = Color(0xFFE65100),
    accent = Color(0xFF009688), accentText = Color(0xFF00796B), onAccent = Color.White,
    ok = Color(0xFF43A047), busy = Color(0xFFF4511E), busyBg = Color(0xFFFBE9E7), infoBg = Color(0xFFECEFF1),
)

private val Dark = M4Colors(
    bar = Color(0xFF212121), bg = Color(0xFF303030), card = Color(0xFF424242),
    fg = Color.White, fg2 = Color(0xB3FFFFFF), fg3 = Color(0x80FFFFFF),
    divider = Color(0x1FFFFFFF), selected = Color(0x1FFFFFFF), track = Color(0x4DFFFFFF),
    anc = Color(0xFF64B5F6), ancText = Color(0xFF90CAF9), trans = Color(0xFFFFB74D), transText = Color(0xFFFFCC80),
    accent = Color(0xFF64FFDA), accentText = Color(0xFF64FFDA), onAccent = Color(0xDE000000),
    ok = Color(0xFF69F0AE), busy = Color(0xFFFF8A65), busyBg = Color(0x24FF8A65), infoBg = Color(0x14FFFFFF),
)

val LocalM4 = staticCompositionLocalOf { Light }

/** Settings → "Theme" ("system", "light" or "dark"); read once from the store, then kept in sync by [setTheme]. */
val themeMode = mutableStateOf(DeviceController.store.theme)

fun setTheme(mode: String) {
    DeviceController.store.theme = mode
    themeMode.value = mode
}

@Composable
fun isDarkTheme(): Boolean = when (themeMode.value) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun M4Theme(content: @Composable () -> Unit) {
    val dark = isDarkTheme()
    val c = if (dark) Dark else Light
    val colors = if (dark) {
        darkColors(primary = c.bar, primaryVariant = Color.Black, secondary = c.accent, background = c.bg,
            surface = c.card, onSecondary = c.onAccent)
    } else {
        lightColors(primary = c.bar, primaryVariant = Color(0xFF37474F), secondary = c.accent, background = c.bg,
            surface = c.card, onSecondary = c.onAccent)
    }
    CompositionLocalProvider(LocalM4 provides c) {
        MaterialTheme(
            colors = colors,
            shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(16.dp)),
            content = content,
        )
    }
}
