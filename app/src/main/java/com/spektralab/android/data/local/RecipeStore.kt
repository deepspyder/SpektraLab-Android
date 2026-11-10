package com.spektralab.android.data.local

import android.content.Context
import com.spektralab.android.core.model.ExportRecipe
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 导出配方的持久化（filesDir/recipes.json）。默认配方 + 自定义配方一起存。 */
object RecipeStore {
    private const val FILE = "recipes.json"

    val DEFAULTS = listOf(
        ExportRecipe("sRGB JPEG 2048", "JPEG", 2048, "sRGB"),
        ExportRecipe("sRGB JPEG 全尺寸", "JPEG", 0, "sRGB"),
        ExportRecipe("Display P3 TIFF 全尺寸", "TIFF", 0, "Display P3"),
        ExportRecipe("sRGB PNG 2048", "PNG", 2048, "sRGB"),
    )

    fun load(context: Context): List<ExportRecipe> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return DEFAULTS
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ExportRecipe(
                    o.getString("name"),
                    o.getString("format"),
                    o.getInt("res"),
                    o.getString("color_space"),
                    o.optInt("jpeg_quality", 95),
                    o.optBoolean("keep_capture", true),
                    o.optBoolean("keep_location", false),
                )
            }
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun save(context: Context, recipes: List<ExportRecipe>) {
        val arr = JSONArray()
        recipes.forEach { r ->
            arr.put(
                JSONObject()
                    .put("name", r.name)
                    .put("format", r.format)
                    .put("res", r.res)
                    .put("color_space", r.colorSpace)
                    .put("jpeg_quality", r.jpegQuality)
                    .put("keep_capture", r.keepCapture)
                    .put("keep_location", r.keepLocation)
            )
        }
        try {
            File(context.filesDir, FILE).writeText(arr.toString())
        } catch (e: Exception) {
            // 写失败不阻塞
        }
    }
}
