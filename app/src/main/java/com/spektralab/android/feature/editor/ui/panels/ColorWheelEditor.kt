package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.color.ColorWheels
import com.spektralab.android.core.color.WheelAdjustment
import com.spektralab.android.core.color.WheelZone
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.*

@Composable
internal fun ColorWheelEditor(vm: EditorViewModel) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val zone = WheelZone.entries[selectedIndex]
    val wheels = vm.params.colorWheels
    val value = wheels[zone]
    val enabled = vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val names = listOf(R.string.wheels_master, R.string.wheels_shadows, R.string.wheels_midtones,
        R.string.wheels_highlights).map { stringResource(it) }
    DisposableEffect(vm.currentUri, zone) { onDispose { vm.finishWheelEdit(cancel = true) } }

    fun apply(wheels: ColorWheels) {
        vm.beginWheelEdit()
        vm.changeWheels(wheels)
        vm.finishWheelEdit()
    }
    fun change(value: WheelAdjustment) = vm.changeWheels(vm.params.colorWheels.with(zone, value))

    EditorSection(stringResource(R.string.wheels_title), stringResource(R.string.wheels_summary)) {
        if (vm.params.unreadableColorWheels != null) {
            Text(stringResource(R.string.wheels_unsupported), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { apply(ColorWheels.NEUTRAL) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wheels_reset_all)) }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WheelZone.entries.forEach { item ->
                    val chosen = item == zone
                    val selectionLabel = stringResource(R.string.wheels_select_zone, names[item.ordinal])
                    TextButton(onClick = { vm.finishWheelEdit(); selectedIndex = item.ordinal }, enabled = enabled,
                        shape = TheiaShapes.control, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = chosen; contentDescription = selectionLabel },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (chosen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )) { Text(names[item.ordinal], style = MaterialTheme.typography.labelLarge) }
                }
            }
            ColorDisc(value, enabled, vm) { hue, strength -> change(vm.params.colorWheels[zone].copy(hue = hue, strength = strength)) }
            Text(stringResource(R.string.wheels_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            WheelSlider(stringResource(R.string.wheels_hue), value.hue, 0f..360f, "${value.hue.roundToInt()}°", enabled, vm) {
                change(vm.params.colorWheels[zone].copy(hue = it))
            }
            WheelSlider(stringResource(R.string.wheels_strength), value.strength, 0f..100f, "${value.strength.roundToInt()}%", enabled, vm) {
                change(vm.params.colorWheels[zone].copy(strength = it))
            }
            WheelSlider(stringResource(R.string.wheels_luminance), value.luminance, -100f..100f,
                stringResource(R.string.wheels_signed_value, value.luminance.roundToInt()), enabled, vm) {
                change(vm.params.colorWheels[zone].copy(luminance = it))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { apply(wheels.with(zone, WheelAdjustment())) }, enabled = enabled && !value.isDefault,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.wheels_reset_zone)) }
                TextButton(onClick = { apply(ColorWheels.NEUTRAL) }, enabled = enabled && !wheels.isDefault,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.wheels_reset_all)) }
            }
        }
    }
}

@Composable
private fun ColorDisc(value: WheelAdjustment, enabled: Boolean, vm: EditorViewModel, onChange: (Float, Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val lastHue by rememberUpdatedState(value.hue)
    val label = stringResource(R.string.wheels_disc)
    val description = stringResource(R.string.wheels_disc_value, value.hue.roundToInt(), value.strength.roundToInt())
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val marker = MaterialTheme.colorScheme.onSurface
    val discSize = if (LocalConfiguration.current.screenHeightDp < 500) 128.dp else 192.dp
    // Clockwise screen angles are the inverse of the model's hue angle.
    val spectrum = remember { Brush.sweepGradient(List(7) { index -> Color.hsv(((360 - index * 60) % 360).toFloat(), .72f, .9f) }) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(discSize).semantics { contentDescription = label; stateDescription = description }
            .pointerInput(enabled, vm) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f - 12.dp.toPx()
                    if ((down.position - center).getDistance() > radius + 8.dp.toPx()) return@awaitEachGesture
                    down.consume()
                    vm.beginWheelEdit()
                    var completed = false
                    fun update(position: Offset) {
                        val delta = position - center
                        val distance = (delta.getDistance() / radius).coerceIn(0f, 1f)
                        // Keep the last angle at the neutral centre; zero strength has no colour cast.
                        val hue = if (distance < .015f) lastHue else
                            ((atan2(-delta.y, delta.x) * 180f / PI.toFloat()) + 360f) % 360f
                        change(hue, if (distance < .015f) 0f else distance * distance * 100f)
                    }
                    try {
                        update(down.position)
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (event.changes.size != 1 || pointer.isConsumed) break
                            update(pointer.position); pointer.consume()
                            if (!pointer.pressed) { completed = true; vm.finishWheelEdit(); break }
                        } while (true)
                    } finally { if (!completed) vm.finishWheelEdit(cancel = true) }
                }
            }) {
            val radius = size.minDimension / 2f - 12.dp.toPx()
            val alpha = if (enabled) 1f else .38f
            drawCircle(spectrum, radius, alpha = alpha)
            drawCircle(Brush.radialGradient(listOf(surface, Color.Transparent), center, radius), radius, alpha = alpha)
            drawCircle(outline, radius, style = Stroke(1.dp.toPx()))
            drawLine(outline, center - Offset(5.dp.toPx(), 0f), center + Offset(5.dp.toPx(), 0f), 1.dp.toPx())
            drawLine(outline, center - Offset(0f, 5.dp.toPx()), center + Offset(0f, 5.dp.toPx()), 1.dp.toPx())
            val angle = value.hue * PI.toFloat() / 180f
            val distance = sqrt(value.strength / 100f) * radius
            val handle = center + Offset(cos(angle), -sin(angle)) * distance
            drawCircle(surface, 8.dp.toPx(), handle)
            drawCircle(marker, 6.dp.toPx(), handle, style = Stroke(2.dp.toPx()), alpha = alpha)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WheelSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, readout: String,
                        enabled: Boolean, vm: EditorViewModel, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val resetLabel = stringResource(R.string.wheels_reset_parameter, label)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(readout, style = MaterialTheme.typography.labelMedium, color = accent)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Slider(value = value, valueRange = range, enabled = enabled,
                    onValueChange = { vm.beginWheelEdit(); onChange(it) }, onValueChangeFinished = { vm.finishWheelEdit() },
                    thumb = { Box(Modifier.size(16.dp).background(if (enabled) accent else track, CircleShape)) },
                    track = { state -> Canvas(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                        fun fraction(v: Float) = (v - range.start) / (range.endInclusive - range.start)
                        drawLine(track, Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
                        drawLine(if (enabled) accent else track, Offset(size.width * fraction(0f), center.y),
                            Offset(size.width * fraction(state.value), center.y), 3.dp.toPx(), StrokeCap.Round)
                    } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label; stateDescription = readout })
                Box(Modifier.matchParentSize().pointerInput(enabled, rtl, vm) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(); down.consume(); vm.beginWheelEdit()
                        var completed = false
                        fun update(x: Float) {
                            val inset = 8.dp.toPx()
                            val fraction = ((x - inset) / (size.width - 2f * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            change(range.start + (if (rtl) 1f - fraction else fraction) * (range.endInclusive - range.start))
                        }
                        try {
                            update(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.size != 1 || pointer.isConsumed) break
                                update(pointer.position.x); pointer.consume()
                                if (!pointer.pressed) { completed = true; vm.finishWheelEdit(); break }
                            } while (true)
                        } finally { if (!completed) vm.finishWheelEdit(cancel = true) }
                    }
                })
            }
            IconButton(onClick = { vm.beginWheelEdit(); onChange(0f); vm.finishWheelEdit() }, enabled = enabled && value != 0f,
                modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.RestartAlt, resetLabel, Modifier.size(18.dp)) }
        }
    }
}
