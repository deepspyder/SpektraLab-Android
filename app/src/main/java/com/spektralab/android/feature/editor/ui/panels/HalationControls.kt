package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.core.model.HalationModel
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.components.ToggleRow

/** Film optics, kept separate from output grading and the print's glare. */
@Composable
internal fun HalationControls(vm: EditorViewModel) {
    val p = vm.params
    val editable = vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting && !vm.isApplyingEdits
    val nativeNoBacking = StockCatalog.hasNoAntihalation(p.filmId)
    val noBacking = nativeNoBacking || p.antihalationRemoved
    val physical = noBacking && p.halationModelVersion == HalationModel.BACK_REFLECTION.version
    val active = editable && p.halationActive

    ToggleRow(stringResource(R.string.halation_enabled), p.halationActive, enabled = editable) {
        p.halationActive = it; vm.scheduleRender()
    }
    SliderRow(stringResource(if (physical) R.string.halation_area else R.string.halation_amount),
        p.halationAmount, 0f..4f, if (physical) "%.1f×" else "%.1f", enabled = active) { p.halationAmount = it; vm.scheduleRender() }
    SliderRow(stringResource(R.string.halation_scatter), p.halationScatterAmount, 0f..1f, "%.2f", enabled = active) {
        p.halationScatterAmount = it; vm.scheduleRender()
    }
    SliderRow(stringResource(R.string.halation_boost), p.halationBoostEv, 0f..8f, "%.1f EV", enabled = active) {
        p.halationBoostEv = it; vm.scheduleRender()
    }
    ToggleRow(stringResource(R.string.halation_keep_backing), !noBacking, enabled = editable && !nativeNoBacking) {
        p.antihalationRemoved = !it; vm.scheduleRender()
    }
    if (nativeNoBacking) Text(stringResource(R.string.halation_no_backing),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (noBacking) {
        Text(stringResource(R.string.halation_model), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HalationModel.entries.forEach { model ->
                FilterChip(selected = p.halationModelVersion == model.version, enabled = active,
                    onClick = { p.halationModelVersion = model.version; vm.scheduleRender() },
                    label = { Text(stringResource(if (model == HalationModel.CLASSIC)
                        R.string.halation_classic else R.string.halation_back_reflection)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
        if (physical) {
            SliderRow(stringResource(R.string.halation_headroom), p.halationHeadroomEv, 0f..8f, "%.1f EV",
                enabled = active && p.halationAmount > 0f) { p.halationHeadroomEv = it; vm.scheduleRender() }
            Text(stringResource(R.string.halation_headroom_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        } else Text(stringResource(R.string.halation_classic_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }
}
