package com.spektralab.android.engine

import android.content.Context
import org.json.JSONArray
import kotlin.math.abs

/** 一条镜头校正配置（对应 Lensfun 数据库里一个镜头的标定）。 */
data class LensProfile(
    val maker: String,
    val model: String,
    val crop: Float,
    val focal: Float,
    val distModel: String,       // "ptlens" | "poly3"
    val distTerms: FloatArray,   // 3 元素：ptlens=[a,b,c]，poly3=[k1,0,0]
    val tcaVr: Float,
    val tcaVb: Float,
    val vigK: FloatArray,        // [k1,k2,k3]
)

/**
 * 加载 assets 里的镜头配置 JSON（tools/extract_lenses.py 从 Lensfun 数据库生成），
 * 按镜头型号做模糊匹配。不依赖 Lensfun/GLib。
 */
object LensProfileStore {

    private var profiles: List<LensProfile>? = null

    fun load(context: Context): List<LensProfile> {
        profiles?.let { return it }
        val list = try {
            val text = context.assets.open("engine/lens_profiles.json")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("dist_terms")
                val terms = FloatArray(3)
                for (j in 0 until minOf(t.length(), 3)) terms[j] = t.getDouble(j).toFloat()
                val v = o.getJSONArray("vig_k")
                LensProfile(
                    maker = o.optString("maker", ""),
                    model = o.optString("model", ""),
                    crop = o.optDouble("crop", 1.0).toFloat(),
                    focal = o.optDouble("focal", 50.0).toFloat(),
                    distModel = o.optString("dist_model", ""),
                    distTerms = terms,
                    tcaVr = o.optDouble("tca_vr", 1.0).toFloat(),
                    tcaVb = o.optDouble("tca_vb", 1.0).toFloat(),
                    vigK = floatArrayOf(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getDouble(2).toFloat()),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        profiles = list
        return list
    }

    /** 按镜头型号模糊匹配；[focal] 用于在多个候选里选焦距最接近的。 */
    fun match(make: String, lens: String, focal: Float): LensProfile? {
        val all = profiles ?: return null
        if (lens.isBlank()) return null
        val nl = normalize(lens)
        val nm = normalize(make)
        val candidates = all.filter { p ->
            val np = normalize(p.model)
            np.isNotEmpty() && (nl.contains(np) || np.contains(nl)) &&
                (nm.isBlank() || normalize(p.maker).contains(nm) || nm.contains(normalize(p.maker)))
        }
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { abs(it.focal - focal) }
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
