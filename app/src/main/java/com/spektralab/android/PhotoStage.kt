package com.spektralab.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A filmstrip for the existing edit queue. Browsing reuses cached previews. */
@Composable
fun PhotoStage(
    vm: EditorViewModel,
    onAddPhotos: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val stripState = rememberLazyListState()
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "当前照片"

    LaunchedEffect(vm.currentIndex, vm.photos.size) {
        if (vm.currentIndex in vm.photos.indices) {
            val selected = stripState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == vm.currentIndex }
            if (selected == null || selected.offset < stripState.layoutInfo.viewportStartOffset ||
                selected.offset + selected.size > stripState.layoutInfo.viewportEndOffset) {
                stripState.animateScrollToItem(vm.currentIndex)
            }
        }
    }

    Column(modifier.fillMaxWidth().background(colors.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("展示台", style = MaterialTheme.typography.titleMedium)
            Text("${vm.photos.size} 张", Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            TextButton(onClick = onAddPhotos, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加照片", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, "关闭展示台", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }

        if (vm.photos.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("把照片放上展示台", style = MaterialTheme.typography.titleSmall)
                Text("添加照片后，横向浏览并选择要编辑的一张。", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
        } else {
            LazyRow(
                state = stripState,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "展示台照片列表" },
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(vm.photos, key = { _, uri -> uri.toString() }) { index, uri ->
                    val selected = index == vm.currentIndex
                    val thumb = vm.developedPreviews[uri] ?: vm.thumbnails[uri]
                    if (thumb == null) LaunchedEffect(uri) { vm.loadThumbnail(uri) }
                    Column(
                        Modifier.width(88.dp)
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                                onClick = { if (!selected) vm.selectPhoto(index) })
                            .semantics(mergeDescendants = true) {
                                contentDescription = "展示台照片 ${index + 1}"
                                stateDescription = if (selected) "当前照片" else "未选中"
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceContainerHigh)
                                .then(if (selected) Modifier.border(2.dp, colors.primary, RoundedCornerShape(6.dp)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) Image(thumb.asImageBitmap(), null,
                                Modifier.fillMaxSize().padding(if (selected) 3.dp else 0.dp).clip(RoundedCornerShape(3.dp)),
                                contentScale = ContentScale.Crop)
                            else Icon(Icons.Outlined.Image, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                            if (selected) Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                                    .background(colors.primary, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = colors.onPrimary)
                            }
                        }
                        Text(if (selected) "${index + 1} · 当前" else "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) colors.primary else colors.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .65f))
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${vm.currentIndex + 1} / ${vm.photos.size}",
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = vm::previousPhoto, enabled = enabled && vm.currentIndex > 0) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "上一张", Modifier.size(22.dp))
                }
                IconButton(onClick = vm::nextPhoto, enabled = enabled && vm.currentIndex < vm.photos.lastIndex) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, "下一张", Modifier.size(22.dp))
                }
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = enabled) {
                        Icon(Icons.Outlined.MoreHoriz, "照片操作", Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("照片信息") },
                            leadingIcon = { Icon(Icons.Outlined.Info, null, Modifier.size(20.dp)) },
                            onClick = { showMenu = false; showInfo = true })
                        DropdownMenuItem(text = { Text("从展示台移除") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp)) },
                            onClick = { showMenu = false; vm.removeCurrentPhoto() })
                    }
                }
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("照片信息") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (vm.photoInfo.isEmpty()) Text("没有可用的照片信息")
                    vm.photoInfo.forEach { (label, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } },
        )
    }
}
