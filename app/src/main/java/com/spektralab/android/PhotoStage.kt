package com.spektralab.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * The selected photo tray. It is deliberately independent from the parameter
 * panel: selection and editing remain visible while the user moves through a
 * multi-photo set.
 */
@Composable
fun PhotoStage(
    vm: EditorViewModel,
    onAddPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("展示台", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddPhotos, enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting) {
                Text("添加照片")
            }
            if (vm.photos.isNotEmpty()) {
                IconButton(onClick = { showInfo = true }, enabled = !vm.busy) {
                    Icon(Icons.Filled.Info, contentDescription = "照片信息")
                }
                IconButton(onClick = vm::removeCurrentPhoto, enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "从展示台移除照片")
                }
                Text("${vm.currentIndex + 1} / ${vm.photos.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.dirty) {
                Text(" · 已修改", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (vm.photos.isEmpty()) {
            Text("从手机相册选择照片后，会在这里逐张编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(76.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(vm.photos, key = { _, uri -> uri.toString() }) { index, uri ->
                    val thumb = vm.thumbnails[uri]
                    if (thumb == null) LaunchedEffect(uri) { vm.loadThumbnail(uri) }
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(if (index == vm.currentIndex) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable(enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting) { vm.selectPhoto(index) },
                    ) {
                        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(72.dp), contentScale = ContentScale.Crop) }
                        if (index == vm.currentIndex) {
                            Text("当前", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .9f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.previousPhoto() },
                    enabled = vm.currentIndex > 0 && !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                    modifier = Modifier.weight(1f),
                ) { Text("上一张") }
                OutlinedButton(
                    onClick = { vm.nextPhoto() },
                    enabled = vm.currentIndex < vm.photos.lastIndex && !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                    modifier = Modifier.weight(1f),
                ) { Text("下一张") }
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("照片信息") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.photoInfo.isEmpty()) Text("没有可用的照片信息")
                    vm.photoInfo.forEach { (label, value) ->
                        Text("$label：$value", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } },
        )
    }
}
