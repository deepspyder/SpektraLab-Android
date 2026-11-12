package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.color.ChannelMixer
import com.spektralab.android.core.color.MixerChannel
import com.spektralab.android.core.color.MixerRow
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.roundToInt

@Composable
internal fun ChannelMixerEditor(vm: EditorViewModel) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val output = MixerChannel.entries[selectedIndex]
    val mixer = vm.params.channelMixer
    val enabled = vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val names = listOf(R.string.color_red, R.string.color_green, R.string.color_blue).map { stringResource(it) }
    DisposableEffect(vm.currentUri, output) { onDispose { vm.finishMixerEdit(cancel = true) } }

    fun apply(value: ChannelMixer) {
        vm.beginMixerEdit()
        vm.changeMixer(value)
        vm.finishMixerEdit()
    }

    EditorSection(stringResource(R.string.mixer_title), stringResource(R.string.mixer_summary)) {
        if (vm.params.unreadableChannelMixer != null) {
            Text(stringResource(R.string.mixer_unsupported), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { apply(ChannelMixer.IDENTITY) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mixer_reset_all)) }
        } else {
            Text(stringResource(R.string.mixer_output), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MixerChannel.entries.forEach { channel ->
                    val chosen = channel == output
                    TextButton(
                        onClick = { vm.finishMixerEdit(); selectedIndex = channel.ordinal },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = chosen },
                        shape = TheiaShapes.control,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (chosen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(names[channel.ordinal], style = MaterialTheme.typography.labelLarge) }
                }
            }
            Text(stringResource(R.string.mixer_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            MixerChannel.entries.forEach { input ->
                val offset = mixer[output][input]
                val identity = if (input == output) 100 else 0
                val label = stringResource(R.string.mixer_input, names[input.ordinal])
                val resetLabel = stringResource(R.string.mixer_reset_input, names[input.ordinal])
                MixerSlider(label, resetLabel, offset, identity, enabled, vm,
                    onReset = { apply(vm.params.channelMixer.with(output, vm.params.channelMixer[output].with(input, 0f))) },
                    onChange = { vm.changeMixer(vm.params.channelMixer.with(output, vm.params.channelMixer[output].with(input, it))) })
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { apply(mixer.with(output, MixerRow())) }, enabled = enabled && !mixer[output].isNeutral,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.mixer_reset_output))
                }
                TextButton(onClick = { apply(ChannelMixer.IDENTITY) }, enabled = enabled && !mixer.isNeutral,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.mixer_reset_all))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MixerSlider(
    label: String,
    resetLabel: String,
    value: Float,
    identity: Int,
    enabled: Boolean,
    vm: EditorViewModel,
    onReset: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val change by rememberUpdatedState(onChange)
    DisposableEffect(Unit) { onDispose { vm.finishMixerEdit(cancel = true) } }
    val readout = stringResource(R.string.mixer_value, identity + value.roundToInt())
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(readout, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp))
        }
        val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        val trackColor = MaterialTheme.colorScheme.outlineVariant
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Slider(value = value, valueRange = -100f..100f, enabled = enabled,
                    onValueChange = { vm.beginMixerEdit(); onChange(it) },
                    onValueChangeFinished = { vm.finishMixerEdit() }, interactionSource = interaction,
                    thumb = { Box(Modifier.size(16.dp).background(accent, CircleShape)) },
                    track = { state ->
                        Canvas(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                            val end = size.width * ((state.value + 100f) / 200f).coerceIn(0f, 1f)
                            drawLine(trackColor, Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
                            drawLine(accent, Offset(size.width / 2, center.y), Offset(end, center.y), 3.dp.toPx(), StrokeCap.Round)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = label
                        stateDescription = readout
                    })
                // A stable gesture layer keeps track taps, drags and cancellation
                // in one transaction. Material Slider still owns range semantics,
                // keyboard input and accessibility; touch cannot also reach it.
                Box(Modifier.matchParentSize().pointerInput(enabled, rtl, vm) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        vm.beginMixerEdit()
                        var completed = false
                        fun update(x: Float) {
                            val inset = 8.dp.toPx()
                            val fraction = ((x - inset) / (size.width - 2f * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            change((if (rtl) 1f - fraction else fraction) * 200f - 100f)
                        }
                        try {
                            update(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.size != 1 || pointer.isConsumed) break
                                update(pointer.position.x)
                                pointer.consume()
                                if (!pointer.pressed) {
                                    completed = true
                                    vm.finishMixerEdit()
                                    break
                                }
                            } while (true)
                        } finally {
                            if (!completed) vm.finishMixerEdit(cancel = true)
                        }
                    }
                })
            }
            IconButton(onClick = onReset, enabled = enabled && value != 0f, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.RestartAlt, resetLabel, Modifier.size(18.dp))
            }
        }
    }
}
