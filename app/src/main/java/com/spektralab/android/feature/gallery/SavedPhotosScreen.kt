package com.spektralab.android.feature.gallery

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spektralab.android.R
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun SavedPhotosScreen(controller: SavedPhotosController, enabled: Boolean,
                              onBack: () -> Unit, onImport: () -> Unit, onOpen: (Uri) -> Unit,
                              onRestore: (SavedEditSession) -> Unit = {}, onRelink: (Uri, Uri) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
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
            IconButton(onClick = onBack, enabled = enabled) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.photos_back)) }
            Text(stringResource(R.string.photos_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = controller::refresh, enabled = !controller.loading) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.photos_refresh))
            }
            IconButton(onClick = onImport, enabled = enabled && !controller.loading) {
                Icon(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.photos_import))
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.photos_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                }
                item {
                    LibraryFilterBar(controller, enabled && !controller.loading && !controller.organization.pending)
                }
                controller.savedSession?.takeIf { it.photos.isNotEmpty() }?.let { session -> item {
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
                    val label = stringResource(R.string.photos_open, photo.name)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Row(Modifier.weight(1f).clip(TheiaShapes.control)
                        .clickable(enabled = enabled && !controller.loading && !controller.organization.pending, role = Role.Button) { controller.open(photo, onOpen) }
                        .semantics(mergeDescendants = true) { contentDescription = label }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(92.dp, 76.dp).clip(TheiaShapes.thumbnail).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center) {
                            val bitmap = controller.thumbnails[photo.assetId]
                            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(photo.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.photos_continue), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            PhotoMarks(photo.rating, photo.label)
                            if (photo.sourceSha256 != null) Text(stringResource(R.string.photos_source_verified),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                      }
                      Box {
                        IconButton(onClick = { menu = true }, enabled = enabled && !controller.loading && !controller.organization.pending) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.photos_source_actions, photo.name))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
    PhotoOrganizationSheets(controller.organization)
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
