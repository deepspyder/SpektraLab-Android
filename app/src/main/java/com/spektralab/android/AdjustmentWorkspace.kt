package com.spektralab.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Resizing and full-screen viewing only change layout; both reuse the current image. */
@Composable
internal fun AdjustmentWorkspace(
    mode: String,
    fileName: String,
    comparing: Boolean,
    canCompare: Boolean,
    onCompare: () -> Unit,
    onFullScreen: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
) {
    var controlsCollapsed by rememberSaveable { mutableStateOf(false) }
    var panelFraction by rememberSaveable { mutableFloatStateOf(.5f) }
    var lastMode by rememberSaveable { mutableStateOf(mode) }
    val scrollStates = rememberSaveableStateHolder()
    LaunchedEffect(mode) {
        if (lastMode != mode) {
            controlsCollapsed = false
            lastMode = mode
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val minimumPreview = minOf(192.dp, maxHeight * .35f)
        val minimumPanel = minOf(240.dp, maxHeight * .5f)
        val panelHeight = (maxHeight * panelFraction).coerceIn(minimumPanel, maxHeight - minimumPreview)
        val handleHeight = 56.dp
        val dragState = rememberDraggableState { delta ->
            controlsCollapsed = false
            panelFraction = (panelFraction - delta / heightPx).coerceIn(.3f, .72f)
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                preview(Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = .85f), SpektraShapes.control)) {
                    PreviewComparisonButton(comparing, canCompare, onCompare)
                    IconButton(onClick = onFullScreen, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.OpenInFull, "全屏预览", modifier = Modifier.size(20.dp))
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, shape = SpektraShapes.sheet,
                modifier = Modifier.fillMaxWidth()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), thickness = .5.dp)
                    Row(Modifier.fillMaxWidth().height(handleHeight).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { controlsCollapsed = !controlsCollapsed }) {
                            Icon(if (controlsCollapsed) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (controlsCollapsed) "展开参数" else "收起参数", fontSize = 12.sp)
                        }
                        Box(Modifier.weight(1f).height(48.dp)
                            .draggable(dragState, Orientation.Vertical)
                            .semantics {
                                contentDescription = "调整预览大小"
                                stateDescription = "上下拖动调整照片与参数的空间"
                                progressBarRangeInfo = ProgressBarRangeInfo(panelFraction, .3f.. .72f)
                                setProgress {
                                    controlsCollapsed = false
                                    panelFraction = it.coerceIn(.3f, .72f)
                                    true
                                }
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(2.dp)))
                        }
                        TextButton(onClick = onDone, modifier = Modifier.semantics { contentDescription = "返回大图预览" }) {
                            Text("完成", fontSize = 12.sp)
                        }
                    }
                    if (!controlsCollapsed) {
                      scrollStates.SaveableStateProvider(mode) {
                        Column(Modifier.fillMaxWidth().heightIn(max = (panelHeight - handleHeight).coerceAtLeast(96.dp))
                            .verticalScroll(rememberScrollState())) {
                            controls()
                        }
                      }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdjustmentFullscreen(
    mode: String,
    fileName: String,
    comparing: Boolean,
    canCompare: Boolean,
    onCompare: () -> Unit,
    onClose: () -> Unit,
    preview: @Composable (Modifier) -> Unit,
) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Close, "关闭全屏预览", modifier = Modifier.size(22.dp))
                        }
                        Text(fileName, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PreviewComparisonButton(comparing, canCompare, onCompare)
                    }
                    preview(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "全屏照片预览" })
                    Text("$mode · 原片始终保留", Modifier.align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "全屏预览说明" },
                        fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
}

@Composable
private fun PreviewComparisonButton(comparing: Boolean, enabled: Boolean, onCompare: () -> Unit) {
    IconToggleButton(checked = comparing, onCheckedChange = { onCompare() }, enabled = enabled,
        modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_darkroom_columns_2),
            if (comparing) "关闭前后对比" else "前后对比", modifier = Modifier.size(20.dp),
            tint = if (comparing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
