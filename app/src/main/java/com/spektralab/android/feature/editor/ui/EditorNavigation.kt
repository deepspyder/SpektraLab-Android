package com.spektralab.android.feature.editor.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import com.spektralab.android.EditorTab
import com.spektralab.android.ui.components.DarkroomBottomBar

@Composable
internal fun EditorNavigationBar(selected: EditorTab, enabled: Boolean, onSelect: (EditorTab) -> Unit) {
    DarkroomBottomBar(EditorTab.entries.map { it.label }, EditorTab.entries.map { it.icon }, selected.ordinal, enabled) {
        onSelect(EditorTab.entries[it])
    }
}

@Composable
internal fun EditorNavigationRail(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        EditorTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
