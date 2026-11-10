package com.spektralab.android.feature.export

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.DarkroomChoiceStrip
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.roundToInt

/** Export settings, progress and pinned action share the original ViewModel export paths. */
@Composable
internal fun ExportScreen(vm: EditorViewModel, modifier: Modifier = Modifier, onSaveRecipe: () -> Unit) {
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.exportKeepLocation = granted
    }
    var batch by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var activeQuickAction by rememberSaveable { mutableStateOf<String?>(null) }
    val processing = vm.busy || vm.isExporting || vm.isBatchExporting
    val exporting = vm.isExporting || vm.isBatchExporting
    val selectedCount = vm.selectedBatchExportPhotos.size
    LaunchedEffect(processing, activeQuickAction) {
        if (!processing && activeQuickAction != null) activeQuickAction = null
    }
    val singleLabel = "单张导出"
    val batchLabel = "批量导出 · ${vm.photos.size}"
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ExportPhotoIdentity(vm)
            DarkroomChoiceStrip(listOf(singleLabel, batchLabel), if (batch) batchLabel else singleLabel, !processing) {
                batch = it == batchLabel
            }
            if (batch) BatchExportSelection(vm, enabled = !processing)
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
            ExportMetadataOption("保留拍摄信息", "相机、镜头、拍摄时间及曝光参数", vm.exportKeepCapture, !processing) { vm.exportKeepCapture = it }
            ExportMetadataOption("保留位置信息", "仅复制原片已有、可读取的 GPS 信息", vm.exportKeepLocation, !processing) { keep ->
                if (keep && Build.VERSION.SDK_INT >= 29 && vm.currentUri?.authority == "media" &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                } else vm.exportKeepLocation = keep
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clip(TheiaShapes.control)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(enabled = !processing, role = Role.Button, onClick = { advanced = !advanced })
                .semantics { contentDescription = "高级设置"; stateDescription = if (advanced) "已展开" else "已收起" }
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("高级设置", fontSize = 12.sp)
                    Text("位深 · 元数据 · 文件命名 · 保存位置", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
            }
            if (advanced) {
                val extension = when (vm.exportFormat) {
                    "PNG" -> "png"
                    "TIFF" -> "tiff"
                    else -> "jpg"
                }
                ExportInfoRow("位深", if (vm.exportFormat == "TIFF") "RGB · 16 bit" else "RGB · 8 bit")
                ExportInfoRow("文件命名", "Theia_时间戳.$extension")
                ExportInfoRow("保存位置", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "相册 / Pictures/Theia"
                } else {
                    "应用专属图片目录"
                })

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
                Text("导出配方", Modifier.padding(top = 10.dp, bottom = 2.dp), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary)
                val currentRecipe = vm.recipes.firstOrNull { it.format == vm.exportFormat && it.res == vm.exportRes && it.colorSpace == vm.exportColorSpace && it.jpegQuality == vm.jpegQuality && it.keepCapture == vm.exportKeepCapture && it.keepLocation == vm.exportKeepLocation }
                ExportSettingRow("当前配方", currentRecipe?.name ?: "自定义", vm.recipes.map { it.name }, !processing) { name ->
                    vm.recipes.firstOrNull { it.name == name }?.let { vm.applyRecipe(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onSaveRecipe,
                        enabled = !processing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = TheiaShapes.control,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.SaveAlt, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存配方", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(
                        onClick = vm::syncSettingsToOtherPhotos,
                        enabled = vm.photos.size > 1 && vm.currentUri != null && !processing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = TheiaShapes.control,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("同步其他照片", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("同步胶片与调色参数；保留每张照片的独立裁剪。", fontSize = 10.sp, lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportQuickAction(
                    title = "DI TIFF", subtitle = "16 位中间文件", description = "导出 DI (16-bit TIFF)",
                    enabled = vm.hasPhoto && !processing, active = activeQuickAction == "di",
                    modifier = Modifier.weight(1f),
                ) { activeQuickAction = "di"; vm.exportDi() }
                ExportQuickAction(
                    title = "软打样", subtitle = "按导出设置预览", description = "软打样 (预览导出)",
                    enabled = vm.hasPhoto && !processing, active = activeQuickAction == "proof",
                    modifier = Modifier.weight(1f),
                ) { activeQuickAction = "proof"; vm.softProof() }
                ExportQuickAction(
                    title = "高清预览", subtitle = "3400 px 检查", description = "高清预览 (3400px)",
                    enabled = vm.hasPhoto && !processing, active = activeQuickAction == "preview",
                    modifier = Modifier.weight(1f),
                ) { activeQuickAction = "preview"; vm.previewTier() }
            }
            if (batch && selectedCount == 0) Text("请选择至少一张照片", fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (exporting) ExportInlineProgress(vm)
            Button(onClick = { if (batch) vm.batchExport() else vm.export() },
                enabled = !processing && (if (batch) selectedCount > 0 else vm.hasPhoto),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = TheiaShapes.control) {
                Icon(Icons.Rounded.SaveAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (batch) "导出所选照片 ($selectedCount 张)" else "导出当前照片", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("导出到相册 · 会话不是永久图库", Modifier.fillMaxWidth(), fontSize = 10.sp, lineHeight = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExportInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.widthIn(min = 88.dp), fontSize = 12.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), thickness = .5.dp)
}

@Composable
private fun ExportPhotoIdentity(vm: EditorViewModel) {
    val fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: vm.currentUri?.lastPathSegment ?: "当前照片"
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        (vm.rendered ?: vm.sourceBitmap)?.let {
            Image(it.asImageBitmap(), "导出照片预览", Modifier.size(56.dp, 64.dp).clip(TheiaShapes.thumbnail), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(fileName.substringBeforeLast('.'), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$fileName · ${if (vm.exportRes == 0) "原始尺寸" else "${vm.exportRes} px"}",
                fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("当前会话 · 共 ${vm.photos.size.coerceAtLeast(1)} 张", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary)
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
            if (options.isNotEmpty()) Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.padding(start = 6.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
}

@Composable
private fun ExportQuickAction(
    title: String,
    subtitle: String,
    description: String,
    enabled: Boolean,
    active: Boolean,
    modifier: Modifier,
    action: () -> Unit,
) {
    Column(
        modifier.heightIn(min = 80.dp)
            .clip(TheiaShapes.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .semantics(mergeDescendants = true) {
                contentDescription = "$description · $subtitle"
                stateDescription = if (active) "正在处理" else if (enabled) "点击执行" else "暂不可用"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.padding(vertical = 2.dp)
                .size(width = 46.dp, height = 26.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    CircleShape,
                )
                .padding(horizontal = 3.dp),
            contentAlignment = if (active) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.size(20.dp).background(
                    if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .48f),
                    CircleShape,
                )
            )
        }
        Text(subtitle, fontSize = 9.sp, lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ExportInlineProgress(vm: EditorViewModel) {
    val taskLabel = when {
        vm.isBatchExporting -> "批量导出中"
        vm.renderStage.contains("DI TIFF") -> "DI TIFF 导出中"
        vm.renderStage.contains("软打样") -> "软打样中"
        vm.renderStage.contains("高清预览") -> "高清预览中"
        else -> "导出中"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = TheiaShapes.control) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(taskLabel, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.renderStage.ifBlank { "正在保存照片" }, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = vm::cancelRender,
                    enabled = vm.renderStage != "正在取消…" && (vm.renderProgress ?: 0f) < 100f &&
                        !vm.isExportCommitCompleted) {
                    Text(if (vm.isExporting && !vm.isBatchExporting) "取消处理" else "取消导出", fontSize = 11.sp)
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

@Composable
private fun ExportMetadataOption(label: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
