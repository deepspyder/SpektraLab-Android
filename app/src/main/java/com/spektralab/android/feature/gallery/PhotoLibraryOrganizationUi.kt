package com.spektralab.android.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.AlbumNames
import com.spektralab.android.core.model.PhotoAlbum
import com.spektralab.android.core.model.PhotoLabel
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.ui.theme.TheiaShapes

internal fun PhotoLabel.textResource(): Int = when (this) {
    PhotoLabel.NONE -> R.string.library_label_none
    PhotoLabel.RED -> R.string.library_label_red
    PhotoLabel.YELLOW -> R.string.library_label_yellow
    PhotoLabel.GREEN -> R.string.library_label_green
    PhotoLabel.BLUE -> R.string.library_label_blue
    PhotoLabel.PURPLE -> R.string.library_label_purple
}

@Composable
private fun PhotoLabel.dotColor(): Color = when (this) {
    PhotoLabel.NONE -> MaterialTheme.colorScheme.outline
    PhotoLabel.RED -> Color(0xFFE99D94)
    PhotoLabel.YELLOW -> Color(0xFFE5C679)
    PhotoLabel.GREEN -> Color(0xFF9CBF9A)
    PhotoLabel.BLUE -> Color(0xFF9CB9D6)
    PhotoLabel.PURPLE -> Color(0xFFC3A6D0)
}

@Composable
internal fun PhotoMarks(rating: Int, label: PhotoLabel) {
    if (rating == 0 && label == PhotoLabel.NONE) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rating > 0) {
            Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.library_rating_value, rating), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (label != PhotoLabel.NONE) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(label.dotColor()))
            Text(stringResource(label.textResource()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryFilterBar(controller: SavedPhotosController, enabled: Boolean) {
    var albumMenu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    val activeAlbum = controller.organization.albums.firstOrNull { it.id == controller.filter.albumId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { albumMenu = true }, enabled = enabled, shape = TheiaShapes.control,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth()) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(activeAlbum?.name ?: stringResource(R.string.library_all_photos), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_all_photos)) }, onClick = {
                        albumMenu = false; controller.applyFilter(controller.filter.copy(albumId = null))
                    })
                    controller.organization.albums.forEach { album ->
                        DropdownMenuItem(text = { Text(album.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                            albumMenu = false; controller.applyFilter(controller.filter.copy(albumId = album.id))
                        }, trailingIcon = { Text(album.count.toString(), style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
            FilledTonalButton(onClick = { filters = true }, enabled = enabled, shape = TheiaShapes.control,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.FilterList, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (controller.filter.minimumRating != 0 || controller.filter.label != null || controller.filter.unratedOnly)
                    R.string.library_filters_active else R.string.library_filters), maxLines = 1)
            }
            IconButton(onClick = controller.organization::manageAlbums, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.FolderCopy, contentDescription = stringResource(R.string.library_album_manage))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library_result_count, controller.matchingCount, controller.totalCount),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (controller.filter.active) TextButton(onClick = { controller.applyFilter(PhotoLibraryFilter()) }, enabled = enabled) {
                Text(stringResource(R.string.library_filter_clear))
            }
        }
    }
    if (filters) LibraryFilterSheet(controller.filter, onDismiss = { filters = false }) {
        filters = false; controller.applyFilter(it)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterSheet(current: PhotoLibraryFilter, onDismiss: () -> Unit, onApply: (PhotoLibraryFilter) -> Unit) {
    var rating by remember { mutableIntStateOf(if (current.unratedOnly) -1 else current.minimumRating) }
    var label by remember { mutableStateOf(current.label) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.library_filter_title), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(stringResource(R.string.library_rating), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = rating == -1, onClick = { rating = -1 }, label = { Text(stringResource(R.string.library_unrated)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                        for (value in 0..5) FilterChip(selected = rating == value, onClick = { rating = value },
                            label = { Text(if (value == 0) stringResource(R.string.library_all_ratings) else stringResource(R.string.library_rating_minimum, value)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                item {
                    Text(stringResource(R.string.library_color_label), style = MaterialTheme.typography.titleMedium)
                    LabelChoices(label, true) { label = it }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
                Button(onClick = { onApply(current.copy(minimumRating = rating.coerceAtLeast(0), label = label, unratedOnly = rating == -1)) }, shape = TheiaShapes.control,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_filter_apply)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelChoices(selected: PhotoLabel?, allowAll: Boolean, enabled: Boolean = true, onSelect: (PhotoLabel?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allowAll) FilterChip(selected = selected == null, onClick = { onSelect(null) }, enabled = enabled,
            label = { Text(stringResource(R.string.library_all_labels)) }, modifier = Modifier.heightIn(min = 48.dp))
        PhotoLabel.entries.forEach { label ->
            FilterChip(selected = selected == label, onClick = { onSelect(label) }, enabled = enabled,
                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(label.dotColor())) },
                label = { Text(stringResource(label.textResource())) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PhotoOrganizationSheets(controller: PhotoOrganizationController) {
    val target = controller.target
    if (target != null) ModalBottomSheet(onDismissRequest = controller::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { !controller.pending || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_organize), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = controller::close, enabled = !controller.pending) { Icon(Icons.Rounded.Close, stringResource(R.string.library_organization_close)) }
            }
            Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Text(stringResource(R.string.library_rating), style = MaterialTheme.typography.titleMedium)
                    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (value in 1..5) IconToggleButton(checked = controller.draft.rating >= value,
                            onCheckedChange = { controller.rating(if (controller.draft.rating == value) 0 else value) }, enabled = !controller.pending,
                            modifier = Modifier.size(48.dp)) {
                            Icon(if (controller.draft.rating >= value) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                stringResource(R.string.library_rating_action, value), tint = if (controller.draft.rating >= value)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { controller.rating(0) }, enabled = !controller.pending) { Text(stringResource(R.string.library_unrated)) }
                    }
                }
                item {
                    Text(stringResource(R.string.library_color_label), style = MaterialTheme.typography.titleMedium)
                    LabelChoices(controller.draft.label, false, !controller.pending) { controller.label(it ?: PhotoLabel.NONE) }
                }
                item { Text(stringResource(R.string.library_albums), style = MaterialTheme.typography.titleMedium) }
                if (controller.albums.isEmpty()) item {
                    Text(stringResource(R.string.library_album_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(controller.albums, key = { it.id }) { album ->
                    val selected = album.id in controller.draft.albums
                    val membershipLabel = stringResource(R.string.library_album_membership, album.name)
                    Row(Modifier.fillMaxWidth().clip(TheiaShapes.control)
                        .clickable(enabled = !controller.pending, role = Role.Checkbox) { controller.album(album.id, !selected) }
                        .semantics { contentDescription = membershipLabel }
                        .heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = null)
                        Text(album.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                controller.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = controller::close, enabled = !controller.pending) { Text(stringResource(R.string.library_cancel)) }
                Button(onClick = controller::save, enabled = !controller.pending && controller.hasChanges, shape = TheiaShapes.control,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_save)) }
            }
        }
    }
    if (controller.albumManager) AlbumManagerSheet(controller)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumManagerSheet(controller: PhotoOrganizationController) {
    var naming by remember { mutableStateOf(false) }
    var renamed by remember { mutableStateOf<PhotoAlbum?>(null) }
    var deletion by remember { mutableStateOf<PhotoAlbum?>(null) }
    ModalBottomSheet(onDismissRequest = controller::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !controller.pending || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_album_manage), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = controller::close, enabled = !controller.pending) { Icon(Icons.Rounded.Close, stringResource(R.string.library_organization_close)) }
            }
            TextButton(onClick = { renamed = null; naming = true; controller.dismissError() }, enabled = !controller.pending,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.library_album_create))
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (controller.albums.isEmpty()) item { Text(stringResource(R.string.library_album_empty), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(controller.albums, key = { it.id }) { album ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(album.name, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.library_album_count, album.count), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { renamed = album; naming = true; controller.dismissError() }, enabled = !controller.pending) {
                            Icon(Icons.Rounded.Edit, stringResource(R.string.library_album_rename_action, album.name))
                        }
                        IconButton(onClick = { deletion = album; controller.dismissError() }, enabled = !controller.pending) {
                            Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.library_album_delete_action, album.name))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                controller.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (naming) AlbumNameDialog(renamed, controller, onDismiss = { naming = false; controller.dismissError() }) { name ->
        val album = renamed
        if (album == null) controller.createAlbum(name) { naming = false } else controller.renameAlbum(album.id, name) { naming = false }
    }
    deletion?.let { album -> AlertDialog(onDismissRequest = { if (!controller.pending) { deletion = null; controller.dismissError() } },
        title = { Text(stringResource(R.string.library_album_delete)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.library_album_delete_hint, album.name))
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        dismissButton = { TextButton(onClick = { deletion = null; controller.dismissError() }, enabled = !controller.pending) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { TextButton(onClick = { controller.deleteAlbum(album.id) { deletion = null } }, enabled = !controller.pending) { Text(stringResource(R.string.library_delete)) } }) }
}

@Composable
private fun AlbumNameDialog(album: PhotoAlbum?, controller: PhotoOrganizationController, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(album?.id) { mutableStateOf(album?.name.orEmpty()) }
    val valid = remember(name) { runCatching { AlbumNames.normalize(name) }.isSuccess }
    AlertDialog(onDismissRequest = { if (!controller.pending) onDismiss() },
        title = { Text(stringResource(if (album == null) R.string.library_album_create else R.string.library_album_rename)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it; controller.dismissError() }, enabled = !controller.pending, singleLine = true,
                label = { Text(stringResource(R.string.library_album_name)) }, supportingText = { Text(stringResource(R.string.library_album_name_hint)) },
                isError = name.isNotEmpty() && !valid, shape = TheiaShapes.control)
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !controller.pending) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = valid && !controller.pending) { Text(stringResource(R.string.library_save)) } })
}
