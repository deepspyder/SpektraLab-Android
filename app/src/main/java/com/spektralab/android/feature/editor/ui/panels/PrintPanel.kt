package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.editorOptionLabel
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.components.OptionsDropdown
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.components.ToggleRow
import kotlin.math.roundToInt

@Composable
internal fun PrintPanel(vm: EditorViewModel) {
    EditorSection("放大机", "打印曝光 · Y / M 滤色", initiallyExpanded = true) {
        SliderRow("打印曝光(越小越亮)", vm.params.printExposure, 0.25f..4f, "%.2f") { vm.params.printExposure = it; vm.scheduleRender() }
        SliderRow("滤镜 Y(黄/蓝)", vm.params.yFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f) { vm.params.yFilterShift = it; vm.scheduleRender() }
        SliderRow("滤镜 M(品红/绿)", vm.params.mFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f) { vm.params.mFilterShift = it; vm.scheduleRender() }
    }

    EditorSection("乳剂与暗房效应", "颗粒 · 光晕 · DIR · 纸面光晕 · 预闪 · EDR", initiallyExpanded = false) {
        ToggleRow("颗粒", vm.params.grainActive) { vm.params.grainActive = it; vm.scheduleRender() }
        SliderRow("颗粒强度", vm.params.grainAmount, 0f..2f, "%.2f×") { vm.params.grainAmount = it; vm.scheduleRender() }
        ToggleRow("分层颗粒", vm.params.grainLayered) { vm.params.grainLayered = it; vm.scheduleRender() }
        ToggleRow("光晕", vm.params.halationActive) { vm.params.halationActive = it; vm.scheduleRender() }
        SliderRow("光晕强度", vm.params.halationAmount, 0f..4f, "%.1f") { vm.params.halationAmount = it; vm.scheduleRender() }
        SliderRow("光晕散射", vm.params.halationScatterAmount, 0f..1f, "%.2f") { vm.params.halationScatterAmount = it; vm.scheduleRender() }
        SliderRow("高光增强", vm.params.halationBoostEv, 0f..8f, "%.1f EV") { vm.params.halationBoostEv = it; vm.scheduleRender() }
        ToggleRow("保留抗晕层", !vm.params.antihalationRemoved) { vm.params.antihalationRemoved = !it; vm.scheduleRender() }
        ToggleRow("DIR 成色剂耦合", vm.params.dirCouplersActive) { vm.params.dirCouplersActive = it; vm.scheduleRender() }
        SliderRow("DIR 强度", vm.params.dirCouplersAmount, 0f..1.5f, "%.1f") { vm.params.dirCouplersAmount = it; vm.scheduleRender() }
        ToggleRow("纸面光晕", vm.params.glareActive) { vm.params.glareActive = it; vm.scheduleRender() }
        SliderRow("纸面光晕强度", vm.params.glareAmount, 0f..30f, "%.1f") { vm.params.glareAmount = it; vm.scheduleRender() }
        SliderRow("预闪", vm.params.preflashExposure, 0f..0.03f, "%.2f×") { vm.params.preflashExposure = it; vm.scheduleRender() }
        ToggleRow("扩展动态范围", vm.params.extendedDynamicRange) { vm.params.extendedDynamicRange = it; vm.scheduleRender() }
    }

    EditorSection("片边", "胶片格式 · 齿孔 · 雾化 · 漏光 · 帧号", initiallyExpanded = false) {
        ToggleRow("片边(整条胶片)", vm.params.overscanActive) { vm.params.overscanActive = it; vm.scheduleRender() }
        if (vm.params.overscanActive) {
            OptionsDropdown("画幅", listOf("135", "135_half", "135_xpan", "120_645", "120_6x6", "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"), vm.params.overscanFormat, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
                vm.params.overscanFormat = it; vm.scheduleRender()
            }
            OptionsDropdown("片边模式", listOf("strip", "filed"), vm.params.overscanMode, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanMode = it; vm.scheduleRender() }
            OptionsDropdown("门形状", listOf("auto", "square", "rounded", "eared", "shouldered", "kicked"), vm.params.overscanGate, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanGate = it; vm.scheduleRender() }
            OptionsDropdown("齿孔", listOf("white", "black"), vm.params.overscanHoles, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanHoles = it; vm.scheduleRender() }
            OptionsDropdown("边缘外", listOf("none", "black", "open"), vm.params.overscanCarrier, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanCarrier = it; vm.scheduleRender() }
            SliderRow("边缘雾化", vm.params.overscanFog, 0f..4f, "%.1f") { vm.params.overscanFog = it; vm.scheduleRender() }
            SliderRow("漏光", vm.params.overscanLeaks, 0f..4f, "%.1f") { vm.params.overscanLeaks = it; vm.scheduleRender() }
            SliderRow("光圈(门边光晕)", vm.params.overscanFNumber, 0f..64f, "%.1f") { vm.params.overscanFNumber = it; vm.scheduleRender() }
            SliderRow("帧号", vm.params.overscanFrameNumber.toFloat(), 0f..99f, "%.0f") { vm.params.overscanFrameNumber = it.roundToInt(); vm.scheduleRender() }
            Text(
                "片边文字（自动）: ${StockCatalog.edgeText(vm.params.filmId, vm.params.overscanFormat)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            SliderRow("相机种子", vm.params.overscanCameraSeed.toFloat(), 0f..999f, "%.0f") { vm.params.overscanCameraSeed = it.roundToInt(); vm.scheduleRender() }
            SliderRow("过片种子", vm.params.overscanFrameSeed.toFloat(), 0f..999f, "%.0f") { vm.params.overscanFrameSeed = it.roundToInt(); vm.scheduleRender() }
        }
    }

    EditorSection("日期后背", "日期文字 · 拍摄数据 · 位置 · 曝光", initiallyExpanded = false) {
        ToggleRow("日期后背", vm.params.dateImprintActive) {
            vm.params.dateImprintActive = it
            if (it && vm.params.dateImprintText.isEmpty()) vm.params.dateImprintText = vm.currentDateText()
            vm.scheduleRender()
        }
        if (vm.params.dateImprintActive) {
            OptionsDropdown("日期样式", listOf("lcd", "dots", "data"), vm.params.dateImprintStyle, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
                vm.params.dateImprintStyle = it
                if (it == "data") vm.params.overscanActive = true
                vm.scheduleRender()
            }
            OptionsDropdown("位置", listOf("frame", "rebate"), vm.params.dateImprintPlacement, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.dateImprintPlacement = it; vm.scheduleRender() }
            OptionsDropdown("角落", listOf("br", "bl", "tr", "tl"), vm.params.dateImprintCorner, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.dateImprintCorner = it; vm.scheduleRender() }
            SliderRow("大小", vm.params.dateImprintSize, 0.4f..3f, "%.2f") { vm.params.dateImprintSize = it; vm.scheduleRender() }
            SliderRow("曝光", vm.params.dateImprintEv, -2f..8f, "%+.1f") { vm.params.dateImprintEv = it; vm.scheduleRender() }
            OutlinedTextField(value = vm.params.dateImprintText, onValueChange = { vm.params.dateImprintText = it; vm.scheduleRender() },
                label = { Text("日期文字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = vm.params.dateImprintDataText, onValueChange = { vm.params.dateImprintDataText = it; vm.scheduleRender() },
                label = { Text("拍摄数据（打印在片边）") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
