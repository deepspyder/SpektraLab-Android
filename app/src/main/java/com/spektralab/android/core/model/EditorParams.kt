package com.spektralab.android.core.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.core.geometry.computeCenteredCropByAspect
import com.spektralab.android.core.geometry.overscanGateAspect
import com.spektralab.android.engine.StockCatalog
import org.json.JSONObject

/**
 * 编辑器参数 = 引擎 wire 字段的集合（带 Compose 状态）。
 * 字段名均来自 engine/src/core/params.cpp 的 kFields。
 */
class EditorParams {
    // 胶片 & 相纸
    var filmId by mutableStateOf("kodak_portra_400")
    var paperId by mutableStateOf("kodak_portra_endura")
    var smartDevelopStyle by mutableStateOf("")
    var smartDevelopSummary by mutableStateOf("")
    var smartDevelopVersion by mutableStateOf(0)

    // 相机曝光（SHOOT，重建负片）
    var exposureEv by mutableFloatStateOf(0f)
    // 自动测光方法（SHOOT）。引擎默认 auto_exposure=true，会按此方法自动测光；
    // exposureEv 是叠加其上的手动补偿。合法值：balanced/center/protect_highlights/protect_shadows。
    var autoExposureMethod by mutableStateOf("balanced")
    var autoExposure by mutableStateOf(true)
    // 胶片画幅（SHOOT）：帧长边毫米数，引擎用它缩放颗粒/光晕的微米量。默认 135 的 36mm。
    var filmFormatMm by mutableFloatStateOf(36f)
    // 相机白平衡（解码侧，仅 RAW，不进引擎 delta）。Kelvin 色温 + 色调（Adobe DNG 定义）。
    // cameraWbCustom=false 用 as-shot；true 时按 Kelvin/tint 重新解码。
    var cameraWbKelvin by mutableFloatStateOf(5500f)
    var cameraWbTint by mutableFloatStateOf(0f)
    var cameraWbCustom by mutableStateOf(false)

    // 场景宽容度 / 置位（RFC-023，SHOOT）。默认值对齐 engine params.hpp 的 SceneLatitudeParams：
    // 两侧 room=0 表示该侧关闭。由「测量宽容度 → 应用」填入求解结果。
    var sceneLatitudeActive by mutableStateOf(false)
    var sceneLatitudeNorm by mutableStateOf("power")   // power | y | max
    var sceneLatitudeHighlightKnee by mutableFloatStateOf(2f)
    var sceneLatitudeHighlightRoom by mutableFloatStateOf(0f)
    var sceneLatitudeShadowKnee by mutableFloatStateOf(-2f)
    var sceneLatitudeShadowRoom by mutableFloatStateOf(0f)
    var sceneLatitudeRolloff by mutableFloatStateOf(2f)
    var sceneLatitudeMaxLift by mutableFloatStateOf(4f)

    // 放大机（PRINT，live 字段，reprint 复用负片）
    var printExposure by mutableFloatStateOf(1f)   // 越小越亮（暗房约定）
    var mFilterShift by mutableFloatStateOf(0f)    // 品红/绿（用户相对中性值的偏移）
    var yFilterShift by mutableFloatStateOf(0f)    // 黄/蓝（用户相对中性值的偏移）
    // 注：中性滤色包（m/y/c_filter_neutral）不在这里——引擎默认
    // neutral_print_filters_from_database=true，open 时自动从数据库解析，前端不持有。

    // 胶片效果（SHOOT）。默认与引擎一致：颗粒/光晕/DIR 都开（这才是完整胶片观感）。
    var grainActive by mutableStateOf(true)
    var grainAmount by mutableFloatStateOf(1f)
    var grainLayered by mutableStateOf(true)
    var halationActive by mutableStateOf(true)
    var halationAmount by mutableFloatStateOf(1f)
    var halationScatterAmount by mutableFloatStateOf(1f)
    var halationBoostEv by mutableFloatStateOf(0f)
    var antihalationRemoved by mutableStateOf(false)
    var dirCouplersActive by mutableStateOf(true)
    var dirCouplersAmount by mutableFloatStateOf(1f)
    var glareActive by mutableStateOf(true)
    var glareAmount by mutableFloatStateOf(1f)
    var preflashExposure by mutableFloatStateOf(0f)
    var extendedDynamicRange by mutableStateOf(false)

    // 几何（SHOOT）。裁剪按源图归一化（左上原点，0..1），在旋转/翻转之前应用。
    var quarterTurns by mutableStateOf(0)          // 0..3，90° 顺时针
    var flipH by mutableStateOf(false)
    var flipV by mutableStateOf(false)
    var rotationDeg by mutableFloatStateOf(0f)     // -45..45
    var cropX by mutableFloatStateOf(0f)
    var cropY by mutableFloatStateOf(0f)
    var cropW by mutableFloatStateOf(1f)
    var cropH by mutableFloatStateOf(1f)

    // Layer 2 白平衡（应用侧后处理，不进引擎 delta；只影响显示/导出，并持久化）。
    var temperature by mutableFloatStateOf(0f)   // -1 冷(蓝) .. +1 暖(橙)
    var tint by mutableFloatStateOf(0f)          // -1 品红 .. +1 绿

    // Layer 2 调色（应用侧，编码空间，与桌面 adjustments.ts 一致）。-100..100 表示百分比。
    var gradeExposure by mutableFloatStateOf(0f)   // EV
    var contrast by mutableFloatStateOf(0f)
    var brightness by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(0f)
    var highlights by mutableFloatStateOf(0f)
    var shadows by mutableFloatStateOf(0f)
    var blackPoint by mutableFloatStateOf(0f)
    var whitePoint by mutableFloatStateOf(0f)
    var vignetteAmount by mutableFloatStateOf(0f) // 0..100, zero preserves existing output
    var vignetteSize by mutableFloatStateOf(50f)
    var vignetteFeather by mutableFloatStateOf(70f)
    // 三区色彩平衡：shadow/mid/high 各 temp（暖↔冷）+ tint（绿↔品红）。
    var cbShadowTemp by mutableFloatStateOf(0f)
    var cbShadowTint by mutableFloatStateOf(0f)
    var cbMidTemp by mutableFloatStateOf(0f)
    var cbMidTint by mutableFloatStateOf(0f)
    var cbHighTemp by mutableFloatStateOf(0f)
    var cbHighTint by mutableFloatStateOf(0f)

    // M5 高级功能（SHOOT）。
    var cameraFilter by mutableStateOf("")              // Wratten 镜头滤镜，"" = 无
    var overscanActive by mutableStateOf(false)         // 片边
    var overscanFormat by mutableStateOf("135")
    var overscanMode by mutableStateOf("strip")         // strip / filed
    var overscanGate by mutableStateOf("auto")          // auto / square / rounded / eared / shouldered / kicked
    var overscanHoles by mutableStateOf("white")        // white / black
    var overscanCarrier by mutableStateOf("none")       // none / black / open
    var overscanEdgeText by mutableStateOf("KODAK PORTRA 400")
    var overscanFrameNumber by mutableStateOf(0)        // 0 = 由帧种子决定
    var overscanCameraSeed by mutableStateOf(1)         // 机身（门形状/毛刺/雾化）
    var overscanFrameSeed by mutableStateOf(1)          // 过片与扫描（漏光/编号）
    var overscanFog by mutableFloatStateOf(1f)          // 0..4 边缘雾化
    var overscanLeaks by mutableFloatStateOf(0f)        // 0..4 漏光
    var overscanFNumber by mutableFloatStateOf(0f)      // 0=未知（按 f/5.6），门边光晕
    var dateImprintActive by mutableStateOf(false)      // 日期后背
    var dateImprintText by mutableStateOf("")           // 空 = 自动当前日期
    var dateImprintStyle by mutableStateOf("lcd")       // lcd / dots / data
    var dateImprintPlacement by mutableStateOf("frame") // frame / rebate
    var dateImprintCorner by mutableStateOf("br")       // br / bl / tr / tl
    var dateImprintSize by mutableFloatStateOf(1f)      // 0.4..3.0
    var dateImprintEv by mutableFloatStateOf(3.5f)      // -2..8
    var dateImprintInsetX by mutableFloatStateOf(3f)    // mm
    var dateImprintInsetY by mutableFloatStateOf(2.4f)  // mm
    var dateImprintDataText by mutableStateOf("")       // 拍摄数据（style=data 时一并打印）

    /** 打开会话的完整参数。[outputColorSpace] 默认 sRGB，导出时可指定 Display P3；
     *  [inputColorSpace] 默认 sRGB，RAW 解码后用 "ProPhoto RGB"。 */
    fun openJson(srcW: Int, srcH: Int, outputColorSpace: String = "sRGB", inputColorSpace: String = "sRGB"): String = baseJson(srcW, srcH)
        .put("input_color_space", inputColorSpace)
        .put("output_color_space", outputColorSpace)
        .toString()

    /** 编辑时的参数 delta。 */
    fun deltaJson(srcW: Int, srcH: Int): String = baseJson(srcW, srcH).toString()

    /** 序列化为 sidecar JSON（所有可编辑字段）。 */
    fun toJson(): JSONObject = JSONObject()
        .put("film_stock", filmId)
        .put("print_stock", paperId)
        .put("smart_develop_style", smartDevelopStyle)
        .put("smart_develop_summary", smartDevelopSummary)
        .put("smart_develop_version", smartDevelopVersion)
        .put("exposure_compensation_ev", exposureEv.toDouble())
        .put("auto_exposure_method", autoExposureMethod)
        .put("auto_exposure", autoExposure)
        .put("film_format_mm", filmFormatMm.toDouble())
        .put("camera_wb_kelvin", cameraWbKelvin.toDouble())
        .put("camera_wb_tint", cameraWbTint.toDouble())
        .put("camera_wb_custom", cameraWbCustom)
        .put("scene_latitude_active", sceneLatitudeActive)
        .put("scene_latitude_norm", sceneLatitudeNorm)
        .put("scene_latitude_highlight_knee", sceneLatitudeHighlightKnee.toDouble())
        .put("scene_latitude_highlight_room", sceneLatitudeHighlightRoom.toDouble())
        .put("scene_latitude_shadow_knee", sceneLatitudeShadowKnee.toDouble())
        .put("scene_latitude_shadow_room", sceneLatitudeShadowRoom.toDouble())
        .put("scene_latitude_rolloff", sceneLatitudeRolloff.toDouble())
        .put("scene_latitude_max_lift", sceneLatitudeMaxLift.toDouble())
        .put("print_exposure", printExposure.toDouble())
        .put("m_filter_shift", mFilterShift.toDouble())
        .put("y_filter_shift", yFilterShift.toDouble())
        .put("grain_active", grainActive)
        .put("grain_amount", grainAmount.toDouble())
        .put("grain_layered", grainLayered)
        .put("halation_active", halationActive)
        .put("halation_amount", halationAmount.toDouble())
        .put("halation_scatter_amount", halationScatterAmount.toDouble())
        .put("halation_boost_ev", halationBoostEv.toDouble())
        .put("antihalation_removed", antihalationRemoved)
        .put("dir_couplers_active", dirCouplersActive)
        .put("dir_couplers_amount", dirCouplersAmount.toDouble())
        .put("glare_active", glareActive)
        .put("glare_amount", glareAmount.toDouble())
        .put("preflash_exposure", preflashExposure.toDouble())
        .put("extended_dynamic_range", extendedDynamicRange)
        .put("quarter_turns", quarterTurns)
        .put("flip_h", flipH)
        .put("flip_v", flipV)
        .put("rotation_deg", rotationDeg.toDouble())
        .put("crop_x", cropX.toDouble())
        .put("crop_y", cropY.toDouble())
        .put("crop_w", cropW.toDouble())
        .put("crop_h", cropH.toDouble())
        .put("temperature", temperature.toDouble())
        .put("tint", tint.toDouble())
        .put("grade_exposure", gradeExposure.toDouble())
        .put("contrast", contrast.toDouble())
        .put("brightness", brightness.toDouble())
        .put("saturation", saturation.toDouble())
        .put("highlights", highlights.toDouble())
        .put("shadows", shadows.toDouble())
        .put("black_point", blackPoint.toDouble())
        .put("white_point", whitePoint.toDouble())
        .put("vignette_amount", vignetteAmount.toDouble())
        .put("vignette_size", vignetteSize.toDouble())
        .put("vignette_feather", vignetteFeather.toDouble())
        .put("cb_shadow_temp", cbShadowTemp.toDouble())
        .put("cb_shadow_tint", cbShadowTint.toDouble())
        .put("cb_mid_temp", cbMidTemp.toDouble())
        .put("cb_mid_tint", cbMidTint.toDouble())
        .put("cb_high_temp", cbHighTemp.toDouble())
        .put("cb_high_tint", cbHighTint.toDouble())
        .put("camera_filter", cameraFilter)
        .put("overscan_active", overscanActive)
        .put("overscan_format", overscanFormat)
        .put("overscan_mode", overscanMode)
        .put("overscan_gate", overscanGate)
        .put("overscan_holes", overscanHoles)
        .put("overscan_carrier", overscanCarrier)
        .put("overscan_edge_text", StockCatalog.edgeText(filmId, overscanFormat))
        .put("overscan_frame_number", overscanFrameNumber)
        .put("overscan_camera_seed", overscanCameraSeed)
        .put("overscan_frame_seed", overscanFrameSeed)
        .put("overscan_fog", overscanFog.toDouble())
        .put("overscan_leaks", overscanLeaks.toDouble())
        .put("overscan_f_number", overscanFNumber.toDouble())
        .put("date_imprint_active", dateImprintActive)
        .put("date_imprint_text", dateImprintText)
        .put("date_imprint_style", dateImprintStyle)
        .put("date_imprint_placement", dateImprintPlacement)
        .put("date_imprint_corner", dateImprintCorner)
        .put("date_imprint_size", dateImprintSize.toDouble())
        .put("date_imprint_ev", dateImprintEv.toDouble())
        .put("date_imprint_inset_x", dateImprintInsetX.toDouble())
        .put("date_imprint_inset_y", dateImprintInsetY.toDouble())
        .put("date_imprint_data_text", dateImprintDataText)

    fun copy(): EditorParams = fromJson(toJson())

    /** Copy editable capture/print/grade settings while keeping each photo's own geometry. */
    fun copySettingsFrom(source: EditorParams) {
        val copied = source.copy()
        copied.cropX = cropX; copied.cropY = cropY
        copied.cropW = cropW; copied.cropH = cropH
        copied.quarterTurns = quarterTurns; copied.flipH = flipH; copied.flipV = flipV; copied.rotationDeg = rotationDeg
        copyAllFrom(copied)
    }

    private fun copyAllFrom(p: EditorParams) {
        filmId = p.filmId; paperId = p.paperId; exposureEv = p.exposureEv
        smartDevelopStyle=p.smartDevelopStyle;smartDevelopSummary=p.smartDevelopSummary;smartDevelopVersion=p.smartDevelopVersion
        autoExposureMethod = p.autoExposureMethod; autoExposure = p.autoExposure; filmFormatMm = p.filmFormatMm
        cameraWbKelvin = p.cameraWbKelvin; cameraWbTint = p.cameraWbTint; cameraWbCustom = p.cameraWbCustom
        sceneLatitudeActive = p.sceneLatitudeActive; sceneLatitudeNorm = p.sceneLatitudeNorm
        sceneLatitudeHighlightKnee = p.sceneLatitudeHighlightKnee; sceneLatitudeHighlightRoom = p.sceneLatitudeHighlightRoom
        sceneLatitudeShadowKnee = p.sceneLatitudeShadowKnee; sceneLatitudeShadowRoom = p.sceneLatitudeShadowRoom
        sceneLatitudeRolloff = p.sceneLatitudeRolloff; sceneLatitudeMaxLift = p.sceneLatitudeMaxLift
        printExposure = p.printExposure; mFilterShift = p.mFilterShift; yFilterShift = p.yFilterShift
        grainActive = p.grainActive; grainAmount = p.grainAmount; grainLayered = p.grainLayered
        halationActive = p.halationActive; halationAmount = p.halationAmount; halationScatterAmount = p.halationScatterAmount
        halationBoostEv = p.halationBoostEv; antihalationRemoved = p.antihalationRemoved
        dirCouplersActive = p.dirCouplersActive; dirCouplersAmount = p.dirCouplersAmount
        glareActive = p.glareActive; glareAmount = p.glareAmount; preflashExposure = p.preflashExposure
        extendedDynamicRange = p.extendedDynamicRange
        quarterTurns = p.quarterTurns; flipH = p.flipH; flipV = p.flipV; rotationDeg = p.rotationDeg
        cropX = p.cropX; cropY = p.cropY; cropW = p.cropW; cropH = p.cropH
        temperature = p.temperature; tint = p.tint; gradeExposure = p.gradeExposure
        contrast = p.contrast; brightness = p.brightness; saturation = p.saturation
        highlights = p.highlights; shadows = p.shadows; blackPoint = p.blackPoint; whitePoint = p.whitePoint
        vignetteAmount = p.vignetteAmount; vignetteSize = p.vignetteSize; vignetteFeather = p.vignetteFeather
        cbShadowTemp = p.cbShadowTemp; cbShadowTint = p.cbShadowTint; cbMidTemp = p.cbMidTemp; cbMidTint = p.cbMidTint
        cbHighTemp = p.cbHighTemp; cbHighTint = p.cbHighTint; cameraFilter = p.cameraFilter
        overscanActive = p.overscanActive; overscanFormat = p.overscanFormat; overscanMode = p.overscanMode
        overscanGate = p.overscanGate; overscanHoles = p.overscanHoles; overscanCarrier = p.overscanCarrier
        overscanEdgeText = p.overscanEdgeText; overscanFrameNumber = p.overscanFrameNumber
        overscanCameraSeed = p.overscanCameraSeed; overscanFrameSeed = p.overscanFrameSeed
        overscanFog = p.overscanFog; overscanLeaks = p.overscanLeaks; overscanFNumber = p.overscanFNumber
        dateImprintActive = p.dateImprintActive; dateImprintText = p.dateImprintText; dateImprintStyle = p.dateImprintStyle
        dateImprintPlacement = p.dateImprintPlacement; dateImprintCorner = p.dateImprintCorner
        dateImprintSize = p.dateImprintSize; dateImprintEv = p.dateImprintEv
        dateImprintInsetX = p.dateImprintInsetX; dateImprintInsetY = p.dateImprintInsetY
        dateImprintDataText = p.dateImprintDataText
    }

    companion object {
        /** Same per-film RMS calibration as Windows desktop/shared/params.ts. */
        fun ownGrain(stock: String): Float = when (stock) {
            "kodak_tri_x_400" -> 1.70f
            "ilford_hp5_plus_400" -> 1.49f
            "kodak_tmax_100" -> 0.74f
            "fujifilm_neopan_acros_100_ii" -> 0.64f
            else -> 1f
        }
        /** 从 sidecar JSON 恢复参数（缺字段用默认值）。 */
        fun fromJson(json: JSONObject): EditorParams {
            val p = EditorParams()
            p.filmId = json.optString("film_stock", p.filmId)
            p.paperId = json.optString("print_stock", p.paperId)
            p.smartDevelopStyle=json.optString("smart_develop_style", "")
            p.smartDevelopSummary=json.optString("smart_develop_summary", "")
            p.smartDevelopVersion=json.optInt("smart_develop_version", 0)
            p.exposureEv = json.optDouble("exposure_compensation_ev", 0.0).toFloat()
            p.autoExposureMethod = json.optString("auto_exposure_method", "balanced")
            p.autoExposure = json.optBoolean("auto_exposure", true)
            p.filmFormatMm = json.optDouble("film_format_mm", 36.0).toFloat()
            p.cameraWbKelvin = json.optDouble("camera_wb_kelvin", 5500.0).toFloat()
            p.cameraWbTint = json.optDouble("camera_wb_tint", 0.0).toFloat()
            p.cameraWbCustom = json.optBoolean("camera_wb_custom", false)
            p.sceneLatitudeActive = json.optBoolean("scene_latitude_active", false)
            p.sceneLatitudeNorm = json.optString("scene_latitude_norm", "power")
            p.sceneLatitudeHighlightKnee = json.optDouble("scene_latitude_highlight_knee", 2.0).toFloat()
            p.sceneLatitudeHighlightRoom = json.optDouble("scene_latitude_highlight_room", 0.0).toFloat()
            p.sceneLatitudeShadowKnee = json.optDouble("scene_latitude_shadow_knee", -2.0).toFloat()
            p.sceneLatitudeShadowRoom = json.optDouble("scene_latitude_shadow_room", 0.0).toFloat()
            p.sceneLatitudeRolloff = json.optDouble("scene_latitude_rolloff", 2.0).toFloat()
            p.sceneLatitudeMaxLift = json.optDouble("scene_latitude_max_lift", 4.0).toFloat()
            p.printExposure = json.optDouble("print_exposure", 1.0).toFloat()
            p.mFilterShift = json.optDouble("m_filter_shift", 0.0).toFloat()
            p.yFilterShift = json.optDouble("y_filter_shift", 0.0).toFloat()
            p.grainActive = json.optBoolean("grain_active", true)
            p.grainAmount = json.optDouble("grain_amount", 1.0).toFloat().coerceIn(0f, 2f)
            p.grainLayered = json.optBoolean("grain_layered", true)
            p.halationActive = json.optBoolean("halation_active", true)
            p.halationAmount = json.optDouble("halation_amount", 1.0).toFloat()
            p.halationScatterAmount = json.optDouble("halation_scatter_amount", 1.0).toFloat().coerceIn(0f, 1f)
            p.halationBoostEv = json.optDouble("halation_boost_ev", 0.0).toFloat().coerceIn(0f, 8f)
            p.antihalationRemoved = json.optBoolean("antihalation_removed", false)
            p.dirCouplersActive = json.optBoolean("dir_couplers_active", true)
            p.dirCouplersAmount = json.optDouble("dir_couplers_amount", 1.0).toFloat().coerceIn(0f, 1.5f)
            p.glareActive = json.optBoolean("glare_active", true)
            p.glareAmount = json.optDouble("glare_amount", 1.0).toFloat().coerceIn(0f, 30f)
            p.preflashExposure = json.optDouble("preflash_exposure", 0.0).toFloat().coerceIn(0f, 0.03f)
            p.extendedDynamicRange = json.optBoolean("extended_dynamic_range", false)
            p.quarterTurns = json.optInt("quarter_turns", 0)
            p.flipH = json.optBoolean("flip_h", false)
            p.flipV = json.optBoolean("flip_v", false)
            p.rotationDeg = json.optDouble("rotation_deg", 0.0).toFloat()
            p.cropX = json.optDouble("crop_x", 0.0).toFloat()
            p.cropY = json.optDouble("crop_y", 0.0).toFloat()
            p.cropW = json.optDouble("crop_w", 1.0).toFloat()
            p.cropH = json.optDouble("crop_h", 1.0).toFloat()
            p.temperature = json.optDouble("temperature", 0.0).toFloat()
            p.tint = json.optDouble("tint", 0.0).toFloat()
            p.gradeExposure = json.optDouble("grade_exposure", 0.0).toFloat()
            p.contrast = json.optDouble("contrast", 0.0).toFloat()
            p.brightness = json.optDouble("brightness", 0.0).toFloat()
            p.saturation = json.optDouble("saturation", 0.0).toFloat()
            p.highlights = json.optDouble("highlights", 0.0).toFloat()
            p.shadows = json.optDouble("shadows", 0.0).toFloat()
            p.blackPoint = json.optDouble("black_point", 0.0).toFloat()
            p.whitePoint = json.optDouble("white_point", 0.0).toFloat()
            p.vignetteAmount = json.optDouble("vignette_amount", 0.0).toFloat().coerceIn(0f, 100f)
            p.vignetteSize = json.optDouble("vignette_size", 50.0).toFloat().coerceIn(0f, 100f)
            p.vignetteFeather = json.optDouble("vignette_feather", 70.0).toFloat().coerceIn(0f, 100f)
            p.cbShadowTemp = json.optDouble("cb_shadow_temp", 0.0).toFloat()
            p.cbShadowTint = json.optDouble("cb_shadow_tint", 0.0).toFloat()
            p.cbMidTemp = json.optDouble("cb_mid_temp", 0.0).toFloat()
            p.cbMidTint = json.optDouble("cb_mid_tint", 0.0).toFloat()
            p.cbHighTemp = json.optDouble("cb_high_temp", 0.0).toFloat()
            p.cbHighTint = json.optDouble("cb_high_tint", 0.0).toFloat()
            p.cameraFilter = json.optString("camera_filter", "")
            p.overscanActive = json.optBoolean("overscan_active", false)
            p.overscanFormat = json.optString("overscan_format", "135")
            p.overscanMode = json.optString("overscan_mode", "strip")
            p.overscanGate = json.optString("overscan_gate", "auto")
            p.overscanHoles = json.optString("overscan_holes", "white")
            p.overscanCarrier = json.optString("overscan_carrier", "none")
            p.overscanEdgeText = json.optString("overscan_edge_text", "KODAK PORTRA 400")
            p.overscanFrameNumber = json.optInt("overscan_frame_number", 0)
            p.overscanCameraSeed = json.optInt("overscan_camera_seed", 1)
            p.overscanFrameSeed = json.optInt("overscan_frame_seed", 1)
            p.overscanFog = json.optDouble("overscan_fog", 1.0).toFloat()
            p.overscanLeaks = json.optDouble("overscan_leaks", 0.0).toFloat()
            p.overscanFNumber = json.optDouble("overscan_f_number", 0.0).toFloat()
            p.dateImprintActive = json.optBoolean("date_imprint_active", false)
            p.dateImprintText = json.optString("date_imprint_text", "")
            p.dateImprintStyle = json.optString("date_imprint_style", "lcd")
            p.dateImprintPlacement = json.optString("date_imprint_placement", "frame")
            p.dateImprintCorner = json.optString("date_imprint_corner", "br")
            p.dateImprintSize = json.optDouble("date_imprint_size", 1.0).toFloat()
            p.dateImprintEv = json.optDouble("date_imprint_ev", 3.5).toFloat()
            p.dateImprintInsetX = json.optDouble("date_imprint_inset_x", 3.0).toFloat()
            p.dateImprintInsetY = json.optDouble("date_imprint_inset_y", 2.4).toFloat()
            p.dateImprintDataText = json.optString("date_imprint_data_text", "")
            return p
        }
    }

    private fun baseJson(srcW: Int, srcH: Int): JSONObject {
        // 片边开启时，把源图居中裁到该画幅的门径（gate）比例，否则用用户的裁剪。
        val c = if (overscanActive) {
            computeCenteredCropByAspect(overscanGateAspect(overscanFormat, srcW, srcH), srcW, srcH)
        } else {
            floatArrayOf(cropX, cropY, cropW, cropH)
        }
        return JSONObject()
            .put("film_stock", filmId)
            .put("print_stock", paperId)
            .put("exposure_compensation_ev", exposureEv.toDouble())
            .put("auto_exposure_method", autoExposureMethod)
            .put("auto_exposure", autoExposure)
            .put("film_format_mm", filmFormatMm.toDouble())
            .put("scene_latitude_active", sceneLatitudeActive)
            .put("scene_latitude_norm", sceneLatitudeNorm)
            .put("scene_latitude_highlight_knee", sceneLatitudeHighlightKnee.toDouble())
            .put("scene_latitude_highlight_room", sceneLatitudeHighlightRoom.toDouble())
            .put("scene_latitude_shadow_knee", sceneLatitudeShadowKnee.toDouble())
            .put("scene_latitude_shadow_room", sceneLatitudeShadowRoom.toDouble())
            .put("scene_latitude_rolloff", sceneLatitudeRolloff.toDouble())
            .put("scene_latitude_max_lift", sceneLatitudeMaxLift.toDouble())
            .put("print_exposure", printExposure.toDouble())
            .put("m_filter_shift", mFilterShift.toDouble())
            .put("y_filter_shift", yFilterShift.toDouble())
            .put("filter_shift_scale", 40.0)   // 让 ±1 的 shift 变成 ±40 CC（与原版 App 一致）
            .put("grain_active", grainActive)
            .put("grain_amount", (grainAmount * ownGrain(filmId)).coerceIn(0f, 2f).toDouble())
            .put("grain_sublayers_active", grainActive && grainLayered && !StockCatalog.isMonochrome(filmId))
            .put("scan_film", StockCatalog.isPositive(filmId))
            .put("halation_active", halationActive)
            .put("halation_amount", halationAmount.toDouble())
            .put("halation_scatter_amount", halationScatterAmount.toDouble())
            .put("halation_boost_ev", halationBoostEv.toDouble())
            .put("antihalation_removed", antihalationRemoved)
            .put("dir_couplers_active", dirCouplersActive)
            .put("dir_couplers_amount", dirCouplersAmount.toDouble())
            .put("glare_active", glareActive)
            .put("glare_amount", glareAmount.toDouble())
            .put("preflash_exposure", preflashExposure.toDouble())
            .put("extended_dynamic_range", extendedDynamicRange && !StockCatalog.isMonochrome(paperId) && !StockCatalog.isPositive(filmId))
            .put("geometry_quarter_turns", quarterTurns)
            .put("geometry_flip_h", flipH)
            .put("geometry_flip_v", flipV)
            .put("geometry_rotation_deg", rotationDeg.toDouble())
            .put("geometry_crop_x", c[0].toDouble())
            .put("geometry_crop_y", c[1].toDouble())
            .put("geometry_crop_w", c[2].toDouble())
            .put("geometry_crop_h", c[3].toDouble())
            .put("camera_filter", cameraFilter)
            .put("overscan_active", overscanActive)
            .put("overscan_format", overscanFormat)
            .put("overscan_mode", overscanMode)
            .put("overscan_gate", overscanGate)
            .put("overscan_holes", overscanHoles)
            .put("overscan_carrier", overscanCarrier)
            // Film edge lettering is a property of the selected emulsion and
            // gauge. It is resolved at render time so a sidecar cannot leave
            // stale Portra text after switching stocks.
            .put("overscan_edge_text", StockCatalog.edgeText(filmId, overscanFormat))
            .put("overscan_frame_number", overscanFrameNumber)
            .put("overscan_camera_seed", overscanCameraSeed)
            .put("overscan_frame_seed", overscanFrameSeed)
            .put("overscan_fog", overscanFog.toDouble())
            .put("overscan_leaks", overscanLeaks.toDouble())
            .put("overscan_f_number", overscanFNumber.toDouble())
            .put("date_imprint_active", dateImprintActive)
            .put("date_imprint_text", if (dateImprintStyle == "data") dateImprintDataText else dateImprintText)
            .put("date_imprint_style", dateImprintStyle)
            .put("date_imprint_placement", dateImprintPlacement)
            .put("date_imprint_corner", dateImprintCorner)
            .put("date_imprint_size", dateImprintSize.toDouble())
            .put("date_imprint_ev", dateImprintEv.toDouble())
            .put("date_imprint_inset_x", dateImprintInsetX.toDouble())
            .put("date_imprint_inset_y", dateImprintInsetY.toDouble())
            .put("date_imprint_data_text", dateImprintDataText)
            .apply {
                if (StockCatalog.isMonochrome(filmId) && StockCatalog.isMonochrome(paperId)) {
                    put("c_filter_neutral", 0.0); put("m_filter_neutral", 0.0); put("y_filter_neutral", 68.0)
                }
            }
    }
}
