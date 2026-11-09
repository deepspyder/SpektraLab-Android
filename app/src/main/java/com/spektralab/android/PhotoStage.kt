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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Always-visible edit queue. Management stays separate from one-tap switching. */
@Composable
internal fun EditorPhotoStrip(
    vm: EditorViewModel,
    onAddPhotos: () -> Unit,
    onManagePhotos: () -> Unit,
    onSmartDevelop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val strip = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val shortWindow = LocalConfiguration.current.screenHeightDp < 500
    LaunchedEffect(vm.currentIndex, vm.photos.size) {
        if (vm.currentIndex in vm.photos.indices) {
            val visible = strip.layoutInfo.visibleItemsInfo.firstOrNull { it.index == vm.currentIndex }
            if (visible == null || visible.offset < strip.layoutInfo.viewportStartOffset ||
                visible.offset + visible.size > strip.layoutInfo.viewportEndOffset) {
                strip.animateScrollToItem(vm.currentIndex)
            }
        }
    }
    Row(modifier.fillMaxWidth().background(colors.surfaceContainerLow)
        .padding(horizontal = 8.dp, vertical = if (shortWindow) 4.dp else 6.dp)
        .semantics { contentDescription = "顶部照片工作台" }, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onManagePhotos, modifier = Modifier.width(56.dp).heightIn(min = 48.dp)
            .semantics { contentDescription = "管理照片工作台" }, contentPadding = PaddingValues(0.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("照片", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text("${vm.currentIndex + 1}/${vm.photos.size}", style = MaterialTheme.typography.labelSmall,
                    color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyRow(state = strip, modifier = Modifier.weight(1f).selectableGroup()
            .semantics { contentDescription = "编辑照片列表" },
            contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.photos, key = { _, uri -> uri.toString() }) { index, uri ->
                val current = index == vm.currentIndex
                val thumbnail = vm.developedPreviews[uri] ?: vm.thumbnails[uri]
                LaunchedEffect(uri, thumbnail == null) { if (thumbnail == null) vm.loadThumbnail(uri) }
                Box(Modifier.size(if (shortWindow) 56.dp else 64.dp, if (shortWindow) 48.dp else 60.dp).clip(SpektraShapes.compact)
                    .background(if (current) colors.surfaceContainerHigh else colors.surface)
                    .then(if (current) Modifier.border(2.dp, colors.primary, SpektraShapes.compact) else Modifier)
                    .selectable(selected = current, enabled = enabled, role = Role.RadioButton,
                        onClick = { if (!current) vm.selectPhoto(index) })
                    .semantics(mergeDescendants = true) {
                        contentDescription = "编辑照片 ${index + 1}"
                        selected = current
                        stateDescription = if (current) "正在编辑" else "点击切换编辑"
                    }, contentAlignment = Alignment.Center) {
                    if (thumbnail != null) Image(thumbnail.asImageBitmap(), null,
                        Modifier.fillMaxSize().padding(if (current) 3.dp else 1.dp), contentScale = ContentScale.Fit)
                    else Icon(Icons.Rounded.Image, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Text("${index + 1}", Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .background(colors.surface.copy(alpha = .9f), SpektraShapes.badge).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (current) colors.primary else colors.onSurface)
                }
            }
        }
        IconButton(onClick=onSmartDevelop,modifier=Modifier.size(48.dp)) {
            Icon(Icons.Rounded.AutoAwesome,"智能显影",Modifier.size(20.dp),
                tint=if(vm.smartAutoEnabled)colors.primary else colors.onSurfaceVariant)
        }
        IconButton(onClick = onAddPhotos, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Add, "添加编辑照片", tint = if (enabled) colors.primary else colors.onSurfaceVariant)
        }
    }
}

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
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加照片", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, "关闭展示台", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
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
                            Modifier.fillMaxWidth().height(104.dp).clip(SpektraShapes.thumbnail)
                                .background(colors.surfaceContainerHigh)
                                .then(if (selected) Modifier.border(2.dp, colors.primary, SpektraShapes.thumbnail) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) Image(thumb.asImageBitmap(), null,
                                Modifier.fillMaxSize().padding(if (selected) 3.dp else 0.dp).clip(SpektraShapes.thumbnailInset),
                                contentScale = ContentScale.Crop)
                            else Icon(Icons.Rounded.Image, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                            if (selected) Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                                    .background(colors.primary, SpektraShapes.badge), contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = colors.onPrimary)
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
                    Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一张", Modifier.size(22.dp))
                }
                IconButton(onClick = vm::nextPhoto, enabled = enabled && vm.currentIndex < vm.photos.lastIndex) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一张", Modifier.size(22.dp))
                }
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = enabled) {
                        Icon(Icons.Rounded.MoreHoriz, "照片操作", Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("照片信息") },
                            leadingIcon = { Icon(Icons.Rounded.Info, null, Modifier.size(20.dp)) },
                            onClick = { showMenu = false; showInfo = true })
                        DropdownMenuItem(text = { Text("从展示台移除") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(20.dp)) },
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
