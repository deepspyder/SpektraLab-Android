package com.spektralab.android.feature.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun PhotoSelectionBar(selection: PhotoSelectionController, filter: PhotoLibraryFilter,
                               count: Long, enabled: Boolean) {
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
            }
        }
      }
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
