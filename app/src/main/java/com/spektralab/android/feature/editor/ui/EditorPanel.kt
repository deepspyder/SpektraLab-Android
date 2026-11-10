package com.spektralab.android.feature.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.spektralab.android.EditorTab
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.panels.GradePanel
import com.spektralab.android.feature.editor.ui.panels.PrintPanel
import com.spektralab.android.feature.editor.ui.panels.ShootPanel
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun EditorPanel(
    vm: EditorViewModel,
    selectedTab: EditorTab,
    modifier: Modifier = Modifier,
    stateHolder: SaveableStateHolder,
    showHeader: Boolean = true,
    onSaveRecipe: () -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth().clip(TheiaShapes.panel), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        Column {
            if (showHeader) Row(
                Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedTab.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                if (vm.dirty) Text("已修改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
            stateHolder.SaveableStateProvider(selectedTab) {
              Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                EditorParameterContent(vm, selectedTab)
                Spacer(Modifier.height(12.dp))
              }
            }
        }
    }
}

@Composable
internal fun EditorParameterContent(vm: EditorViewModel, selectedTab: EditorTab) {
    when (selectedTab) {
        EditorTab.Shoot -> ShootPanel(vm)
        EditorTab.Print -> PrintPanel(vm)
        EditorTab.Grade -> GradePanel(vm)
        EditorTab.Export -> Unit
        EditorTab.Gallery -> Unit
    }
}

// ============================================================
//  四个 Tab 面板
// ============================================================
