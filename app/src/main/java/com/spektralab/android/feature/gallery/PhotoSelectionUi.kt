package com.spektralab.android.feature.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.core.model.PhotoAlbum
import com.spektralab.android.core.model.TableProjectSummary
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun PhotoSelectionBar(selection: PhotoSelectionController, filter: PhotoLibraryFilter,
                               count: Long, enabled: Boolean, albums: List<PhotoAlbum>,
                               projects: List<TableProjectSummary>, onAddToViewing: () -> Unit,
                               onAddToProject: (TableProjectSummary) -> Unit) {
    var showAlbums by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.photos_selection_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selection.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { selection.selectAll(filter) }, enabled = enabled && !selection.pending && count > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.photos_select_all, count))
                }
                Button(onClick = selection::requestRemoval,
                    enabled = enabled && !selection.pending && selection.selected.isNotEmpty(),
                    shape = TheiaShapes.control, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.photos_remove_count, selection.selected.size))
                }
                OutlinedButton(onClick = { showAlbums = true }, enabled = enabled && !selection.pending && selection.selected.isNotEmpty(),
                    shape = TheiaShapes.control, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.photos_add_album))
                }
                OutlinedButton(onClick = onAddToViewing, enabled = enabled && !selection.pending && selection.selected.isNotEmpty(),
                    shape = TheiaShapes.control, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.photos_add_viewing))
                }
                OutlinedButton(onClick = { showProjects = true }, enabled = enabled && !selection.pending && selection.selected.isNotEmpty() && projects.isNotEmpty(),
                    shape = TheiaShapes.control, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.photos_add_project))
                }
            }
        }
      }
    }
    if (showAlbums) {
        AlertDialog(onDismissRequest = { showAlbums = false },
            title = { Text(stringResource(R.string.photos_choose_album)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (albums.isEmpty()) Text("请先创建一个相册")
                    albums.forEach { album ->
                        TextButton(onClick = { showAlbums = false; selection.addToAlbum(album.id, album.name) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(album.name) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showAlbums = false }) { Text(stringResource(R.string.library_cancel)) } })
    }
    if (showProjects) {
        AlertDialog(onDismissRequest = { showProjects = false },
            title = { Text(stringResource(R.string.photos_choose_project)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                projects.forEach { project ->
                    TextButton(onClick = { showProjects = false; onAddToProject(project) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(project.name) }
                }
            } }, confirmButton = { TextButton(onClick = { showProjects = false }) { Text(stringResource(R.string.library_cancel)) } })
    }
}

@Composable
internal fun PhotoRemovalDialog(selection: PhotoSelectionController, enabled: Boolean) {
    if (!selection.confirming) return
    AlertDialog(onDismissRequest = selection::dismissRemoval,
        title = { Text(stringResource(R.string.photos_remove_title, selection.selected.size)) },
        text = { Text(stringResource(R.string.photos_remove_hint)) },
        confirmButton = { TextButton(onClick = selection::remove, enabled = enabled && !selection.pending) {
            Text(stringResource(if (selection.pending) R.string.photos_removing else R.string.photos_remove))
        } },
        dismissButton = { TextButton(onClick = selection::dismissRemoval, enabled = !selection.pending) {
            Text(stringResource(R.string.library_cancel))
        } })
}
