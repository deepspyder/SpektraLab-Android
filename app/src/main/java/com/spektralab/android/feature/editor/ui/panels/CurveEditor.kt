package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.color.CurveChannel
import com.spektralab.android.core.color.ToneCurve
import com.spektralab.android.core.color.ToneCurves
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.sqrt

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun CurveEditor(vm: EditorViewModel) {
    val titles =
        listOf(
            stringResource(R.string.curve_rgb),
            stringResource(R.string.curve_luma),
            stringResource(R.string.curve_red),
            stringResource(R.string.curve_green),
            stringResource(R.string.curve_blue),
        )
    var channelIndex by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable(vm.currentUri, channelIndex) { mutableIntStateOf(0) }
    var precision by rememberSaveable { mutableStateOf(false) }
    var plotSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging: ToneCurve? by remember { mutableStateOf(null) }
    var dragIndex by remember { mutableIntStateOf(0) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    val dragOwnership = remember { RequestDisallowInterceptTouchEvent() }
    val channel = CurveChannel.entries[channelIndex]
    val curves = vm.params.toneCurves
    val curve = curves[channel]
    val enabled = vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val pointIndex = selected.coerceIn(curve.points.indices)
    val point = curve.points[pointIndex]
    val title = stringResource(R.string.curve_title)
    val plotLabel = stringResource(R.string.curve_plot, titles[channelIndex])
    if (vm.params.unreadableToneCurves != null) {
        EditorSection(title, stringResource(R.string.curve_unsupported), initiallyExpanded = true) {
            Text(
                stringResource(R.string.curve_unsupported_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    vm.beginCurveEdit()
                    vm.changeCurves(ToneCurves.IDENTITY)
                    vm.finishCurveEdit()
                },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.curve_reset_all))
            }
        }
        return
    }
    val addLabel = stringResource(R.string.curve_add_midpoint)
    val previousLabel = stringResource(R.string.curve_previous_point)
    val nextLabel = stringResource(R.string.curve_next_point)
    DisposableEffect(vm.currentUri, channel) { onDispose { vm.finishCurveEdit(cancel = true) } }
    val accent =
        when (channel) {
            CurveChannel.RED -> Color(0xFFED9691)
            CurveChannel.GREEN -> Color(0xFF9DCB9E)
            CurveChannel.BLUE -> Color(0xFF98B6ED)
            else -> MaterialTheme.colorScheme.primary
        }
    val line = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val currentCurve by rememberUpdatedState(curve)
    val currentCurves by rememberUpdatedState(curves)
    EditorSection(title, stringResource(R.string.curve_summary), initiallyExpanded = false) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            titles.forEachIndexed { index, label ->
                FilterChip(
                    selected = channelIndex == index,
                    enabled = enabled,
                    onClick = {
                        vm.finishCurveEdit()
                        channelIndex = index
                    },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = TheiaShapes.control,
                )
            }
        }
        Canvas(
            Modifier.fillMaxWidth()
                .height(180.dp)
                .clip(TheiaShapes.panel)
                .background(surface)
                .semantics {
                    contentDescription = plotLabel
                    stateDescription = "${curve.points.size}"
                    if (enabled)
                        customActions =
                            listOf(
                                CustomAccessibilityAction(addLabel) {
                                    vm.beginCurveEdit()
                                    val gap =
                                        curve.points.zipWithNext().maxBy { (a, b) -> b.x - a.x }
                                    val x = (gap.first.x + gap.second.x) / 2f
                                    val next = curve.add(x, curve.evaluate(x))
                                    vm.changeCurves(curves.with(channel, next))
                                    selected =
                                        next.points.indexOfFirst { it.x == x }.coerceAtLeast(0)
                                    vm.finishCurveEdit()
                                    true
                                },
                                CustomAccessibilityAction(previousLabel) {
                                    selected = (pointIndex - 1).coerceAtLeast(0)
                                    true
                                },
                                CustomAccessibilityAction(nextLabel) {
                                    selected = (pointIndex + 1).coerceAtMost(curve.points.lastIndex)
                                    true
                                },
                            )
                }
                .onSizeChanged { plotSize = it }
                .pointerInteropFilter(requestDisallowInterceptTouchEvent = dragOwnership) { event ->
                    if (!enabled) return@pointerInteropFilter false
                    val inset = 18f * density
                    val w = (plotSize.width - 2 * inset).coerceAtLeast(1f)
                    val h = (plotSize.height - 2 * inset).coerceAtLeast(1f)
                    val point =
                        Offset(
                            ((event.x - inset) / w).coerceIn(0f, 1f),
                            (1f - (event.y - inset) / h).coerceIn(0f, 1f),
                        )
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            dragOwnership(true)
                            val base = currentCurve
                            val nearest =
                                base.points.indices.minBy { i ->
                                    val p = base.points[i]
                                    val dx = inset + p.x * w - event.x
                                    val dy = inset + (1 - p.y) * h - event.y
                                    dx * dx + dy * dy
                                }
                            val hit =
                                base.points[nearest].let { p ->
                                    val dx = inset + p.x * w - event.x
                                    val dy = inset + (1 - p.y) * h - event.y
                                    sqrt(dx * dx + dy * dy) <= 24f * density
                                }
                            val next = if (hit) base else base.add(point.x, point.y)
                            dragIndex =
                                if (hit) nearest
                                else
                                    next.points.indices.minBy {
                                        kotlin.math.abs(next.points[it].x - point.x)
                                    }
                            selected = dragIndex
                            grabOffset =
                                if (hit)
                                    Offset(
                                        next.points[dragIndex].x - point.x,
                                        next.points[dragIndex].y - point.y,
                                    )
                                else Offset.Zero
                            dragging = next
                            vm.beginCurveEdit()
                            vm.changeCurves(currentCurves.with(channel, next))
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            dragging?.let { work ->
                                val p = point + grabOffset
                                val next = work.move(dragIndex, p.x, p.y)
                                dragging = next
                                vm.changeCurves(currentCurves.with(channel, next))
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            vm.finishCurveEdit()
                            dragging = null
                            dragOwnership(false)
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL,
                        android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                            vm.finishCurveEdit(cancel = true)
                            dragging = null
                            dragOwnership(false)
                            true
                        }
                        else -> dragging != null
                    }
                }
        ) {
            val inset = 18.dp.toPx()
            val w = size.width - 2 * inset
            val h = size.height - 2 * inset
            for (i in 0..4) {
                val t = i / 4f
                drawLine(
                    line.copy(alpha = if (i == 0 || i == 4) .75f else .35f),
                    Offset(inset + w * t, inset),
                    Offset(inset + w * t, inset + h),
                    1.dp.toPx(),
                )
                drawLine(
                    line.copy(alpha = if (i == 0 || i == 4) .75f else .35f),
                    Offset(inset, inset + h * t),
                    Offset(inset + w, inset + h * t),
                    1.dp.toPx(),
                )
            }
            drawLine(
                muted.copy(alpha = .35f),
                Offset(inset, inset + h),
                Offset(inset + w, inset),
                1.dp.toPx(),
            )
            val path = Path()
            for (i in 0..256) {
                val x = i / 256f
                val y = curve.evaluate(x)
                if (i == 0) path.moveTo(inset + x * w, inset + (1 - y) * h)
                else path.lineTo(inset + x * w, inset + (1 - y) * h)
            }
            drawPath(path, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            curve.points.forEachIndexed { i, p ->
                val at = Offset(inset + p.x * w, inset + (1 - p.y) * h)
                if (i == pointIndex) drawCircle(accent.copy(alpha = .2f), 11.dp.toPx(), at)
                drawCircle(surface, 5.dp.toPx(), at)
                drawCircle(accent, 5.dp.toPx(), at, style = Stroke(2.dp.toPx()))
            }
        }
        Text(
            stringResource(R.string.curve_hint),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.curve_point, pointIndex + 1, curve.points.size),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
            IconButton(
                enabled = enabled && pointIndex in 1 until curve.points.lastIndex,
                onClick = {
                    vm.beginCurveEdit()
                    vm.changeCurves(curves.with(channel, curve.remove(pointIndex)))
                    selected = (pointIndex - 1).coerceAtLeast(0)
                    vm.finishCurveEdit()
                },
            ) {
                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.curve_delete))
            }
            TextButton(
                enabled = enabled && curve != ToneCurve.IDENTITY,
                onClick = {
                    vm.beginCurveEdit()
                    vm.changeCurves(curves.with(channel, ToneCurve.IDENTITY))
                    selected = 0
                    vm.finishCurveEdit()
                },
            ) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.curve_reset_channel))
            }
        }
        TextButton(onClick = { precision = !precision }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (precision) R.string.curve_hide_precision else R.string.curve_show_precision
                )
            )
        }
        if (precision) {
            CurveValueSlider(
                stringResource(R.string.curve_input),
                point.x,
                enabled && pointIndex in 1 until curve.points.lastIndex,
                onStart = vm::beginCurveEdit,
                onFinish = { vm.finishCurveEdit() },
                onCancel = { vm.finishCurveEdit(cancel = true) },
            ) {
                vm.changeCurves(
                    vm.params.toneCurves.with(
                        channel,
                        vm.params.toneCurves[channel].move(pointIndex, it, point.y),
                    )
                )
            }
            CurveValueSlider(
                stringResource(R.string.curve_output),
                point.y,
                enabled,
                onStart = vm::beginCurveEdit,
                onFinish = { vm.finishCurveEdit() },
                onCancel = { vm.finishCurveEdit(cancel = true) },
            ) {
                vm.changeCurves(
                    vm.params.toneCurves.with(
                        channel,
                        vm.params.toneCurves[channel].move(pointIndex, point.x, it),
                    )
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                enabled = enabled && !curves.isDefault,
                onClick = {
                    vm.beginCurveEdit()
                    vm.changeCurves(ToneCurves.IDENTITY)
                    selected = 0
                    vm.finishCurveEdit()
                },
            ) {
                Text(stringResource(R.string.curve_reset_all))
            }
        }
    }
}

@Composable
private fun CurveValueSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val cancel by rememberUpdatedState(onCancel)
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is DragInteraction.Cancel) cancel() }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                "${(value*255+.5f).toInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = {
                onStart()
                onChange(it)
            },
            onValueChangeFinished = onFinish,
            interactionSource = interaction,
            valueRange = 0f..1f,
            enabled = enabled,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = label
                },
        )
    }
}
