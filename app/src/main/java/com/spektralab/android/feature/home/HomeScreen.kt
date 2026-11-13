package com.spektralab.android.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.smartdevelop.SmartDevelopEntry
import com.spektralab.android.feature.smartdevelop.SmartDevelopSheet
import com.spektralab.android.ui.components.captureClock
import com.spektralab.android.ui.components.captureReadouts
import com.spektralab.android.ui.components.rememberSourcePhotoInfo
import com.spektralab.android.ui.theme.TheiaShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    vm: EditorViewModel,
    onImportPhotos: () -> Unit,
    onAddPhotos: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenViewingStand: () -> Unit,
    onOpenTestImage: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSavedPhotos: () -> Unit = {},
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val preview = vm.rendered ?: vm.sourceBitmap

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(22.dp).background(MaterialTheme.colorScheme.primary))
                    Text("Theia", Modifier.weight(1f).padding(start = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onOpenSavedPhotos, enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.photos_title), style = MaterialTheme.typography.labelLarge)
                    }
                    Box {
                        IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Tune, "更多操作", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("测试图") },
                                onClick = { moreExpanded = false; onOpenTestImage() },
                            )
                            DropdownMenuItem(
                                text = { Text("关于") },
                                onClick = { moreExpanded = false; onOpenAbout() },
                            )
                        }
                    }
                }
            }
        },
    ) { insets ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(insets),
        ) {
            val expanded = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight)
            if (expanded) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    HomeLead(
                        vm = vm,
                        preview = preview,
                        onImportPhotos = onImportPhotos,
                        onOpenEditor = onOpenEditor,
                        onOpenViewingStand = onOpenViewingStand,
                        modifier = Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    )
                    Column(
                        Modifier.weight(0.85f).fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (vm.photos.isNotEmpty()) {
                            HomeSession(vm, onAddPhotos, onOpenEditor)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("当前会话", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "导入照片后，缩略图与编辑状态会保留在本次会话中。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    HomeLead(
                        vm = vm,
                        preview = preview,
                        onImportPhotos = onImportPhotos,
                        onOpenEditor = onOpenEditor,
                        onOpenViewingStand = onOpenViewingStand,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.photos.isNotEmpty()) HomeSession(vm, onAddPhotos, onOpenEditor)
                }
            }
        }
    }
}

@Composable
private fun HomeLead(
    vm: EditorViewModel,
    preview: android.graphics.Bitmap?,
    onImportPhotos: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenViewingStand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var smartVisible by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            if (vm.hasPhoto) "让光，慢慢显影。" else "从一张照片开始",
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
          )
          if (vm.hasPhoto) Text("数字暗房 / ${(vm.currentIndex + 1).toString().padStart(2, '0')}",
              style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (!vm.hasPhoto) Text(
            if (vm.hasPhoto) "当前工作台 · ${vm.photos.size.coerceAtLeast(1)} 张照片"
            else "把照片放上工作台，选择胶片与相纸，开始显影。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (vm.hasPhoto && preview != null) {
            val identity = rememberSourcePhotoInfo(vm.currentUri,
                vm.photoInfo.firstOrNull { it.first == "文件名" }?.second, vm.currentIsRaw)
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "继续显影",
                modifier = Modifier.fillMaxWidth().aspectRatio(1.4f)
                    .clip(TheiaShapes.panel).clickable(role = Role.Button, onClick = onOpenEditor),
                contentScale = ContentScale.Crop,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val film = vm.films.firstOrNull { it.id == vm.params.filmId }?.name ?: "胶片"
                val paper = vm.papers.firstOrNull { it.id == vm.params.paperId }?.name ?: "相纸"
                Text("$film / $paper", Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                captureClock(vm.photoInfo)?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
              }
              Column(Modifier.fillMaxWidth().semantics { contentDescription = "首页照片参数" },
                  verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                  Text(identity.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                  Spacer(Modifier.weight(1f))
                  val dimensions = vm.photoInfo.firstOrNull { it.first == "原始尺寸" }?.second
                      ?: "预览 ${preview.width} × ${preview.height}"
                  Text(dimensions, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (captureReadouts(vm.photoInfo).isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  captureReadouts(vm.photoInfo).forEach { value ->
                      Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
              }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 224.dp, max = 300.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = TheiaShapes.control,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("暗房已就绪", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "导入照片，开始一卷新的显影。",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SmartDevelopEntry(vm,onOpen={smartVisible=true})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onImportPhotos,
            enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
            modifier = Modifier.weight(1.15f).heightIn(min = 52.dp),
            shape = TheiaShapes.control,
        ) {
            Icon(
                Icons.Rounded.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text("导入照片", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        FilledTonalButton(
            onClick=onOpenViewingStand,
            enabled=!vm.busy && !vm.isExporting && !vm.isBatchExporting,
            modifier=Modifier.weight(1f).heightIn(min=52.dp).semantics { contentDescription="打开观景台" },
            shape=TheiaShapes.control,
            colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor=MaterialTheme.colorScheme.onSurface),
            contentPadding=androidx.compose.foundation.layout.PaddingValues(horizontal=12.dp),
        ) {
            Icon(Icons.Rounded.PhotoLibrary,null,Modifier.size(19.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("观景台",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
        }
        }
        Text("通过 Android 照片选择器导入，仅读取你选择的照片。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(smartVisible)SmartDevelopSheet(vm,onDismiss={smartVisible=false})
}

@Composable
private fun HomeSession(vm: EditorViewModel, onAddPhotos: () -> Unit, onOpenEditor: () -> Unit) {
    val strip = rememberLazyListState()
    LaunchedEffect(vm.currentIndex, vm.photos.size) {
        if (vm.currentIndex in vm.photos.indices) {
            val item = strip.layoutInfo.visibleItemsInfo.firstOrNull { it.index == vm.currentIndex }
            if (item == null || item.offset < strip.layoutInfo.viewportStartOffset ||
                item.offset + item.size > strip.layoutInfo.viewportEndOffset) strip.scrollToItem(vm.currentIndex)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当前会话", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${vm.photos.size} 张照片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAddPhotos, enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting) { Text("添加照片") }
        }
        LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "首页会话照片列表" }) {
            itemsIndexed(vm.photos, key = { _, uri -> uri.toString() }) { index, uri ->
                val current = index == vm.currentIndex
                val thumbnail = vm.developedPreviews[uri] ?: vm.thumbnails[uri]
                if (thumbnail == null) LaunchedEffect(uri) { vm.loadThumbnail(uri) }
                val identity = rememberSourcePhotoInfo(uri)
                Box(Modifier.width(112.dp).height(84.dp).clip(TheiaShapes.thumbnail)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (current) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, TheiaShapes.thumbnail) else Modifier)
                    .clickable(enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting, role = Role.Button) {
                        if (index != vm.currentIndex) vm.selectPhoto(index)
                        onOpenEditor()
                    }.semantics(mergeDescendants = true) {
                        contentDescription = "编辑会话照片 ${index + 1}"
                        selected = current
                        stateDescription = if (current) "编辑中 · ${identity.detail}" else identity.detail
                    }) {
                    thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(if (current) 2.dp else 0.dp), contentScale = ContentScale.Crop) }
                    val number = (index + 1).toString().padStart(2, '0')
                    Text(if (current) "$number · 编辑中" else "$number · ${identity.format}",
                        Modifier.align(Alignment.BottomStart).padding(6.dp)
                            .background(Color.Black.copy(alpha = .75f), TheiaShapes.badge)
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    if (current) Text(identity.format,
                        Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color.Black.copy(alpha = .75f), TheiaShapes.badge)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Text("编辑参数自动保留。点击照片继续显影，完成后导出成片。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
