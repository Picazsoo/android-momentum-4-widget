package com.example.m4control.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, titleAlpha: Float = 1f,
           actions: @Composable RowScope.() -> Unit = {}) {
    val c = LocalM4.current
    Box(Modifier.fillMaxWidth().background(c.bar).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White.copy(alpha = titleAlpha), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
            }
            actions()
        }
    }
}

@Composable
fun M4Card(modifier: Modifier = Modifier, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalM4.current
    Card(modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), backgroundColor = c.card, elevation = 2.dp) {
        Column(if (padded) Modifier.padding(16.dp) else Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = LocalM4.current.accentText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalM4.current.fg2, fontSize = 12.sp, modifier = modifier)
}

@Composable
fun Banner(icon: ImageVector, text: String, warn: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    val c = LocalM4.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(if (warn) c.busyBg else c.infoBg).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
        Icon(icon, null, tint = if (warn) c.busy else c.fg2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = c.fg, fontSize = 13.sp)
            if (action != null) TextButton(onClick = onAction) { Text(action.uppercase(), color = c.accentText, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun Chip(text: String, icon: ImageVector? = null) {
    val c = LocalM4.current
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.selected).padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = c.fg2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = c.fg2, fontSize = 12.sp)
    }
}

@Composable
fun StatusDot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

/** Outlined Material 1 toggle-button group. */
@Composable
fun ToggleGroup(options: List<String>, selected: Int?, onSelect: (Int) -> Unit, enabled: Boolean = true, dim: Boolean = false) {
    val c = LocalM4.current
    Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.divider, RoundedCornerShape(8.dp))) {
        options.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
            val on = i == selected
            Box(Modifier.weight(1f).fillMaxHeight().background(if (on) c.selected else Color.Transparent)
                .clickable(enabled = enabled) { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(label.uppercase(), color = (if (on) c.fg else c.fg2).copy(alpha = if (on && dim) 0.55f else 1f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean?, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null,
              enabled: Boolean = true, badge: String? = null, dim: Boolean = false) {
    val c = LocalM4.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled && checked != null) { onChange(checked != true) }
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = c.fg2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (enabled) c.fg else c.fg3, fontSize = 15.sp)
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(badge.uppercase(), color = c.busy, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.border(1.dp, c.busy, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp))
                }
            }
            if (subtitle != null) Text(subtitle, color = c.fg2, fontSize = 13.sp)
        }
        Switch(checked = checked == true, onCheckedChange = { onChange(it) }, enabled = enabled && checked != null,
            modifier = Modifier.then(if (dim) Modifier.padding(0.dp) else Modifier),
            colors = SwitchDefaults.colors(checkedThumbColor = c.accent, checkedTrackColor = c.accent))
    }
}

/**
 * Material 1 slider whose colored fill grows from [center] (0..1) toward the thumb, like the widget.
 * Track and fill are drawn here; the Slider only supplies the thumb and gestures.
 */
@Composable
fun CenterFillSlider(
    position: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    fill: Color,
    thumb: Color,
    modifier: Modifier = Modifier,
    center: Float = 0.5f,
    steps: Int = 0,
    enabled: Boolean = true,
    showFill: Boolean = true,
) {
    val c = LocalM4.current
    Box(modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 10.dp)) {
            val r = CornerRadius(2.dp.toPx())
            drawRoundRect(c.track, cornerRadius = r)
            if (showFill) {
                val a = size.width * minOf(center, position)
                val b = size.width * maxOf(center, position)
                drawRoundRect(fill, topLeft = Offset(a, 0f), size = Size(b - a, size.height), cornerRadius = r)
            }
        }
        Slider(
            value = position, onValueChange = onChange, onValueChangeFinished = onFinished, steps = steps, enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = thumb, disabledThumbColor = thumb,
                activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent, disabledInactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

fun agoText(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "never"
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun RowGap(w: Int = 8) = Spacer(Modifier.width(w.dp))

val SpaceBetween = Arrangement.SpaceBetween
