package com.spektralab.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object DarkroomChrome {
    val navigation = Color(0xFF222222)
    val selected = Color(0xFF3B301E)
}

@Composable
internal fun DarkroomTopBar(
    title: String,
    parameterView: Boolean,
    exportView: Boolean,
    enabled: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canCompare: Boolean,
    comparing: Boolean,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompare: () -> Unit,
    onReset: () -> Unit,
    onHelp: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_darkroom_arrow_left),
                    if (exportView) "返回暗房" else if (parameterView) "返回大图预览" else "返回首页",
                    modifier = Modifier.size(20.dp))
            }
            Box(Modifier.weight(1f)) {
                Text(title,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = enabled && !exportView, role = Role.Button, onClick = onMenu)
                        .semantics { if (!exportView) contentDescription = "更多操作" }
                        .wrapContentHeight(Alignment.CenterVertically),
                    fontSize = if (parameterView || exportView) 18.sp else 11.sp,
                    fontWeight = if (parameterView || exportView) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                menu()
            }
            when {
                exportView -> TextButton(onClick = onHelp, enabled = enabled) { Text("帮助", fontSize = 12.sp) }
                parameterView -> TextButton(onClick = onReset, enabled = enabled) { Text("重置", fontSize = 12.sp) }
                else -> {
                    IconButton(onClick = onUndo, enabled = enabled && canUndo, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_darkroom_undo_2), "撤销", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onRedo, enabled = enabled && canRedo, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_darkroom_redo_2), "重做", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onCompare, enabled = enabled && canCompare, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_darkroom_columns_2), if (comparing) "关闭前后对比" else "前后对比",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled && canCompare) 1f else .38f),
                            modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(40.dp))
                }
            }
        }
    }
}

@Composable
internal fun DarkroomBottomBar(
    labels: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val navigationHeight = 64.dp + 24.dp * (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), thickness = .5.dp)
        Row(Modifier.fillMaxWidth().height(navigationHeight).background(DarkroomChrome.navigation).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected = active, enabled = enabled, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(48.dp, 28.dp).background(
                        if (active) DarkroomChrome.selected else Color.Transparent, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(icons[index], null, tint = tint, modifier = Modifier.size(19.dp))
                    }
                    Text(label, color = tint, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun DarkroomChoiceStrip(options: List<String>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(DarkroomChrome.navigation, TheiaShapes.control).selectableGroup()) {
        options.forEach { option ->
            val active = option == selected
            TextButton(onClick = { onSelect(option) }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = active },
                shape = TheiaShapes.compact, contentPadding = PaddingValues(4.dp)) {
              Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, TheiaShapes.compact)
                .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(option, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
        }
    }
}
