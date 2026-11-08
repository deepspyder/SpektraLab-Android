package com.spektralab.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BatchExportSelection(vm: EditorViewModel, enabled: Boolean) {
    val selectedCount = vm.selectedBatchExportPhotos.size
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 $selectedCount / ${vm.photos.size} 张", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { vm.selectAllBatchExportPhotos(true) },
                enabled = enabled && selectedCount < vm.photos.size) { Text("全选") }
            TextButton(onClick = { vm.selectAllBatchExportPhotos(false) },
                enabled = enabled && selectedCount > 0) { Text("清空") }
        }
        if (vm.photos.isEmpty()) {
            Text("当前会话还没有照片", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(vm.photos, key = { _, uri -> uri.toString() }) { index, uri ->
                    BatchExportPhoto(vm, uri, index, enabled)
                }
            }
        }
    }
}

@Composable
private fun BatchExportPhoto(vm: EditorViewModel, uri: Uri, index: Int, enabled: Boolean) {
    val context = LocalContext.current
    val selected = uri in vm.batchExportSelection
    val name by produceState(uri.lastPathSegment?.substringAfterLast('/') ?: "照片 ${index + 1}", uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: value
        }
    }
    LaunchedEffect(uri) { vm.loadThumbnail(uri) }
    // Reuse small previews; selecting an export never switches the editor's active photo.
    val preview = vm.developedPreviews[uri] ?: vm.thumbnails[uri]
    val shape = SpektraShapes.thumbnail
    Column(Modifier.width(104.dp).clip(shape)
        .toggleable(value = selected, enabled = enabled, role = Role.Checkbox,
            onValueChange = { vm.setBatchExportPhotoSelected(uri, it) })
        .semantics(mergeDescendants = true) { contentDescription = "导出照片 ${index + 1}：$name" }) {
        Box(Modifier.fillMaxWidth().height(88.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)) {
            if (preview != null) {
                Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Rounded.Photo, null, Modifier.align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), SpektraShapes.badge))
        }
        Text(name, Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 4.dp),
            fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
