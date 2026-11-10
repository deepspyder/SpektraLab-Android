package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.components.SliderRow

@Composable
internal fun GradePanel(vm: EditorViewModel) {
    EditorSection("暗角", "柔和压暗边缘 · 不改变中心曝光", initiallyExpanded = true) {
        SliderRow("暗角强度", vm.params.vignetteAmount, 0f..100f, "%.0f%%") {
            vm.params.vignetteAmount = it; vm.scheduleGrade()
        }
        SliderRow("暗角范围", vm.params.vignetteSize, 0f..100f, "%.0f%%") {
            vm.params.vignetteSize = it; vm.scheduleGrade()
        }
        SliderRow("暗角羽化", vm.params.vignetteFeather, 0f..100f, "%.0f%%") {
            vm.params.vignetteFeather = it; vm.scheduleGrade()
        }
    }
    EditorSection("白平衡", "扫描白平衡 · 暖冷与色调", initiallyExpanded = true) {
        SliderRow("色温(暖/冷)", vm.params.temperature, -1f..1f, "%+.2f") { vm.params.temperature = it; vm.scheduleGrade() }
        SliderRow("色调(绿/品红)", vm.params.tint, -1f..1f, "%+.2f") { vm.params.tint = it; vm.scheduleGrade() }
    }

    EditorSection("影调", "曝光 · 对比度 · 高光 · 阴影 · 黑白点", initiallyExpanded = true) {
        SliderRow("曝光", vm.params.gradeExposure, -5f..5f, "%+.2f EV") { vm.params.gradeExposure = it; vm.scheduleGrade() }
        SliderRow("对比度", vm.params.contrast, -100f..100f, "%+.0f") { vm.params.contrast = it; vm.scheduleGrade() }
        SliderRow("亮度", vm.params.brightness, -100f..100f, "%+.0f") { vm.params.brightness = it; vm.scheduleGrade() }
        SliderRow("高光", vm.params.highlights, -100f..100f, "%+.0f") { vm.params.highlights = it; vm.scheduleGrade() }
        SliderRow("阴影", vm.params.shadows, -100f..100f, "%+.0f") { vm.params.shadows = it; vm.scheduleGrade() }
        SliderRow("饱和度", vm.params.saturation, -100f..100f, "%+.0f") { vm.params.saturation = it; vm.scheduleGrade() }
        SliderRow("黑点", vm.params.blackPoint, -100f..100f, "%+.0f") { vm.params.blackPoint = it; vm.scheduleGrade() }
        SliderRow("白点", vm.params.whitePoint, -100f..100f, "%+.0f") { vm.params.whitePoint = it; vm.scheduleGrade() }
    }

    EditorSection("色彩平衡", "阴影 · 中间调 · 高光", initiallyExpanded = false) {
        SliderRow("阴影 色温", vm.params.cbShadowTemp, -100f..100f, "%+.0f") { vm.params.cbShadowTemp = it; vm.scheduleGrade() }
        SliderRow("阴影 色调", vm.params.cbShadowTint, -100f..100f, "%+.0f") { vm.params.cbShadowTint = it; vm.scheduleGrade() }
        SliderRow("中间调 色温", vm.params.cbMidTemp, -100f..100f, "%+.0f") { vm.params.cbMidTemp = it; vm.scheduleGrade() }
        SliderRow("中间调 色调", vm.params.cbMidTint, -100f..100f, "%+.0f") { vm.params.cbMidTint = it; vm.scheduleGrade() }
        SliderRow("高光 色温", vm.params.cbHighTemp, -100f..100f, "%+.0f") { vm.params.cbHighTemp = it; vm.scheduleGrade() }
        SliderRow("高光 色调", vm.params.cbHighTint, -100f..100f, "%+.0f") { vm.params.cbHighTint = it; vm.scheduleGrade() }
    }

    EditorSection("几何与裁剪", "旋转 · 翻转 · 拉直 · 自由裁剪", initiallyExpanded = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { vm.params.quarterTurns = (vm.params.quarterTurns + 3) % 4; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.RotateLeft, contentDescription = "左转90度")
            }
            IconButton(onClick = { vm.params.quarterTurns = (vm.params.quarterTurns + 1) % 4; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.RotateRight, contentDescription = "右转90度")
            }
            IconToggleButton(checked = vm.params.flipH, onCheckedChange = { vm.params.flipH = it; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "水平翻转")
            }
            IconToggleButton(checked = vm.params.flipV, onCheckedChange = { vm.params.flipV = it; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SwapVert, contentDescription = "垂直翻转")
            }
        }
        SliderRow("旋转/拉直", vm.params.rotationDeg, -45f..45f, "%+.1f°") { vm.params.rotationDeg = it; vm.scheduleRender() }
        OutlinedButton(onClick = vm::beginCrop, enabled = vm.hasPhoto && !vm.busy && !vm.params.overscanActive,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("自由裁剪")
        }
    }
}
