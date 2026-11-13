package com.spektralab.android.feature.lighttable

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import com.spektralab.android.R
import com.spektralab.android.core.model.*
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaShapes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TableOutputSheet(vm: EditorViewModel) {
    val controller = vm.tableOutput
    if (!controller.shown) return
    val active = vm.isExporting
    val options = controller.options
    val enabled = !active && !controller.preparing
    val plan = runCatching { controller.request?.plan() }.getOrNull()
    val exporting = rememberUpdatedState(active)
    // SheetState keys its saved state by this callback. Keep its identity stable as export starts/ends.
    val confirmSheetChange = remember { { value: SheetValue -> !exporting.value || value != SheetValue.Hidden } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmSheetChange)
    val bodyScroll = remember {
        object : NestedScrollConnection {
            // Scrolling settings to their boundary must not drag the entire sheet out of reach.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!active) controller.close() },
        sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).fillMaxHeight().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.table_output), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = controller::close, enabled = !active) { Icon(Icons.Rounded.Close, stringResource(R.string.table_output_close)) }
            }
            Text(stringResource(R.string.table_output_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(Modifier.weight(1f, fill = false).nestedScroll(bodyScroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().height(152.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, TheiaShapes.control), contentAlignment = Alignment.Center) {
                        controller.preview?.takeUnless { it.isRecycled }?.let {
                            Image(it.asImageBitmap(), stringResource(R.string.table_output_preview), Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                        }
                        if (controller.previewPending || controller.preparing) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    Text(stringResource(R.string.table_output_preview), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    OutputSection(stringResource(R.string.table_output_mode)) {
                        listOf(TableOutputMode.POSTER to R.string.table_output_poster, TableOutputMode.CONTACT to R.string.table_output_contact,
                            TableOutputMode.TABLE to R.string.table_output_table).forEach { (value, label) ->
                            FilterChip(options.mode == value, { controller.update(options.copy(mode = value)) }, enabled = enabled, label = { Text(stringResource(label)) })
                        }
                    }
                }
                item {
                    OutputSection(stringResource(R.string.table_output_range)) {
                        FilterChip(!options.viewport, { controller.update(options.copy(viewport = false)) }, enabled = enabled, label = { Text(stringResource(R.string.table_output_all)) })
                        FilterChip(options.viewport, { controller.update(options.copy(viewport = true)) }, enabled = enabled && controller.request?.viewport != null,
                            label = { Text(stringResource(R.string.table_output_viewport)) })
                    }
                }
                item {
                    OutputSection(stringResource(R.string.table_output_paper)) {
                        listOf(TablePaper.LAYOUT to R.string.table_output_layout, TablePaper.A4_PORTRAIT to R.string.table_output_portrait,
                            TablePaper.A4_LANDSCAPE to R.string.table_output_landscape, TablePaper.SQUARE to R.string.table_output_square).forEach { (value, label) ->
                            FilterChip(options.paper == value, { controller.update(options.copy(paper = value)) }, enabled = enabled, label = { Text(stringResource(label)) })
                        }
                    }
                }
                item {
                    OutputSection(stringResource(R.string.table_output_size)) {
                        listOf(1600, 2400).forEach { edge -> FilterChip(options.longEdge == edge, { controller.update(options.copy(longEdge = edge)) },
                            enabled = enabled, label = { Text("$edge px") }) }
                    }
                    plan?.let { Text(stringResource(R.string.table_output_pixels, it.width, it.height), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item {
                    OutputSection(stringResource(R.string.table_output_format)) {
                        FilterChip(options.format == TableOutputFormat.PNG, { controller.update(options.copy(format = TableOutputFormat.PNG)) }, enabled = enabled,
                            label = { Text(stringResource(R.string.table_output_png)) })
                        FilterChip(options.format == TableOutputFormat.JPEG, { controller.update(options.copy(format = TableOutputFormat.JPEG)) }, enabled = enabled && !options.transparent,
                            label = { Text(stringResource(R.string.table_output_jpeg)) })
                    }
                    OutputSwitch(stringResource(R.string.table_output_alpha), options.transparent, enabled && options.format == TableOutputFormat.PNG) {
                        controller.update(options.copy(transparent = it))
                    }
                    Text(stringResource(R.string.table_output_alpha_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (options.mode == TableOutputMode.POSTER) item {
                    OutlinedTextField(options.title, { controller.update(options.copy(title = it)) }, label = { Text(stringResource(R.string.table_output_title)) },
                        supportingText = { Text(stringResource(R.string.library_album_name_hint)) }, singleLine = true, enabled = enabled, shape = TheiaShapes.control,
                        modifier = Modifier.fillMaxWidth())
                    OutputSwitch(stringResource(R.string.table_output_date), options.date.isNotEmpty(), enabled) { value ->
                        controller.update(options.copy(date = if (value) LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) else ""))
                    }
                }
                item {
                    OutputSwitch(stringResource(R.string.table_output_numbers), options.numbered, enabled) { controller.update(options.copy(numbered = it)) }
                    if (options.mode == TableOutputMode.TABLE) OutputSwitch(stringResource(R.string.table_output_shadows), options.shadows, enabled) { controller.update(options.copy(shadows = it)) }
                    OutputSwitch(stringResource(R.string.table_output_latest), options.latestEdits, enabled) { controller.update(options.copy(latestEdits = it)) }
                    Text(stringResource(R.string.table_output_latest_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Text(stringResource(R.string.table_output_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (active) item {
                    Text(vm.renderStage, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { ((vm.renderProgress ?: 0f) / 100).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            controller.error?.let { message -> Text(message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            controller.result?.let { path -> Text(stringResource(if (path.startsWith("相册:")) R.string.table_output_success else R.string.table_output_saved_local),
                Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (active) vm.cancelTableProjectExport() else controller.close() }) { Text(stringResource(R.string.library_cancel)) }
                Button(onClick = { controller.submit(vm) }, enabled = enabled && plan != null && !controller.previewPending,
                    shape = TheiaShapes.control, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.FileDownload, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.table_output_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun OutputSwitch(title: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, enabled, Role.Switch, onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}
