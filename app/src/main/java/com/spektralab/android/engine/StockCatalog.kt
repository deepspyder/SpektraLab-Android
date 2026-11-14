package com.spektralab.android.engine

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 一款胶片或相纸。
 *
 * @param id    引擎用的 stock id（如 `kodak_portra_400`），即 wire 字段 film_stock/print_stock 的值
 * @param name  显示名（如 `Kodak Portra 400`），来自 profile 的 `info.name`
 * @param stage `filming`（胶片）或 `printing`（相纸）
 * @param type  `negative`（负片）或 `positive`（反转片/正片）
 */
data class Stock(
    val id: String,
    val name: String,
    val stage: String,
    val type: String,
    val targetPrint: String?,
    val monochrome: Boolean = false,
    val antihalation: String = "",
    val derivedFrom: String? = null,
)

/**
 * 从 assets 里的 profile JSON 读出胶片/相纸目录。
 * 不依赖引擎，可在创建引擎前调用。
 */
object StockCatalog {
    private var stocks: Map<String, Stock> = emptyMap()
    fun isMonochrome(id: String): Boolean = stocks[id]?.monochrome == true
    fun isPositive(id: String): Boolean = stocks[id]?.type == "positive"
    fun hasNoAntihalation(id: String): Boolean = stocks[id]?.antihalation == "no"
    fun canPrint(film: String, paper: String): Boolean = isMonochrome(film) == isMonochrome(paper)

    /** The lettering exposed by the physical emulsion, resolved like Windows. */
    fun edgeText(id: String, format: String = "135"): String {
        val gauge120 = format.startsWith("120")
        val known = mapOf(
            "kodak_gold_200" to ("KODAK GB 200-7" to "KODAK 200"),
            "kodak_portra_800" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_portra_800_push1" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_portra_800_push2" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_ultramax_400" to ("KODAK GC 400" to "KODAK GC 400"),
            "kodak_ektachrome_100" to ("KODAK E100" to "KODAK E100"),
            "fujifilm_c200" to ("FUJI 200" to "FUJI 200"),
            "fujifilm_xtra_400" to ("S-400" to "FUJI S-400"),
            "fujifilm_pro_400h" to ("PRO400H" to "FUJI PRO400H"),
            "fujifilm_provia_100f" to ("FUJI RDPIII" to "FUJI RDPIII"),
            "fujifilm_velvia_100" to ("RVP100" to "FUJI RVP100"),
            "kodak_vision3_50d" to ("EASTMAN 5203" to "EASTMAN 5203"),
            "kodak_vision3_250d" to ("EASTMAN 5207" to "EASTMAN 5207"),
            "kodak_vision3_200t" to ("EASTMAN 5213" to "EASTMAN 5213"),
            "kodak_vision3_500t" to ("EASTMAN 5219" to "EASTMAN 5219"),
            "cinestill_800t" to ("CINESTILL 800T" to "CINESTILL 800T"),
        )[id]
        if (known != null) return if (gauge120) known.second else known.first
        val name = stocks[id]?.name ?: id
        return name.substringBefore('(').trim().uppercase(Locale.US)
    }

    /** Windows' visible film picker order: Positive first, then Negative. */
    fun filmGroups(films: List<Stock>): List<Pair<String, List<Stock>>> = listOf(
        "正片" to films.filter { it.type == "positive" },
        "负片" to films.filter { it.type != "positive" },
    ).filter { it.second.isNotEmpty() }

    fun load(context: Context): Pair<List<Stock>, List<Stock>> {
        val films = mutableListOf<Stock>()
        val papers = mutableListOf<Stock>()
        val dir = "engine/resources/profiles"
        val files = context.assets.list(dir) ?: return films to papers

        for (f in files.sorted()) {
            if (!f.endsWith(".json")) continue
            val text = context.assets.open("$dir/$f").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val root = JSONObject(text)
            val base = root.optString("derived_from").takeIf { it.isNotEmpty() }
            val info = if (base == null) root.getJSONObject("info") else {
                require(base.matches(Regex("[a-zA-Z0-9_-]{1,96}"))) { "Invalid material alias" }
                val original = JSONObject(context.assets.open("$dir/$base.json").bufferedReader().use { it.readText() })
                require(!original.has("derived_from")) { "Nested material aliases are unsupported" }
                JSONObject(original.getJSONObject("info").toString()).apply {
                    val overrides = root.getJSONObject("info")
                    overrides.keys().forEach { key -> put(key, overrides.get(key)) }
                    put("stock", f.removeSuffix(".json"))
                }
            }
            val stock = Stock(
                id = info.getString("stock"),
                name = info.optString("name", info.getString("stock")),
                stage = info.optString("stage", ""),
                type = info.optString("type", ""),
                targetPrint = if (info.isNull("target_print")) null else info.optString("target_print"),
                monochrome = info.optString("channel_model") == "bw",
                antihalation = info.optString("antihalation"),
                derivedFrom = base,
            )
            when (stock.stage) {
                "filming" -> films += stock
                "printing" -> papers += stock
            }
        }
        stocks = (films + papers).associateBy { it.id }
        return films.sortedBy { it.name } to papers.sortedBy { it.name }
    }
}
