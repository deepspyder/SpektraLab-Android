package com.spektralab.android.feature.editor.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.*
import com.spektralab.android.feature.editor.EditTransferPreview
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaShapes

/** A focused extension of the darkroom: choose fields, inspect targets, then apply once. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun EditToolsSheet(vm: EditorViewModel, presets: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var page by rememberSaveable { mutableStateOf(if (presets) "presets" else "groups") }
    var groups by rememberSaveable { mutableStateOf(EditGroup.DEFAULT.map { it.key }) }
    var targetKeys by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var sourceJson by rememberSaveable { mutableStateOf<String?>(null) }
    val source = remember(sourceJson) { sourceJson?.let { EditTemplate.fromJson(org.json.JSONObject(it)) } }
    val groupNames = EditGroup.entries.map { groupName(it) }
    var sourceName by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var namingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<EditTransferPreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val initialApplied by rememberSaveable { mutableIntStateOf(vm.editTransferRevision) }
    val catalog = vm.editPresets
    var savedAt by remember { mutableStateOf<Int?>(null) }
    val selectedGroups = groups.mapNotNull { key -> EditGroup.entries.firstOrNull { it.key == key } }.toSet()
    val enabled = vm.canTransferEdits && !catalog.pending
    val title = when (page) {
        "presets" -> R.string.edit_presets_title
        "save" -> R.string.edit_save_preset
        "rename" -> R.string.edit_rename_preset
        "targets" -> R.string.edit_select_targets
        else -> R.string.edit_copy_title
    }
    val invalidTemplateMessage = stringResource(R.string.edit_template_unreadable)
    val unavailableMessage = stringResource(R.string.edit_preview_error)
    val duplicate = name.isNotBlank() && catalog.items.any {
        it.id != namingId && it.name.equals(name.trim(), ignoreCase = true)
    }
    val validName = runCatching { EditPreset.normalizedName(name) }.isSuccess && !duplicate
    val selected = vm.photos.filter { it.toString() in targetKeys }

    LaunchedEffect(vm.editTransferRevision) {
        if (vm.editTransferRevision != initialApplied) onDismiss()
    }
    LaunchedEffect(catalog.revision) {
        savedAt?.let { if (catalog.revision != it) { page = "presets"; savedAt = null; name = "" } }
    }
    LaunchedEffect(page, source, targetKeys, refresh, vm.photos) {
        if (page != "targets" || source == null) return@LaunchedEffect
        preview = null
        loading = true
        localError = null
        try { preview = vm.previewEditTransfer(source!!, selected) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { localError = unavailableMessage }
        finally { loading = false }
    }

    fun chooseTemplate(template: EditTemplate, label: String, copy: Boolean) {
        sourceJson = template.toJson().toString()
        sourceName = label
        targetKeys = if (copy) vm.photos.filter { it != vm.currentUri }.map(Uri::toString)
            else listOfNotNull(vm.currentUri?.toString())
        page = "targets"
        localError = null
    }

    ModalBottomSheet(onDismissRequest = { if (!vm.isApplyingEdits && !catalog.pending) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = TheiaShapes.sheet,
        containerColor = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
            .imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != "presets" && page != "groups") IconButton(enabled = !vm.isApplyingEdits && !catalog.pending,
                    onClick = { page = if (presets || page in setOf("save", "rename")) "presets" else "groups"; localError = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.edit_back))
                } else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(if (page == "presets") Icons.Rounded.Bookmarks else Icons.Rounded.ContentCopy, null, tint = colors.primary)
                }
                Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, enabled = !vm.isApplyingEdits && !catalog.pending) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.edit_close))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (page) {
                    "groups", "save" -> {
                        if (page == "save") OutlinedTextField(name, { name = it.take(48) },
                            label = { Text(stringResource(R.string.edit_preset_name)) }, singleLine = true,
                            isError = duplicate, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = TheiaShapes.control)
                        if (duplicate) Text(stringResource(R.string.edit_duplicate_name), color = colors.error, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.edit_groups_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        EditGroup.entries.forEach { group ->
                            GroupChoice(group, group in selectedGroups, enabled) {
                                groups = if (group.key in groups) groups - group.key else groups + group.key
                            }
                        }
                        Text(stringResource(R.string.edit_local_values_hint), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    "rename" -> {
                        OutlinedTextField(name, { name = it.take(48) }, label = { Text(stringResource(R.string.edit_preset_name)) },
                            singleLine = true, enabled = enabled, isError = duplicate, shape = TheiaShapes.control,
                            modifier = Modifier.fillMaxWidth())
                        if (duplicate) Text(stringResource(R.string.edit_duplicate_name), color = colors.error)
                    }
                    "presets" -> {
                        Text(stringResource(R.string.edit_presets_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (catalog.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (catalog.items.isEmpty() && catalog.ready) {
                            Text(stringResource(R.string.edit_presets_empty), style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
                            Text(stringResource(R.string.edit_presets_empty_hint), style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
                        }
                        catalog.items.forEach { preset ->
                            Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { chooseTemplate(preset.template, preset.name, copy = false) }, enabled = enabled,
                                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 0.dp), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(preset.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                                            Text(preset.template.groups.joinToString(" · ") { groupNames[it.ordinal] }, style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    IconButton(onClick = { namingId = preset.id; name = preset.name; page = "rename" }, enabled = enabled) {
                                        Icon(Icons.Rounded.Edit, stringResource(R.string.edit_rename_named, preset.name), Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { deleteId = preset.id }, enabled = enabled) {
                                        Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.edit_delete_named, preset.name), Modifier.size(20.dp))
                                    }
                                }
                                HorizontalDivider(color = colors.outlineVariant)
                            }
                        }
                        if (catalog.unsupported > 0) Text(stringResource(R.string.edit_unknown_presets, catalog.unsupported),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (catalog.error != null) TextButton(onClick = catalog::reload, enabled = !catalog.pending) {
                            Text(stringResource(R.string.edit_retry))
                        }
                    }
                    "targets" -> {
                        Text(sourceName, style = MaterialTheme.typography.titleSmall)
                        Text(source?.groups?.joinToString(" · ") { groupNames[it.ordinal] }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { targetKeys = vm.photos.filter { presets || it != vm.currentUri }.map(Uri::toString) }, enabled = enabled) {
                                Text(stringResource(R.string.edit_select_all))
                            }
                            TextButton(onClick = { targetKeys = emptyList() }, enabled = enabled) { Text(stringResource(R.string.edit_clear_selection)) }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        vm.photos.filter { presets || it != vm.currentUri }.forEach { uri ->
                            val row = preview?.targets?.firstOrNull { it.uri == uri }
                            TargetChoice(vm, uri, uri.toString() in targetKeys, enabled,
                                row?.name, row?.problem, row?.paperName, row?.notices.orEmpty(), row != null && !row.changed && row.problem == null) {
                                targetKeys = if (uri.toString() in targetKeys) targetKeys - uri.toString() else targetKeys + uri.toString()
                            }
                        }
                        if (vm.photos.size <= 1 && !presets) Text(stringResource(R.string.edit_no_targets), style = MaterialTheme.typography.bodyMedium)
                        if (vm.editTransferError != null) TextButton(onClick = { refresh++ }, enabled = enabled) { Text(stringResource(R.string.edit_refresh_preview)) }
                    }
                }
                (localError ?: catalog.error ?: if (page == "targets") vm.editTransferError else null)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Button(onClick = {
                when (page) {
                    "groups" -> try {
                        chooseTemplate(vm.captureEditTemplate(selectedGroups), vm.photoInfo.firstOrNull { it.first == "文件名" }?.second.orEmpty(), copy = true)
                    } catch (_: Exception) { localError = invalidTemplateMessage }
                    "presets" -> { page = "save"; groups = EditGroup.DEFAULT.map { it.key }; name = ""; namingId = null }
                    "save" -> try {
                        val template = vm.captureEditTemplate(selectedGroups)
                        savedAt = catalog.revision
                        catalog.save(name, template)
                    } catch (_: Exception) { localError = invalidTemplateMessage }
                    "rename" -> catalog.items.firstOrNull { it.id == namingId }?.let {
                        savedAt = catalog.revision; catalog.rename(it, name)
                    }
                    "targets" -> preview?.let(vm::applyEditTransfer)
                }
            }, enabled = enabled && when (page) {
                "groups" -> selectedGroups.isNotEmpty()
                "presets" -> catalog.ready
                "save" -> validName && selectedGroups.isNotEmpty()
                "rename" -> validName
                else -> !loading && preview?.changes?.isNotEmpty() == true
            }, shape = TheiaShapes.control, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                Text(when (page) {
                    "groups" -> stringResource(R.string.edit_continue_targets)
                    "presets" -> stringResource(R.string.edit_save_current)
                    "save", "rename" -> stringResource(R.string.edit_save)
                    else -> stringResource(R.string.edit_apply_count, preview?.changes?.size ?: 0)
                })
            }
        }
    }
    deleteId?.let { id -> catalog.items.firstOrNull { it.id == id }?.let { preset ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.edit_delete_title)) },
            text = { Text(stringResource(R.string.edit_delete_message, preset.name)) },
            confirmButton = { TextButton(onClick = { catalog.delete(id); deleteId = null }) { Text(stringResource(R.string.edit_delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.edit_cancel)) } })
    } }
}

@Composable
private fun groupName(group: EditGroup): String = stringResource(when (group) {
    EditGroup.MATERIAL -> R.string.edit_group_material
    EditGroup.DECODE -> R.string.edit_group_decode
    EditGroup.FILM -> R.string.edit_group_film
    EditGroup.PRINT -> R.string.edit_group_print
    EditGroup.LIGHT -> R.string.edit_group_light
    EditGroup.CURVES -> R.string.edit_group_curves
    EditGroup.COLOR -> R.string.edit_group_color
    EditGroup.VIGNETTE -> R.string.edit_group_vignette
    EditGroup.GEOMETRY -> R.string.edit_group_geometry
    EditGroup.FRAME -> R.string.edit_group_frame
})

@Composable
private fun GroupChoice(group: EditGroup, chosen: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val label = groupName(group)
    Row(Modifier.fillMaxWidth().clip(TheiaShapes.control)
        .background(if (chosen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
        .toggleable(chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
        .semantics(mergeDescendants = true) { contentDescription = label }
        .padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(when (group) {
            EditGroup.MATERIAL -> Icons.Rounded.LocalMovies
            EditGroup.DECODE -> Icons.Rounded.WbSunny
            EditGroup.FILM -> Icons.Rounded.Grain
            EditGroup.PRINT -> Icons.Rounded.Print
            EditGroup.LIGHT -> Icons.Rounded.Exposure
            EditGroup.CURVES -> Icons.Rounded.Timeline
            EditGroup.COLOR -> Icons.Rounded.Palette
            EditGroup.VIGNETTE -> Icons.Rounded.Vignette
            EditGroup.GEOMETRY -> Icons.Rounded.Crop
            EditGroup.FRAME -> Icons.Rounded.CropFree
        }, null, Modifier.size(20.dp), tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Checkbox(chosen, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun TargetChoice(vm: EditorViewModel, uri: Uri, chosen: Boolean, enabled: Boolean, name: String?, problem: String?, paperName: String?,
                         notices: Set<EditTransferNotice>, unchanged: Boolean, onToggle: () -> Unit) {
    val number = vm.photos.indexOf(uri) + 1
    val label = stringResource(R.string.edit_target_label, number)
    val bitmap = vm.developedPreviews[uri] ?: vm.thumbnails[uri]
    LaunchedEffect(uri) { vm.loadThumbnail(uri) }
    Row(Modifier.fillMaxWidth().clip(TheiaShapes.control)
        .background(if (chosen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
        .toggleable(chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
        .semantics(mergeDescendants = true) { contentDescription = label }
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).clip(TheiaShapes.compact).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name ?: uri.lastPathSegment ?: label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (uri == vm.currentUri) Text(stringResource(R.string.edit_current_photo), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (unchanged) Text(stringResource(R.string.edit_unchanged), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            notices.forEach { notice -> Text(if (notice == EditTransferNotice.PAPER_ADJUSTED)
                stringResource(R.string.edit_paper_adjusted, paperName.orEmpty()) else stringResource(when (notice) {
                EditTransferNotice.RAW_ONLY -> R.string.edit_raw_only
                EditTransferNotice.PAPER_ADJUSTED -> R.string.edit_paper_adjusted
                EditTransferNotice.COLOR_REPLACED -> R.string.edit_replace_color
                EditTransferNotice.CURVES_REPLACED -> R.string.edit_replace_curves
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Checkbox(chosen, onCheckedChange = null, enabled = enabled)
    }
}
