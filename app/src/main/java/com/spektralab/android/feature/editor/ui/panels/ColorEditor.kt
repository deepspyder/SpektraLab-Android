package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.color.ColorAdjustments
import com.spektralab.android.core.color.HslBand
import com.spektralab.android.core.color.HslColor
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.roundToInt

private val swatches = listOf(
    Color(0xFFED8881), Color(0xFFEEAC6E), Color(0xFFE4CE73), Color(0xFF90C99B),
    Color(0xFF85C9C9), Color(0xFF8EAEED), Color(0xFFB49BE4), Color(0xFFE494C4),
)

@Composable
internal fun ColorEditor(vm: EditorViewModel) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val color = HslColor.entries[index]
    val colors = vm.params.colorAdjustments
    val band = colors[color]
    val enabled = vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val labels = listOf(R.string.color_red, R.string.color_orange, R.string.color_yellow,
        R.string.color_green, R.string.color_aqua, R.string.color_blue, R.string.color_purple, R.string.color_magenta)
        .map { stringResource(it) }
    DisposableEffect(vm.currentUri, color) { onDispose { vm.finishColorEdit(cancel = true) } }

    fun apply(value: ColorAdjustments) {
        vm.beginColorEdit()
        vm.changeColors(value)
        vm.finishColorEdit()
    }

    EditorSection(stringResource(R.string.color_title), stringResource(R.string.color_summary)) {
        if (vm.params.unreadableColorAdjustments != null) {
            Text(stringResource(R.string.color_unsupported), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { apply(ColorAdjustments.NEUTRAL) }, enabled = enabled) {
                Text(stringResource(R.string.color_reset_all))
            }
        } else {
            ColorValueSlider(stringResource(R.string.color_vibrance), colors.vibrance, enabled, vm) {
                vm.changeColors(vm.params.colorAdjustments.withVibrance(it))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.color_vibrance_hint), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { apply(colors.withVibrance(0f)) }, enabled = enabled && colors.vibrance != 0f) {
                    Icon(Icons.Rounded.RestartAlt, stringResource(R.string.color_reset_vibrance), Modifier.size(20.dp))
                }
            }
            Text(stringResource(R.string.color_hsl), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 440.dp) 8 else 4
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HslColor.entries.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { option ->
                                val chosen = index == option.ordinal
                                TextButton(
                                    onClick = { vm.finishColorEdit(); index = option.ordinal },
                                    enabled = enabled,
                                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).semantics { selected = chosen },
                                    shape = TheiaShapes.control,
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        containerColor = if (chosen) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                        contentColor = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Box(Modifier.size(18.dp).background(swatches[option.ordinal], TheiaShapes.badge))
                                        Text(labels[option.ordinal], style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val title = labels[index]
            ColorValueSlider(stringResource(R.string.color_hue, title), band.hue, enabled, vm) {
                vm.changeColors(vm.params.colorAdjustments.with(color, vm.params.colorAdjustments[color].copy(hue = it)))
            }
            ColorValueSlider(stringResource(R.string.color_saturation, title), band.saturation, enabled, vm) {
                vm.changeColors(vm.params.colorAdjustments.with(color, vm.params.colorAdjustments[color].copy(saturation = it)))
            }
            ColorValueSlider(stringResource(R.string.color_luminance, title), band.luminance, enabled, vm) {
                vm.changeColors(vm.params.colorAdjustments.with(color, vm.params.colorAdjustments[color].copy(luminance = it)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { apply(colors.with(color, HslBand())) }, enabled = enabled && !band.isNeutral) {
                    Text(stringResource(R.string.color_reset_band))
                }
                TextButton(onClick = { apply(ColorAdjustments.NEUTRAL) }, enabled = enabled && !colors.isNeutral) {
                    Text(stringResource(R.string.color_reset_all))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ColorValueSlider(label: String, value: Float, enabled: Boolean, vm: EditorViewModel, onChange: (Float) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is DragInteraction.Cancel) vm.finishColorEdit(cancel = true) }
    }
    DisposableEffect(Unit) { onDispose { vm.finishColorEdit(cancel = true) } }
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("%+d".format(value.roundToInt()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
        }
        val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        val trackColor = MaterialTheme.colorScheme.outlineVariant
        Slider(value = value, valueRange = -100f..100f, enabled = enabled,
            onValueChange = { vm.beginColorEdit(); onChange(it) },
            onValueChangeFinished = { vm.finishColorEdit() }, interactionSource = interaction,
            thumb = { Box(Modifier.size(16.dp).background(accent, CircleShape)) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                    val fraction = ((state.value + 100f) / 200f).coerceIn(0f, 1f)
                    drawLine(trackColor, Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(accent, Offset(0f, center.y), Offset(size.width * fraction, center.y), 3.dp.toPx(), StrokeCap.Round)
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = "%+d".format(value.roundToInt())
            })
    }
}
