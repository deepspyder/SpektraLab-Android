package com.spektralab.android.core.model

import com.spektralab.android.engine.Stock
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Explicit ownership keeps copy, preview and presets on the same field contract. */
enum class EditGroup(val key: String, val fields: Set<String>) {
    MATERIAL("material", setOf("film_stock", "print_stock")),
    DECODE("decode", setOf("camera_wb_kelvin", "camera_wb_tint", "camera_wb_custom")),
    FILM("film", setOf("exposure_compensation_ev", "auto_exposure_method", "auto_exposure", "film_format_mm",
        "scene_latitude_active", "scene_latitude_norm", "scene_latitude_highlight_knee", "scene_latitude_highlight_room",
        "scene_latitude_shadow_knee", "scene_latitude_shadow_room", "scene_latitude_rolloff", "scene_latitude_max_lift",
        "grain_active", "grain_amount", "grain_layered", "halation_active", "halation_amount", "halation_scatter_amount",
        "halation_boost_ev", "antihalation_removed", "dir_couplers_active", "dir_couplers_amount", "extended_dynamic_range", "camera_filter")),
    PRINT("print", setOf("print_exposure", "m_filter_shift", "y_filter_shift", "glare_active", "glare_amount", "preflash_exposure")),
    LIGHT("light", setOf("grade_exposure", "contrast", "brightness", "highlights", "shadows", "black_point", "white_point")),
    CURVES("curves", setOf("tone_curves")),
    COLOR("color", setOf("temperature", "tint", "saturation", "color_adjustments", "channel_mixer", "color_wheels",
        "cb_shadow_temp", "cb_shadow_tint", "cb_mid_temp", "cb_mid_tint", "cb_high_temp", "cb_high_tint")),
    VIGNETTE("vignette", setOf("vignette_amount", "vignette_size", "vignette_feather")),
    GEOMETRY("geometry", setOf("quarter_turns", "flip_h", "flip_v", "rotation_deg", "crop_x", "crop_y", "crop_w", "crop_h")),
    FRAME("frame", setOf("overscan_active", "overscan_format", "overscan_mode", "overscan_gate", "overscan_holes",
        "overscan_carrier", "overscan_fog", "overscan_leaks", "date_imprint_active", "date_imprint_style",
        "date_imprint_placement", "date_imprint_corner", "date_imprint_size", "date_imprint_ev", "date_imprint_inset_x", "date_imprint_inset_y"));

    companion object {
        val DEFAULT: Set<EditGroup> = entries.toSet() - setOf(GEOMETRY, FRAME)
        // Per-photo analysis, resolved camera data, dates and random identities never travel.
        val LOCAL_FIELDS = setOf("smart_develop_style", "smart_develop_summary", "smart_develop_version",
            "overscan_edge_text", "overscan_frame_number", "overscan_camera_seed", "overscan_frame_seed",
            "overscan_f_number", "date_imprint_text", "date_imprint_data_text")
    }
}

enum class EditTransferNotice { RAW_ONLY, PAPER_ADJUSTED, COLOR_REPLACED, CURVES_REPLACED }
enum class EditTransferProblem { UNKNOWN_FILM, UNKNOWN_PAPER, NO_COMPATIBLE_PAPER, INCOMPATIBLE_MATERIAL }
data class EditApplication(val params: EditorParams?, val notices: Set<EditTransferNotice> = emptySet(),
                           val problem: EditTransferProblem? = null)

/** Immutable, versioned template: no photo URI, geometry by default, output settings or seed. */
class EditTemplate private constructor(val groups: Set<EditGroup>, private val valuesJson: String) {
    fun toJson(): JSONObject = JSONObject().put("version", 1)
        .put("groups", JSONArray(EditGroup.entries.filter { it in groups }.map { it.key }))
        .put("values", JSONObject(valuesJson))

    fun applyTo(target: EditorParams, raw: Boolean, films: List<Stock>, papers: List<Stock>): EditApplication {
        val values = JSONObject(valuesJson)
        val result = target.toJson()
        val notices = mutableSetOf<EditTransferNotice>()
        for (group in groups) {
            if (group == EditGroup.DECODE && !raw) {
                notices += EditTransferNotice.RAW_ONLY
                continue
            }
            for (key in group.fields) {
                if (values.has(key)) result.put(key, values.get(key)) else result.remove(key)
            }
        }
        val updated = EditorParams.fromJson(result)
        run {
            val film = films.firstOrNull { it.id == updated.filmId }
                ?: return EditApplication(null, problem = EditTransferProblem.UNKNOWN_FILM)
            val paper = papers.firstOrNull { it.id == updated.paperId }
                ?: return EditApplication(null, problem = EditTransferProblem.UNKNOWN_PAPER)
            if (film.monochrome != paper.monochrome) {
                if (EditGroup.MATERIAL !in groups) return EditApplication(null, problem = EditTransferProblem.INCOMPATIBLE_MATERIAL)
                updated.paperId = papers.firstOrNull { it.id == film.targetPrint && it.monochrome == film.monochrome }?.id
                    ?: papers.firstOrNull { it.monochrome == film.monochrome }?.id
                    ?: return EditApplication(null, problem = EditTransferProblem.NO_COMPATIBLE_PAPER)
                notices += EditTransferNotice.PAPER_ADJUSTED
            }
        }
        if (EditGroup.COLOR in groups && (target.unreadableColorAdjustments != null ||
                target.unreadableChannelMixer != null || target.unreadableColorWheels != null)) notices += EditTransferNotice.COLOR_REPLACED
        if (EditGroup.CURVES in groups && target.unreadableToneCurves != null) notices += EditTransferNotice.CURVES_REPLACED
        return EditApplication(updated, notices)
    }

    companion object {
        private val optional = setOf("tone_curves", "color_adjustments", "channel_mixer", "color_wheels")

        fun capture(params: EditorParams, groups: Set<EditGroup>): EditTemplate {
            require(groups.isNotEmpty())
            val all = params.toJson()
            val selected = JSONObject()
            groups.flatMap { it.fields }.forEach { key -> if (all.has(key)) selected.put(key, all.get(key)) }
            return fromJson(JSONObject().put("version", 1).put("groups", JSONArray(groups.map { it.key })).put("values", selected))
        }

        fun fromJson(json: JSONObject): EditTemplate {
            require(json.get("version") is Number && json.getDouble("version") == 1.0)
            val listed = json.getJSONArray("groups")
            val groups = (0 until listed.length()).map { index ->
                EditGroup.entries.first { it.key == listed.get(index) }
            }.toSet()
            require(groups.isNotEmpty() && groups.size == listed.length())
            val values = json.getJSONObject("values")
            val fields = groups.flatMap { it.fields }.toSet()
            require(values.keys().asSequence().all { it in fields })
            require((fields - optional).all(values::has))
            val defaults = EditorParams().toJson()
            for (key in fields - optional) {
                val raw = values.get(key)
                require(when (defaults.get(key)) {
                    is Boolean -> raw is Boolean
                    is String -> raw is String
                    is Number -> raw is Number && raw.toDouble().isFinite()
                    else -> false
                })
                if (raw is Number) {
                    val range = when (key) {
                        "camera_wb_kelvin" -> 1000.0..50000.0
                        "camera_wb_tint" -> -1000.0..1000.0
                        "exposure_compensation_ev", "grade_exposure" -> -16.0..16.0
                        "film_format_mm" -> 1.0..1000.0
                        "print_exposure" -> .01..64.0
                        "m_filter_shift", "y_filter_shift" -> -4.0..4.0
                        "grain_amount", "halation_amount", "halation_scatter_amount", "dir_couplers_amount" -> 0.0..8.0
                        "halation_boost_ev" -> 0.0..16.0
                        "glare_amount" -> 0.0..100.0
                        "preflash_exposure" -> 0.0..1.0
                        "temperature", "tint" -> -1.0..1.0
                        "crop_x", "crop_y", "crop_w", "crop_h" -> 0.0..1.0
                        "quarter_turns" -> 0.0..3.0
                        "rotation_deg" -> -45.0..45.0
                        "vignette_amount", "vignette_size", "vignette_feather" -> 0.0..100.0
                        "scene_latitude_highlight_knee", "scene_latitude_shadow_knee" -> -32.0..32.0
                        "scene_latitude_highlight_room", "scene_latitude_shadow_room", "scene_latitude_max_lift" -> 0.0..32.0
                        "scene_latitude_rolloff" -> .01..32.0
                        "overscan_fog", "overscan_leaks" -> 0.0..4.0
                        "date_imprint_size" -> .1..10.0
                        "date_imprint_ev" -> -16.0..16.0
                        "date_imprint_inset_x", "date_imprint_inset_y" -> 0.0..100.0
                        else -> -100.0..100.0
                    }
                    require(raw.toDouble() in range)
                    if (key == "quarter_turns") require(raw.toDouble() % 1.0 == 0.0)
                }
            }
            val choices = mapOf(
                "auto_exposure_method" to setOf("balanced", "center", "protect_highlights", "protect_shadows"),
                "scene_latitude_norm" to setOf("power", "y", "max"),
                "camera_filter" to setOf("", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58"),
                "overscan_format" to setOf("135", "135_half", "135_xpan", "120_645", "120_6x6", "120_6x7", "120_6x8",
                    "120_6x9", "120_6x12", "120_6x17"), "overscan_mode" to setOf("strip", "filed"),
                "overscan_gate" to setOf("auto", "square", "rounded", "eared", "shouldered", "kicked"),
                "overscan_holes" to setOf("white", "black"), "overscan_carrier" to setOf("none", "black", "open"),
                "date_imprint_style" to setOf("lcd", "dots", "data"), "date_imprint_placement" to setOf("frame", "rebate"),
                "date_imprint_corner" to setOf("br", "bl", "tr", "tl"),
            )
            choices.forEach { (key, allowed) -> if (values.has(key)) require(values.getString(key) in allowed) }
            val parsed = EditorParams.fromJson(values)
            if (EditGroup.GEOMETRY in groups) require(parsed.cropW > 0f && parsed.cropH > 0f &&
                parsed.cropX + parsed.cropW <= 1.00001f && parsed.cropY + parsed.cropH <= 1.00001f)
            require(parsed.unreadableToneCurves == null && parsed.unreadableColorAdjustments == null &&
                parsed.unreadableChannelMixer == null && parsed.unreadableColorWheels == null)
            return EditTemplate(groups.toSet(), values.toString())
        }
    }
}

data class EditPreset(val id: String, val name: String, val template: EditTemplate) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("template", template.toJson())

    companion object {
        fun normalizedName(value: String): String = value.trim().also { name ->
            require(name.isNotEmpty() && name.length <= 48 && name.none { it.isISOControl() })
        }
        fun nameKey(value: String) = normalizedName(value).lowercase(Locale.ROOT)
        fun fromJson(json: JSONObject): EditPreset {
            val id = json.getString("id")
            require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            return EditPreset(id, normalizedName(json.getString("name")), EditTemplate.fromJson(json.getJSONObject("template")))
        }
    }
}
