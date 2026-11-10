package com.spektralab.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.spektralab.android.engine.Stock

// ---- Tight, touch-safe editor controls ----

/** Disclosure changes composition only; it never dispatches an image operation. */
@Composable
fun EditorSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起$title" else "展开$title")
        }
        if (expanded) content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    }
}

@Composable
fun ChoiceStrip(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, TheiaShapes.control)) {
        options.forEach { option ->
            val isSelected = selected == option
            androidx.compose.material3.TextButton(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = isSelected },
                shape = TheiaShapes.control,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    containerColor = if (selected == option) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (selected == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(option, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    displayScale: Float = 1f,
    compact: Boolean = false,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(format.format(value * displayScale), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
        }
        val accent = MaterialTheme.colorScheme.primary
        val trackColor = MaterialTheme.colorScheme.outlineVariant
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled,
            thumb = { Box(Modifier.size(16.dp).background(accent, RoundedCornerShape(8.dp))) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                    val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    drawLine(trackColor, Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(accent, Offset(0f, center.y), Offset(size.width * fraction, center.y), 3.dp.toPx(), StrokeCap.Round)
                }
            },
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            contentDescription = label
            stateDescription = format.format(value * displayScale)
        })
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChecked), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun OptionsDropdown(
    label: String,
    options: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    optionLabel: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        SelectionRow(label, optionLabel(selected)) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                val isSelected = opt == selected
                DropdownMenuItem(
                    text = { Text(optionLabel(opt)) },
                    modifier = Modifier.semantics { this.selected = isSelected },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

@Composable
fun StockDropdown(
    label: String,
    stocks: List<Stock>,
    selectedId: String,
    modifier: Modifier = Modifier,
    groups: List<Pair<String, List<Stock>>> = emptyList(),
    onSelect: (Stock) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = stocks.find { it.id == selectedId }
    Box(modifier) {
        SelectionRow(label, selected?.name ?: "—") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (groups.isEmpty()) {
                stocks.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name) },
                        onClick = { onSelect(s); expanded = false }
                    )
                }
            } else {
                groups.forEach { (title, group) ->
                    DropdownMenuItem(
                        text = { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) },
                        enabled = false,
                        onClick = {}
                    )
                    group.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name) },
                            onClick = { onSelect(s); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActionButton(
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (primary) {
        Button(onClick = onClick, enabled = enabled, shape = TheiaShapes.control, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 48.dp)) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, shape = TheiaShapes.control,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 48.dp)) {
            Text(label)
        }
    }
}

@Composable
private fun SelectionRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .clickable(role = Role.Button, onClickLabel = "展开选项", onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = value }
        .padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
    }
}
