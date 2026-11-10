package com.spektralab.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SmartDevelopEntry(vm: EditorViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors=MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(TheiaShapes.control).background(colors.surfaceContainerLow)
        .clickable(role=Role.Button,onClick=onOpen).padding(horizontal=12.dp,vertical=10.dp)
        .semantics(mergeDescendants=true){contentDescription="选择智能显影风格"},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(colors.primaryContainer,TheiaShapes.control),contentAlignment=Alignment.Center){
            Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(21.dp),tint=colors.primary)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("智能显影 · ${vm.smartStyle.title}",style=MaterialTheme.typography.titleSmall)
            Text(if(vm.smartImportRunning) "准备 ${vm.smartImportCompleted}/${vm.smartImportTotal} 张照片"
                else if(vm.smartAutoEnabled) "导入后逐张分析，自动选片与调色" else "自动处理已关闭，可手动应用",
                style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight,null,Modifier.size(20.dp),tint=colors.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartDevelopSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val colors=MaterialTheme.colorScheme
    val processing=vm.busy || vm.isExporting || vm.isBatchExporting
    ModalBottomSheet(onDismissRequest=onDismiss,shape=TheiaShapes.sheet,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start=20.dp,end=20.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.primaryContainer,TheiaShapes.control),contentAlignment=Alignment.Center){
                    Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(23.dp),tint=colors.primary)
                }
                Column(Modifier.weight(1f).padding(start=12.dp)) {
                    Text("智能显影",style=MaterialTheme.typography.titleLarge)
                    Text("三种风格，每张照片单独判断",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                }
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"关闭智能显影")}
            }
            Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                SmartDevelopStyle.entries.forEach { style ->
                    val selected=vm.smartStyle==style
                    Row(Modifier.fillMaxWidth().clip(TheiaShapes.control)
                        .background(if(selected)colors.surfaceContainerHigh else colors.surfaceContainerLow)
                        .selectable(selected=selected,enabled=!processing,role=Role.RadioButton,onClick={vm.smartStyle=style})
                        .semantics(mergeDescendants=true){contentDescription="显影风格：${style.title}"}
                        .padding(horizontal=12.dp,vertical=12.dp).heightIn(min=48.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        val icon=when(style){SmartDevelopStyle.Natural -> Icons.Rounded.WbSunny
                            SmartDevelopStyle.Cinema -> Icons.Rounded.Theaters;SmartDevelopStyle.Vintage -> Icons.Rounded.FilterVintage}
                        Icon(icon,null,Modifier.size(24.dp),tint=if(selected)colors.primary else colors.onSurfaceVariant)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(style.title,style=MaterialTheme.typography.titleSmall,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium)
                            Text(style.description,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                        }
                        RadioButton(selected=selected,onClick=null,enabled=!processing)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp)
                .toggleable(vm.smartAutoEnabled,enabled=!processing,role=Role.Switch,onValueChange={vm.smartAutoEnabled=it})
                .semantics(mergeDescendants=true){contentDescription="导入后自动显影"},verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end=12.dp)) {
                    Text("导入后自动显影",style=MaterialTheme.typography.bodyMedium)
                    Text("已有编辑照常恢复，不会被覆盖",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                }
                Switch(vm.smartAutoEnabled,onCheckedChange=null,enabled=!processing)
            }
            if(vm.smartImportRunning) {
                LinearProgressIndicator(progress={vm.smartImportCompleted.toFloat()/vm.smartImportTotal.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                Text("正在准备 ${vm.smartImportCompleted}/${vm.smartImportTotal} 张照片",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            if(vm.params.smartDevelopStyle.isNotEmpty() && vm.hasPhoto) {
                HorizontalDivider(color=colors.outlineVariant.copy(alpha=.55f))
                Text("当前照片 · ${SmartDevelopStyle.from(vm.params.smartDevelopStyle).title}",style=MaterialTheme.typography.labelLarge,color=colors.primary)
                Text(vm.params.smartDevelopSummary,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                val film=vm.films.firstOrNull{it.id==vm.params.filmId}?.name.orEmpty()
                val paper=vm.papers.firstOrNull{it.id==vm.params.paperId}?.name.orEmpty()
                Text("$film / $paper",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            if(vm.smartMessage.isNotEmpty())Text(vm.smartMessage,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            Text("根据亮暗、反差与色彩给出本地建议。选好风格后可继续微调，使用撤销即可恢复。",
                style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            if(vm.hasPhoto)Button(onClick={vm.applySmartDevelop();onDismiss()},enabled=!processing,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=TheiaShapes.control) {
                Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp));Text("应用到当前照片")
            }
            else Text("选择用于接下来导入的照片",style=MaterialTheme.typography.labelMedium,color=colors.primary)
        }
    }
}
