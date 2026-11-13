package com.spektralab.android.feature.lighttable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.AlbumNames
import com.spektralab.android.core.model.TableProjectSummary
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.roundToInt

@Stable
internal class TableProjectUiState {
    var naming by mutableStateOf(false)
    var rename by mutableStateOf<TableProjectSummary?>(null)
    var deletion by mutableStateOf<TableProjectSummary?>(null)
    var leave by mutableStateOf<(() -> Unit)?>(null)
    var placement by mutableStateOf<String?>(null)
    var adding by mutableStateOf(false)
    fun request(controller: LightTableProjectsController, action: () -> Unit) {
        if (controller.pending) return
        if (controller.dirty) leave = action else action()
    }
}

@Composable
internal fun TableProjectToolbar(controller: LightTableProjectsController, state: FilmCanvasState,
    ui: TableProjectUiState, lightOn: Boolean, enabled: Boolean, onExport: () -> Unit = {}, onSave: () -> Unit) {
    val ink = if (lightOn) StandColors.ink else StandColors.white
    val accent = if (lightOn) StandColors.brown else StandColors.amber
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controller::showProjects, enabled = !controller.pending, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(contentColor = accent)) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(controller.current?.name ?: stringResource(R.string.table_projects), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = state::undo, enabled = enabled && !controller.pending && state.canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.table_undo), tint = if (enabled && !controller.pending && state.canUndo) ink else ink.copy(alpha = .38f))
            }
            IconButton(onClick = state::redo, enabled = enabled && !controller.pending && state.canRedo) {
                Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.table_redo), tint = if (enabled && !controller.pending && state.canRedo) ink else ink.copy(alpha = .38f))
            }
            IconButton(onClick = onSave, enabled = enabled && !controller.pending) {
                Icon(Icons.Rounded.Save, stringResource(R.string.table_save), tint = accent)
            }
            IconButton(onClick = onExport, enabled = enabled && !controller.pending) {
                Icon(Icons.Rounded.FileDownload, stringResource(R.string.table_output), tint = accent)
            }
        }
        if (controller.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (controller.current != null) Text(
            if (controller.dirty) stringResource(R.string.table_unsaved) else controller.message ?: stringResource(R.string.table_preview_hint),
            Modifier.padding(start = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = if (lightOn) StandColors.muted else StandColors.soft)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TableProjectPanels(controller: LightTableProjectsController, ui: TableProjectUiState,
    available: List<ViewingPhoto>, draft: FilmCanvasState, enabled: Boolean, beforeSave: () -> Unit) {
    val state = if (controller.current == null) draft else controller.canvas
    if (controller.manager) ModalBottomSheet(onDismissRequest = { if (!controller.pending) controller.manager = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !controller.pending || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.table_projects), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { controller.manager = false }, enabled = !controller.pending) { Icon(Icons.Rounded.Close, stringResource(R.string.table_close_list)) }
            }
            Text(stringResource(R.string.table_project_hint), Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
            if (controller.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (controller.projects.isEmpty() && !controller.pending) item {
                    Text(stringResource(R.string.table_empty_projects), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(controller.projects, key = { it.id }) { project ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(enabled && !controller.pending) {
                            ui.request(controller) { controller.open(project) }
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Dashboard, null, tint = MaterialTheme.colorScheme.primary)
                            Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        var menu by remember(project.id) { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !controller.pending) {
                                Icon(Icons.Rounded.MoreVert, stringResource(R.string.table_project_actions, project.name))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.table_rename)) }, onClick = { menu = false; ui.rename = project; controller.dismissError() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.table_delete)) }, onClick = { menu = false; ui.deletion = project; controller.dismissError() })
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { ui.rename = null; ui.naming = true; controller.dismissError() }, enabled = enabled && !controller.pending) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (controller.current == null) R.string.table_create else R.string.table_save_as))
                }
                if (controller.current != null) TextButton(onClick = { ui.request(controller) { controller.close(); controller.manager = false } }, enabled = !controller.pending) {
                    Text(stringResource(R.string.table_close_project))
                }
            }
        }
    }
    if (ui.naming || ui.rename != null) {
        val target = ui.rename
        key(target?.id, ui.naming) {
            var name by remember { mutableStateOf(target?.name ?: "") }
            val valid = runCatching { AlbumNames.normalize(name) }.isSuccess
            fun dismiss() { ui.naming = false; ui.rename = null; controller.dismissError() }
            AlertDialog(onDismissRequest = { if (!controller.pending) dismiss() },
                title = { Text(stringResource(if (target != null) R.string.table_rename else R.string.table_save)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it; controller.dismissError() }, enabled = !controller.pending, singleLine = true,
                        label = { Text(stringResource(R.string.table_name)) }, supportingText = { Text(stringResource(R.string.library_album_name_hint)) },
                        isError = name.isNotEmpty() && !valid, shape = TheiaShapes.control)
                    controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } },
                dismissButton = { TextButton(onClick = ::dismiss, enabled = !controller.pending) { Text(stringResource(R.string.library_cancel)) } },
                confirmButton = { TextButton(onClick = {
                    beforeSave()
                    if (target != null) controller.rename(target, name, ::dismiss)
                    else if (controller.current == null) controller.create(name, available, draft, ::dismiss)
                    else controller.saveAs(name, available, ::dismiss)
                }, enabled = valid && !controller.pending) { Text(stringResource(R.string.table_save)) } })
        }
    }
    ui.deletion?.let { project -> AlertDialog(onDismissRequest = { if (!controller.pending) { ui.deletion = null; controller.dismissError() } },
        title = { Text(stringResource(R.string.table_delete)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.table_delete_hint, project.name)); controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, dismissButton = { TextButton(onClick = { ui.deletion = null; controller.dismissError() }, enabled = !controller.pending) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { TextButton(onClick = { controller.delete(project) { ui.deletion = null } }, enabled = !controller.pending) { Text(stringResource(R.string.library_delete)) } }) }
    ui.leave?.let { action -> AlertDialog(onDismissRequest = { if (!controller.pending) ui.leave = null },
        title = { Text(stringResource(R.string.table_leave)) }, text = { Text(stringResource(R.string.table_leave_hint)) },
        dismissButton = { TextButton(onClick = { ui.leave = null; controller.discardChanges(); action() }, enabled = !controller.pending) { Text(stringResource(R.string.table_discard)) } },
        confirmButton = { TextButton(onClick = { beforeSave(); controller.saveCurrent(available) { ui.leave = null; action() } }, enabled = !controller.pending) { Text(stringResource(R.string.table_save)) } }) }
    if (ui.adding) ModalBottomSheet(onDismissRequest = { if (!controller.pending) ui.adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.table_add), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (available.isEmpty()) R.string.table_add_empty else R.string.table_add_hint), Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(available, key = { it.key }) { photo ->
                    TextButton(onClick = { controller.add(listOf(photo)); ui.adding = false }, enabled = enabled && !controller.pending) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(12.dp)); Text(photo.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    ui.placement?.let { key ->
        val photo = if (controller.current != null) controller.canvas.members.firstOrNull { it.id == key }?.source?.name else available.firstOrNull { it.key == key }?.name
        if (photo == null) ui.placement = null else TablePlacementSheet(key, photo, state, controller.current != null, enabled && !controller.pending,
            onDismiss = { state.finishGesture(cancelled = true); ui.placement = null })
    }
    if (controller.error != null && !ui.naming && ui.rename == null && ui.deletion == null) AlertDialog(onDismissRequest = controller::dismissError,
        title = { Text(stringResource(R.string.table_projects)) }, text = { Text(requireNotNull(controller.error)) },
        confirmButton = { TextButton(onClick = controller::dismissError) { Text(stringResource(R.string.photos_understood)) } })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TablePlacementSheet(key: String, name: String, state: FilmCanvasState, project: Boolean,
    enabled: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.table_place), style = MaterialTheme.typography.titleLarge)
            Text(name, Modifier.padding(vertical = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val rotation = state.rotations[key] ?: 0f
                    Text(stringResource(R.string.table_rotation) + " · ${rotation.roundToInt()}°", style = MaterialTheme.typography.titleSmall)
                    Slider(rotation, onValueChange = { state.beginGesture(); state.rotations[key] = it }, enabled = enabled,
                        onValueChangeFinished = { state.finishGesture() }, valueRange = -180f..180f,
                        modifier = Modifier.semantics { contentDescription = "旋转角度" })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { state.rotate(key, normalize(rotation - 90f)) }, enabled = enabled) { Icon(Icons.Rounded.RotateLeft, stringResource(R.string.table_rotate_left)) }
                        OutlinedButton(onClick = { state.rotate(key, normalize(rotation + 90f)) }, enabled = enabled) { Icon(Icons.Rounded.RotateRight, stringResource(R.string.table_rotate_right)) }
                    }
                }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { state.layer(key, true) }, enabled = enabled) { Icon(Icons.Rounded.FlipToFront, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.table_front)) }
                    OutlinedButton(onClick = { state.layer(key, false) }, enabled = enabled) { Icon(Icons.Rounded.FlipToBack, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.table_back)) }
                    if (project) OutlinedButton(onClick = { state.duplicate(key) }, enabled = enabled && state.members.size < 200) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.table_duplicate)) }
                } }
                if (project) item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.millimeters, enabled, Role.Switch, state::changeUnits), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.table_units), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(state.millimeters, onCheckedChange = null, enabled = enabled)
                    }
                    Text(stringResource(R.string.table_units_hint), style = MaterialTheme.typography.bodySmall)
                    if (state.millimeters) {
                        val size = state.photoSizes[key]
                        if (size != null) {
                            val limit = minOf(10000f, 10000f * size.width / size.height).coerceAtLeast(1f)
                            var width by remember(key, size.width) { mutableStateOf(size.width.roundToInt().toString()) }
                            val value = width.toFloatOrNull()
                            OutlinedTextField(width, { width = it }, singleLine = true, enabled = enabled, shape = TheiaShapes.control,
                                label = { Text(stringResource(R.string.table_width)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                supportingText = { Text(stringResource(R.string.table_width_hint, limit.toInt())) })
                            TextButton(onClick = { value?.let { state.width(key, it) } }, enabled = enabled && value != null && value.isFinite() && value in 1f..limit) {
                                Text(stringResource(R.string.library_save))
                            }
                        }
                    }
                }
                if (project) item { TextButton(onClick = { state.remove(key); onDismiss() }, enabled = enabled) { Text(stringResource(R.string.table_remove)) } }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.table_done)) }
        }
    }
}

private fun normalize(value: Float) = ((value + 180f) % 360f + 360f) % 360f - 180f
