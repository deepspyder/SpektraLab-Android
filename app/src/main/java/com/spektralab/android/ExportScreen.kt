package com.spektralab.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Export settings, progress and pinned action share the original ViewModel export paths. */
@Composable
internal fun ExportScreen(vm: EditorViewModel, modifier: Modifier = Modifier, onSaveRecipe: () -> Unit) {
    var batch by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val processing = vm.busy || vm.isExporting || vm.isBatchExporting
    val exporting = vm.isExporting || vm.isBatchExporting
    val singleLabel = "单张导出"
    val batchLabel = "批量导出 · ${vm.photos.size}"
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ExportPhotoIdentity(vm)
            DarkroomChoiceStrip(listOf(singleLabel, batchLabel), if (batch) batchLabel else singleLabel, !processing) {
                batch = it == batchLabel
            }
            Spacer(Modifier.height(12.dp))
            Text("文件格式", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            DarkroomChoiceStrip(listOf("JPEG", "PNG", "TIFF"), vm.exportFormat, !processing) { vm.exportFormat = it }
            ExportSettingRow("分辨率",
                if (vm.exportRes == 0) "原始尺寸" else "长边 ${vm.exportRes} px",
                listOf("2048", "2560", "3840", "原始"), !processing,
                optionLabel = { if (it == "原始") "原始尺寸" else "长边 $it px" }) {
                vm.exportRes = it.toIntOrNull() ?: 0
            }
            ExportSettingRow("色彩空间",
                if (vm.exportFormat == "TIFF") vm.exportColorSpace else "sRGB IEC61966-2.1",
                if (vm.exportFormat == "TIFF") listOf("sRGB", "Display P3") else emptyList(), !processing) {
                vm.exportColorSpace = it
            }
            if (vm.exportFormat == "JPEG") {
                SliderRow("JPEG 质量", vm.jpegQuality.toFloat(), 50f..100f, "%.0f%%", compact = true,
                    enabled = !processing) { vm.jpegQuality = it.roundToInt() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = !processing, role = Role.Button, onClick = { advanced = !advanced })
                .semantics { stateDescription = if (advanced) "已展开" else "已收起" },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("高级设置", fontSize = 12.sp)
                    Text("配方 · 输出信息 · 同步设置", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp))
            }
            if (advanced) {
                val currentRecipe = vm.recipes.firstOrNull { it.format == vm.exportFormat && it.res == vm.exportRes && it.colorSpace == vm.exportColorSpace && it.jpegQuality == vm.jpegQuality }
                ExportSettingRow("配方", currentRecipe?.name ?: "自定义", vm.recipes.map { it.name }, !processing) { name ->
                    vm.recipes.firstOrNull { it.name == name }?.let { vm.applyRecipe(it) }
                }
                ActionButton("保存当前为配方", enabled = !processing, onClick = onSaveRecipe)
                ActionButton("同步当前设置到其他照片", enabled = vm.photos.size > 1 && vm.currentUri != null && !processing) {
                    vm.syncSettingsToOtherPhotos()
                }
                Text(if (vm.exportFormat == "TIFF") "TIFF 保留 16 位输出。" else "JPEG / PNG 使用 sRGB 输出。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportQuickAction("DI TIFF", "16 位中间文件", Icons.Outlined.Description,
                    "导出 DI (16-bit TIFF)", vm.hasPhoto && !processing, Modifier.weight(1f)) { vm.exportDi() }
                ExportQuickAction("软打样", "按导出设置预览", Icons.Outlined.Visibility,
                    "软打样 (预览导出)", vm.hasPhoto && !processing, Modifier.weight(1f)) { vm.softProof() }
                ExportQuickAction("高清预览", "3400 px 检查", Icons.Outlined.ZoomIn,
                    "高清预览 (3400px)", vm.hasPhoto && !processing, Modifier.weight(1f)) { vm.previewTier() }
            }
            if (batch && vm.photos.size <= 1) Text("添加两张以上照片后可批量导出", fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (exporting) ExportInlineProgress(vm)
            Button(onClick = { if (batch) vm.batchExport() else vm.export() },
                enabled = vm.hasPhoto && !processing && (!batch || vm.photos.size > 1),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (batch) "导出本次会话 (${vm.photos.size} 张)" else "导出当前照片", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("导出到相册 · 会话不是永久图库", Modifier.fillMaxWidth(), fontSize = 10.sp, lineHeight = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExportPhotoIdentity(vm: EditorViewModel) {
    val fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: vm.currentUri?.lastPathSegment ?: "当前照片"
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        (vm.rendered ?: vm.sourceBitmap)?.let {
            Image(it.asImageBitmap(), "导出照片预览", Modifier.size(56.dp, 64.dp).clip(RoundedCornerShape(2.dp)), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(fileName.substringBeforeLast('.'), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$fileName · ${if (vm.exportRes == 0) "原始尺寸" else "${vm.exportRes} px"}",
                fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("当前会话 · 已选 ${vm.photos.size.coerceAtLeast(1)} 张", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ExportSettingRow(label: String, value: String, options: List<String>, enabled: Boolean,
    optionLabel: (String) -> String = { it }, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled && options.isNotEmpty(), role = Role.Button, onClick = { expanded = true })
            .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = value },
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(end = 16.dp))
            Text(value, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
            if (options.isNotEmpty()) Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.padding(start = 6.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
}

@Composable
private fun ExportQuickAction(title: String, subtitle: String, icon: ImageVector, description: String,
    enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Box(Modifier.size(46.dp, 30.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center) {
                Icon(icon, description, modifier = Modifier.size(18.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(subtitle, fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ExportInlineProgress(vm: EditorViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("导出中", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.renderStage.ifBlank { "正在保存照片" }, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = vm::cancelRender,
                    enabled = vm.renderStage != "正在取消…" && !(vm.isExporting && (vm.renderProgress ?: 0f) >= 96f)) {
                    Text("取消导出", fontSize = 11.sp)
                }
            }
            val progress = vm.renderProgress
            if (progress != null) ExportProgressBar(progress / 100f, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outlineVariant)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            Row {
                Text("保持应用开启", Modifier.weight(1f), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) Text("${progress.roundToInt()}%", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
