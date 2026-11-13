package com.spektralab.android.feature.gallery

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.AlbumNames
import com.spektralab.android.core.model.EditVersion
import com.spektralab.android.data.library.SavedPhoto
import com.spektralab.android.ui.theme.TheiaShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditVersionsSheet(controller: EditVersionsController, enabled: Boolean, onOpen: (SavedPhoto, EditVersion) -> Unit,
                               inUse: (Uri) -> Boolean) {
    val photo = controller.photo ?: return
    var naming by remember { mutableStateOf<EditVersion?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<EditVersion?>(null) }
    ModalBottomSheet(onDismissRequest = controller::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { !controller.pending || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.versions_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = controller::close, enabled = !controller.pending) { Icon(Icons.Rounded.Close, stringResource(R.string.versions_close)) }
            }
            Text(photo.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.versions_hint), Modifier.padding(top = 12.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (controller.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(controller.versions, key = { it.id }) { version ->
                    val title = if (version.isDefault) stringResource(R.string.versions_default) else version.name
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(TheiaShapes.control).clickable(enabled && !controller.pending) { onOpen(photo, version) }
                            .heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (version.isDefault) Icons.Rounded.Photo else Icons.Rounded.Layers, null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.versions_open_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        var menu by remember(version.id) { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = enabled && !controller.pending) {
                                Icon(Icons.Rounded.MoreVert, stringResource(R.string.versions_actions, title))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.versions_copy)) }, onClick = {
                                    menu = false; renaming = false; naming = version; controller.dismissError()
                                })
                                if (!version.isDefault) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.versions_rename)) }, onClick = {
                                        menu = false; renaming = true; naming = version; controller.dismissError()
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.versions_delete)) }, onClick = {
                                        menu = false; deletion = version; controller.dismissError()
                                    })
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                controller.error?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } }
            }
            TextButton(onClick = { controller.versions.firstOrNull()?.let { renaming = false; naming = it; controller.dismissError() } },
                enabled = enabled && !controller.pending && controller.versions.size <= 30, modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 12.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.versions_create))
            }
        }
    }
    naming?.let { version -> key(version.id, renaming) {
        var name by remember { mutableStateOf(if (renaming) version.name else "") }
        val valid = remember(name) { runCatching { AlbumNames.normalize(name) }.isSuccess }
        AlertDialog(onDismissRequest = { if (!controller.pending) { naming = null; controller.dismissError() } },
            title = { Text(stringResource(if (renaming) R.string.versions_rename else R.string.versions_create)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it; controller.dismissError() }, enabled = !controller.pending, singleLine = true,
                    label = { Text(stringResource(R.string.versions_name)) }, supportingText = { Text(stringResource(R.string.library_album_name_hint)) },
                    isError = name.isNotEmpty() && !valid, shape = TheiaShapes.control)
                controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            dismissButton = { TextButton(onClick = { naming = null; controller.dismissError() }, enabled = !controller.pending) { Text(stringResource(R.string.library_cancel)) } },
            confirmButton = { TextButton(onClick = {
                if (renaming) controller.rename(version, name) { naming = null } else controller.create(version, name) { naming = null }
            }, enabled = valid && !controller.pending) { Text(stringResource(R.string.library_save)) } })
    } }
    deletion?.let { version -> AlertDialog(onDismissRequest = { if (!controller.pending) { deletion = null; controller.dismissError() } },
        title = { Text(stringResource(R.string.versions_delete)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.versions_delete_hint, version.name))
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        dismissButton = { TextButton(onClick = { deletion = null; controller.dismissError() }, enabled = !controller.pending) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { TextButton(onClick = { controller.delete(version, { inUse(version.editUri) }) { deletion = null } },
            enabled = !controller.pending) { Text(stringResource(R.string.library_delete)) } }) }
}
