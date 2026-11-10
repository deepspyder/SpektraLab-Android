package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.editorOptionLabel
import com.spektralab.android.ui.components.ActionButton
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.components.OptionsDropdown
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.components.ToggleRow

// 胶片画幅 → 帧长边毫米数（引擎用它缩放颗粒/光晕微米量）。
private val FILM_FORMAT_MM = linkedMapOf(
    "135 (36mm)" to 36f,
    "645 (56mm)" to 56f,
    "6×6 (56mm)" to 56f,
    "6×7 (69.5mm)" to 69.5f,
    "6×8 (76mm)" to 76f,
    "6×9 (84mm)" to 84f,
    "6×12 (112mm)" to 112f,
    "6×17 (168mm)" to 168f,
)

@Composable
internal fun ShootPanel(vm: EditorViewModel) {
    EditorSection("画幅与配光", "胶片物理画幅 · 自动配光", initiallyExpanded = false) {
        OptionsDropdown(
            "胶片画幅",
            FILM_FORMAT_MM.keys.toList(),
            FILM_FORMAT_MM.entries.firstOrNull { it.value == vm.params.filmFormatMm }?.key ?: "135 (36mm)",
            Modifier.fillMaxWidth()
        ) { label ->
            FILM_FORMAT_MM[label]?.let { vm.params.filmFormatMm = it }
            vm.scheduleRender()
        }

        ActionButton("自动配光", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.solve() })
    }

    EditorSection("曝光与测光", "曝光补偿 · 自动测光 · 测光方式", initiallyExpanded = true) {
        SliderRow("曝光补偿", vm.params.exposureEv, -8f..8f, "%+.1f EV") { vm.params.exposureEv = it; vm.scheduleRender() }
        OptionsDropdown("测光方法", listOf("balanced", "center", "protect_highlights", "protect_shadows"), vm.params.autoExposureMethod, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
            vm.params.autoExposureMethod = it; vm.scheduleRender()
        }
        ToggleRow("自动测光", vm.params.autoExposure) { vm.params.autoExposure = it; vm.scheduleRender() }
    }

    EditorSection("场景宽容度", "测量场景 · 应用场景置位", initiallyExpanded = false) {
        ActionButton("测量宽容度", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.measureLatitude() })
        vm.latitudeReadout?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (vm.latitudeCanApply) {
            ActionButton("应用场景置位", onClick = { vm.applyLatitude() })
        }
    }

    if (vm.currentIsRaw) {
        EditorSection("相机白平衡 (RAW)", "相机色温 · 镜头校正") {
        ToggleRow("镜头校正", vm.lensCorrectionActive && vm.matchedLens != null) { vm.toggleLensCorrection() }
        vm.matchedLens?.let { Text("已匹配镜头: $it", style = MaterialTheme.typography.bodySmall) }
            ?: Text("未找到镜头配置，保留原始画面", style = MaterialTheme.typography.bodySmall)
        ToggleRow("自定义白平衡", vm.params.cameraWbCustom) { vm.params.cameraWbCustom = it; vm.scheduleCameraWb() }
        if (vm.params.cameraWbCustom) {
            SliderRow("色温 K", vm.params.cameraWbKelvin, 2000f..12000f, "%.0f K") { vm.params.cameraWbKelvin = it; vm.scheduleCameraWb() }
            SliderRow("色调", vm.params.cameraWbTint, -150f..150f, "%+.0f") { vm.params.cameraWbTint = it; vm.scheduleCameraWb() }
            ActionButton("恢复拍摄白平衡", onClick = { vm.resetCameraWb() })
        }
        }
    }

    EditorSection("镜头滤镜", "Wratten 彩色滤镜", initiallyExpanded = false) {
        OptionsDropdown("镜头滤镜", listOf("无", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58"), vm.params.cameraFilter.ifEmpty { "无" }, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
            vm.params.cameraFilter = if (it == "无") "" else it; vm.scheduleRender()
        }
    }
}
