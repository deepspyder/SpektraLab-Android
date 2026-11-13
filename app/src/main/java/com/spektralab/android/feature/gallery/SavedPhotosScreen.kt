package com.spektralab.android.feature.gallery

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MoreVert
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.data.library.SavedPhoto
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spektralab.android.R
import com.spektralab.android.ui.theme.TheiaShapes

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SavedPhotosScreen(controller: SavedPhotosController, enabled: Boolean,
                              onBack: () -> Unit, onImport: () -> Unit, onOpen: (Uri) -> Unit,
                              onRestore: (SavedEditSession) -> Unit = {}, onRelink: (Uri, Uri) -> Unit = { _, _ -> },
                              onVersionOpen: (Uri) -> Unit = onOpen, versionInUse: (Uri) -> Boolean = { true },
                              prepareVersions: () -> Unit = {}) {
    val context = LocalContext.current
    val selection = controller.selection
    val canAct = enabled && !controller.loading && !controller.organization.pending && !selection.pending
    fun back() { if (selection.active) selection.clear() else onBack() }
    BackHandler(enabled = selection.active || selection.pending) { if (!selection.pending) selection.clear() }
    var checkTarget by remember { mutableStateOf<SavedPhoto?>(null) }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) controller.cancelRelinkPicker() else {
            val persistent = uri.scheme == "file" || runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            }.getOrDefault(false)
            controller.relink(uri, onRelink, persistent)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.refresh() }
        owner.lifecycle.addObserver(observer)
        controller.refresh()
        onDispose { owner.lifecycle.removeObserver(observer); controller.leave() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back, enabled = enabled && !selection.pending) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.photos_back)) }
            Text(if (selection.active) stringResource(R.string.photos_selected, selection.selected.size) else stringResource(R.string.photos_title),
                Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { if (selection.active) selection.clear() else selection.begin() },
                enabled = canAct && (selection.active || controller.photos.isNotEmpty())) {
                Text(stringResource(if (selection.active) R.string.library_cancel else R.string.photos_select))
            }
            if (!selection.active) {
            IconButton(onClick = controller::refresh, enabled = canAct) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.photos_refresh))
            }
            IconButton(onClick = onImport, enabled = canAct) {
                Icon(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.photos_import))
            }
            }
        }
    }, bottomBar = {
        if (selection.active) PhotoSelectionBar(selection, controller.filter, controller.matchingCount, canAct)
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(if (selection.active) R.string.photos_selection_detail else R.string.photos_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                }
                item {
                    LibraryFilterBar(controller, canAct && !selection.active)
                }
                controller.savedSession?.takeIf { it.photos.isNotEmpty() && !selection.active }?.let { session -> item {
                    Row(Modifier.fillMaxWidth().clip(TheiaShapes.panel)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.photos_session_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.photos_session_detail, session.photos.size, session.selected.size),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { controller.restoreSession(onRestore) }, enabled = enabled && !controller.loading,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.photos_session_resume)) }
                    }
                } }
                if (controller.loading) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (controller.checkingAsset != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (controller.committingSource) R.string.photos_check_committing else R.string.photos_check_running),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = controller::cancelSourceCheck, enabled = !controller.committingSource,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.photos_check_stop)) }
                    }
                }
                controller.sourceMessage?.let { message -> item {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } }
                selection.message?.let { message -> item {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } }
                selection.error?.let { message -> item {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } }
                controller.sessionError?.let { message -> item {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } }
                controller.error?.let { message -> item {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = controller::refresh) { Text(stringResource(R.string.photos_retry)) }
                } }
                if (controller.organization.target == null && !controller.organization.albumManager) controller.organization.error?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = controller.organization::dismissError) { Text(stringResource(R.string.library_cancel)) }
                } }
                if (controller.photos.isEmpty() && !controller.loading && controller.error == null) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Image, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (controller.filter.active) R.string.library_no_results else R.string.photos_empty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                        Text(stringResource(if (controller.filter.active) R.string.library_no_results_hint else R.string.photos_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { if (controller.filter.active) controller.applyFilter(com.spektralab.android.core.model.PhotoLibraryFilter()) else onImport() },
                            enabled = enabled && !controller.organization.pending, shape = TheiaShapes.control, modifier = Modifier.padding(top = 24.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(if (controller.filter.active) R.string.library_filter_clear else R.string.photos_import))
                        }
                    }
                }
                items(controller.photos, key = { it.assetId }) { photo ->
                    DisposableEffect(photo.assetId, photo.uri) {
                        controller.thumbnail(photo)
                        onDispose { controller.cancelThumbnail(photo.assetId) }
                    }
                    var menu by remember(photo.assetId) { mutableStateOf(false) }
                    val label = stringResource(if (selection.active) R.string.photos_select_photo else R.string.photos_open, photo.name)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Row(Modifier.weight(1f).clip(TheiaShapes.control)
                        .background(if (photo.assetId in selection.selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
                        .combinedClickable(enabled = canAct, role = if (selection.active) Role.Checkbox else Role.Button,
                            onLongClick = { selection.begin(photo) },
                            onClick = { if (selection.active) selection.toggle(photo) else controller.open(photo, onOpen) })
                        .semantics(mergeDescendants = true) {
                            contentDescription = label
                            if (selection.active) {
                                selected = photo.assetId in selection.selected
                                toggleableState = ToggleableState(photo.assetId in selection.selected)
                            }
                        }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (selection.active) 8.dp else 16.dp)) {
                        if (selection.active) Checkbox(checked = photo.assetId in selection.selected, onCheckedChange = null,
                            modifier = Modifier.size(48.dp))
                        Box(Modifier.size(if (selection.active) 72.dp else 92.dp, 76.dp).clip(TheiaShapes.thumbnail).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center) {
                            val bitmap = controller.thumbnails[photo.assetId]
                            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(photo.name, style = MaterialTheme.typography.titleSmall,
                                maxLines = if (selection.active) 1 else 2, overflow = TextOverflow.Ellipsis)
                            if (!selection.active) Text(stringResource(R.string.photos_continue), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            PhotoMarks(photo.rating, photo.label)
                            if (photo.sourceSha256 != null) Text(stringResource(R.string.photos_source_verified),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                      }
                      if (!selection.active) Box {
                        IconButton(onClick = { menu = true }, enabled = enabled && !controller.loading && !controller.organization.pending) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.photos_source_actions, photo.name))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.photos_remove)) }, onClick = {
                                menu = false; selection.begin(photo); selection.requestRemoval()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.versions_title)) }, onClick = {
                                menu = false; prepareVersions(); controller.versions.open(photo)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_organize)) }, onClick = {
                                menu = false; controller.organization.open(photo)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.photos_check_source)) },
                                onClick = { menu = false; checkTarget = photo })
                            DropdownMenuItem(text = { Text(stringResource(R.string.photos_relink_source)) }, onClick = {
                                menu = false
                                controller.prepareRelink(photo)
                                // RAW providers use both image/* and vendor application/* MIME types.
                                // Byte verification, rather than a MIME guess, authorizes relocation.
                                sourcePicker.launch(arrayOf("*/*"))
                            })
                        }
                      }
                    }
                    controller.sourceErrors[photo.assetId]?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 12.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (controller.hasMore) item {
                    TextButton(onClick = controller::more, enabled = !controller.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.photos_more))
                    }
                }
            }
        }
    }
    PhotoRemovalDialog(selection, canAct)
    PhotoOrganizationSheets(controller.organization)
    EditVersionsSheet(controller.versions, enabled && !controller.loading, onOpen = { photo, version ->
        controller.open(photo) { _ -> controller.versions.close(); onVersionOpen(version.editUri) }
    }, inUse = versionInUse)
    checkTarget?.let { photo ->
        AlertDialog(onDismissRequest = { checkTarget = null },
            title = { Text(stringResource(R.string.photos_check_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(photo.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.photos_check_hint))
            } },
            confirmButton = { TextButton(onClick = { checkTarget = null; controller.checkSource(photo) }) {
                Text(stringResource(R.string.photos_check_begin))
            } },
            dismissButton = { TextButton(onClick = { checkTarget = null }) { Text(stringResource(R.string.photos_check_cancel)) } })
    }
}
