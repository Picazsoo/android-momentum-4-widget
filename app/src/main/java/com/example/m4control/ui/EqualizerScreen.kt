package com.example.m4control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.example.m4control.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.AlertDialog
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.m4control.device.DeviceController
import com.example.m4control.device.EqModel
import com.example.m4control.device.Preset
import com.example.m4control.gaia.Cmd
import com.example.m4control.state.AppState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SOUND_MODES = listOf(Cmd.AUDIO_USER_EQ to "User EQ", Cmd.AUDIO_PODCAST to "Podcast",
    Cmd.AUDIO_PERSONALIZED to "Personal", Cmd.AUDIO_BYPASS to "Bypass")

private class NameDialog(val presetId: String?, val initial: String, val gains: List<Int>)

@Composable
fun EqualizerScreen(st: AppState, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val c = LocalM4.current
    val presets by DeviceController.presets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<NameDialog?>(null) }
    val gains = st.shownGains
    val title = EqModel.editorTitle(st.shownAudioMode, gains, presets.all, st.basePresetId)
    val active = if (st.shownAudioMode == Cmd.AUDIO_USER_EQ) EqModel.match(gains, presets.all) else null

    fun openSave() {
        val g = gains ?: return
        val existing = EqModel.match(g, presets.all)
        if (existing != null) {
            scope.launch { snackbar.showSnackbar("These bands already match ${existing.name}") }
            return
        }
        dialog = NameDialog(null, "My preset ${DeviceController.store.nextUserPresetNumber}", g)
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            // The header names what's being edited; "Equalizer" moves to the subtitle.
            AppBar(title, onBack = onBack, subtitle = "Equalizer", titleAlpha = if (st.eqPending) 0.55f else 1f)
            ScreenColumn {
                StateBanners(st)
                M4Card {
                    Caption("Sound mode", Modifier.padding(bottom = 6.dp))
                    ToggleGroup(SOUND_MODES.map { it.second }, SOUND_MODES.indexOfFirst { it.first == st.shownAudioMode }.takeIf { it >= 0 },
                        { DeviceController.setAudioMode(SOUND_MODES[it].first) })
                }
                BandsCard(st, onFlat = DeviceController::applyFlat, onSave = ::openSave)
                M4Card(padded = false) {
                    SectionTitle("Presets")
                    Caption("Tap a preset to apply and edit it. Drag the handle to reorder. " +
                        "Star up to ${EqModel.FAVORITES} for the widget — it shows them in this order.",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
                    ReorderablePresets(presets.all, active, presets.favorites,
                        onEdit = { p -> dialog = NameDialog(p.id, p.name, p.gains) },
                        onStar = { p -> DeviceController.toggleFavorite(p.id)?.let { m -> scope.launch { snackbar.showSnackbar(m) } } })
                }
                Gap(64)
            }
        }
        FloatingActionButton(onClick = ::openSave, backgroundColor = c.accent, contentColor = c.onAccent,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
            Icon(Icons.Default.Add, "Save current bands as preset")
        }
    }

    dialog?.let { d ->
        PresetNameDialog(d, onDismiss = { dialog = null }, onDelete = {
            dialog = null
            val deleted = DeviceController.deletePreset(d.presetId!!) ?: return@PresetNameDialog
            val refill = deleted.refill?.let { id -> presets.all.firstOrNull { it.id == id }?.name }
            scope.launch {
                val r = snackbar.showSnackbar("Deleted ${deleted.preset.name}" + (refill?.let { " · $it added to favorites" } ?: ""), "Undo")
                if (r == SnackbarResult.ActionPerformed) DeviceController.undoDelete(deleted)
            }
        }) { name ->
            val error = if (d.presetId == null) DeviceController.savePreset(name, d.gains) else DeviceController.renamePreset(d.presetId, name)
            if (error == null) {
                dialog = null
                scope.launch { snackbar.showSnackbar(if (d.presetId == null) "Saved as ${name.trim()}" else "Renamed to ${name.trim()}") }
            }
            error
        }
    }
}

@Composable
private fun BandsCard(st: AppState, onFlat: () -> Unit, onSave: () -> Unit) {
    val c = LocalM4.current
    val cfg = st.snapshot.eqConfig
    val min = cfg?.minGain ?: -60
    val max = cfg?.maxGain ?: 60
    val span = (max - min).coerceAtLeast(1)
    val drag = remember { mutableStateMapOf<Int, Int>() }
    val gains = st.shownGains
    M4Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bands", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.fg, modifier = Modifier.weight(1f))
            Caption("${EqModel.fmtDb(min)} to ${EqModel.fmtDb(max)}")
        }
        EqModel.BAND_LABELS.forEachIndexed { i, hz ->
            val g = drag[i] ?: gains?.getOrNull(i)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hz, fontSize = 13.sp, color = c.fg2, modifier = Modifier.width(58.dp))
                CenterFillSlider(
                    position = ((g ?: 0) - min).toFloat() / span,
                    onChange = { drag[i] = (min + it * span).let { v -> (v / 5f).roundToInt() * 5 }.coerceIn(min, max) },
                    onFinished = { drag.remove(i)?.let { DeviceController.setBand(i, it) } },
                    fill = c.accent, thumb = if (g == null) c.track else c.accent,
                    center = (0 - min).toFloat() / span,
                    enabled = gains != null,
                    modifier = Modifier.weight(1f).alpha(if (st.eqPending && drag[i] == null) 0.55f else 1f),
                )
                Text(g?.let { EqModel.fmtDb(it) } ?: "—", fontSize = 13.sp, color = c.fg, modifier = Modifier.width(62.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            TextButton(onClick = onFlat, enabled = gains != null) { Text("FLAT", color = c.accentText, fontWeight = FontWeight.Medium) }
            TextButton(onClick = onSave, enabled = gains != null) { Text("SAVE AS PRESET", color = c.accentText, fontWeight = FontWeight.Medium) }
        }
    }
}

/**
 * Preset list with drag-to-reorder. The dragged row follows the finger and swaps with a neighbour once
 * it has moved half a row; the new order is saved when the finger lifts.
 */
@Composable
private fun ReorderablePresets(all: List<Preset>, active: Preset?, favorites: List<String>,
                               onEdit: (Preset) -> Unit, onStar: (Preset) -> Unit) {
    val c = LocalM4.current
    val haptics = LocalHapticFeedback.current
    val order = remember { mutableStateListOf<Preset>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    // Follow outside changes (save, rename, delete, undo) except mid-drag.
    LaunchedEffect(all) { if (dragId == null) { order.clear(); order.addAll(all) } }

    fun move(from: Int, to: Int) {
        if (from == to || to !in order.indices) return
        order.add(to, order.removeAt(from))
    }
    fun commit() {
        dragId = null
        dragOffset = 0f
        DeviceController.reorderPresets(order.map { it.id })
    }

    order.forEachIndexed { i, p ->
        key(p.id) {
            val dragging = p.id == dragId
            Column(Modifier
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer {
                    translationY = if (dragging) dragOffset else 0f
                    shadowElevation = if (dragging) 8.dp.toPx() else 0f
                }
                .background(c.card)
                .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }) {
                if (i > 0 && !dragging) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                val handle = Modifier
                    .pointerInput(p.id) { detectTapGestures { } } // a tap on the handle doesn't apply the preset
                    .pointerInput(p.id) {
                        detectDragGestures(
                            onDragStart = {
                                dragId = p.id
                                dragOffset = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = ::commit,
                            onDragCancel = ::commit,
                        ) { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            val idx = order.indexOfFirst { it.id == dragId }
                            if (dragOffset > rowHeight / 2f && idx < order.lastIndex) {
                                move(idx, idx + 1); dragOffset -= rowHeight
                            } else if (dragOffset < -rowHeight / 2f && idx > 0) {
                                move(idx, idx - 1); dragOffset += rowHeight
                            }
                        }
                    }
                    .semantics {
                        contentDescription = "Reorder ${p.name}"
                        customActions = listOf(
                            CustomAccessibilityAction("Move up") { val k = order.indexOf(p); move(k, k - 1); commit(); true },
                            CustomAccessibilityAction("Move down") { val k = order.indexOf(p); move(k, k + 1); commit(); true },
                        )
                    }
                PresetRow(p, selected = p == active, favorite = p.id in favorites, handle = handle,
                    onApply = { DeviceController.applyPreset(p) }, onEdit = { onEdit(p) }, onStar = { onStar(p) })
            }
        }
    }
}

@Composable
private fun PresetRow(p: Preset, selected: Boolean, favorite: Boolean, handle: Modifier,
                      onApply: () -> Unit, onEdit: () -> Unit, onStar: () -> Unit) {
    val c = LocalM4.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onApply).padding(end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(handle.width(32.dp).height(48.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(ImageVector.vectorResource(R.drawable.ic_drag_handle), null, tint = c.fg3, modifier = Modifier.size(20.dp))
        }
        RadioButton(selected = selected, onClick = onApply, colors = RadioButtonDefaults.colors(selectedColor = c.accentText))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(p.name, fontSize = 15.sp, color = c.fg)
            Text((if (p.builtIn) "" else "Your preset · ") + p.gains.joinToString(" · ") { "%.1f".format(it / 10.0) },
                fontSize = 13.sp, color = c.fg2)
        }
        if (!p.builtIn) IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit ${p.name}", tint = c.fg3) }
        IconButton(onClick = onStar) {
            Icon(Icons.Default.Star, if (favorite) "Remove from favorites" else "Add to favorites",
                tint = if (favorite) c.accentText else c.fg3.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun PresetNameDialog(d: NameDialog, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String) -> String?) {
    val c = LocalM4.current
    var value by remember { mutableStateOf(TextFieldValue(d.initial, TextRange(0, d.initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        backgroundColor = c.card,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        title = { Text(if (d.presetId == null) "Save preset" else "Edit preset", color = c.fg) },
        text = {
            Column {
                TextField(
                    value = value,
                    onValueChange = { if (it.text.length <= EqModel.NAME_MAX) { value = it; error = null } },
                    label = { Text("Preset name") },
                    singleLine = true,
                    isError = error != null,
                    colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, textColor = c.fg,
                        focusedIndicatorColor = c.accent, focusedLabelColor = c.accentText, cursorColor = c.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(error ?: "", color = c.busy, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${value.text.length} / ${EqModel.NAME_MAX}", color = c.fg2, fontSize = 12.sp)
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        },
        buttons = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (d.presetId != null) TextButton(onClick = onDelete) { Text("DELETE", color = c.busy, fontWeight = FontWeight.Medium) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("CANCEL", color = c.accentText, fontWeight = FontWeight.Medium) }
                TextButton(onClick = { error = onSave(value.text) }) { Text("SAVE", color = c.accentText, fontWeight = FontWeight.Medium) }
            }
        },
    )
}
