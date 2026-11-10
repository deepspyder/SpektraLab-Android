package com.spektralab.android.core.develop

import android.graphics.Bitmap
import android.graphics.Color
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.engine.Stock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class SmartDevelopStyle(val id: String, val title: String, val description: String) {
    Natural("natural", "自然耐看", "保留现场色彩，柔和修整明暗"),
    Cinema("cinema", "电影浓郁", "更有层次的反差与电影胶片色彩"),
    Vintage("vintage", "轻柔复古", "温润色彩、柔和影调与适量颗粒");

    companion object { fun from(id: String) = entries.firstOrNull { it.id == id } ?: Cinema }
}

/** Measured pixel properties only. These are not claims of semantic subject recognition. */
internal data class SmartPhotoAnalysis(
    val median: Float, val low: Float, val high: Float,
    val clippedHighlights: Float, val darkFraction: Float, val saturation: Float,
    val greenFraction: Float, val blueFraction: Float, val warmFraction: Float,
    val neutralBalance: Float, val detail: Float,
) {
    val span get() = high - low
    val lowLight get() = median < .24f && darkFraction > .3f
    val highContrast get() = span > .75f || clippedHighlights > .04f
    val muted get() = saturation < .17f
    fun summary(): String = buildList {
        add(when { lowLight -> "暗部较多"; median > .68f -> "画面明亮"; else -> "明暗均衡" })
        if (highContrast) add("反差较强") else if (span < .35f) add("反差较低")
        when { warmFraction > .32f -> add("暖色较多"); greenFraction > .25f -> add("绿色较多")
            blueFraction > .25f -> add("蓝色较多"); muted -> add("色彩柔和") }
    }.joinToString(" · ")
}

internal object SmartDevelop {
    const val VERSION = 1

    /** At most ~25k sampled pixels, ignoring transparent pixels and without an extra full-size copy. */
    fun analyze(bitmap: Bitmap): SmartPhotoAnalysis? {
        val stepX = max(1, (bitmap.width + 159) / 160); val stepY = max(1, (bitmap.height + 159) / 160)
        val row = IntArray(bitmap.width)
        val histogram = IntArray(256)
        var count=0;var bright=0;var dark=0;var green=0;var blue=0;var warm=0;var neutral=0
        var saturation=0.0;var neutralBalance=0.0;var difference=0.0;var pairs=0
        for (y in 0 until bitmap.height step stepY) {
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            var previous: Float?=null
            for (x in 0 until bitmap.width step stepX) {
                val pixel=row[x]
                if (Color.alpha(pixel)<128) { previous=null;continue }
                val r=Color.red(pixel)/255f;val g=Color.green(pixel)/255f;val b=Color.blue(pixel)/255f
                val maximum=max(r,max(g,b));val minimum=min(r,min(g,b))
                val s=if(maximum>.001f)(maximum-minimum)/maximum else 0f
                val l=.2126f*r+.7152f*g+.0722f*b
                histogram[(l*255).toInt().coerceIn(0,255)]++;count++;saturation+=s
                if(l>.97f)bright++;if(l<.16f)dark++
                if(g>r*1.12f && g>b*1.08f && l>.15f)green++
                if(b>r*1.15f && b>g*1.04f && l>.15f)blue++
                if(r>b*1.25f && r>g*1.02f && s>.12f && l>.12f)warm++
                if(s<.18f && l in .15f.. .85f){neutral++;neutralBalance+=(r-b)/(maximum+.01f)}
                previous?.let { difference+=abs(l-it);pairs++ };previous=l
            }
        }
        if(count<32)return null
        fun percentile(fraction:Float):Float {
            val target=max(1,(count*fraction).toInt());var sum=0
            for(i in histogram.indices){sum+=histogram[i];if(sum>=target)return i/255f}
            return 1f
        }
        return SmartPhotoAnalysis(percentile(.5f),percentile(.05f),percentile(.95f),bright.toFloat()/count,
            dark.toFloat()/count,(saturation/count).toFloat(),green.toFloat()/count,blue.toFloat()/count,warm.toFloat()/count,
            if(neutral>count*.02f)(neutralBalance/neutral).toFloat() else 0f,
            if(pairs>0)(difference/pairs).toFloat() else 0f)
    }

    fun recommend(base: EditorParams, analysis: SmartPhotoAnalysis, style: SmartDevelopStyle,
        films: List<Stock>, papers: List<Stock>): EditorParams {
        val a=analysis
        val scores=linkedMapOf<String,Float>()
        fun candidate(id:String,score:Float){scores[id]=score}
        when(style){
            SmartDevelopStyle.Natural -> {
                candidate("kodak_portra_400",5f + if(a.highContrast)3 else 0)
                candidate("fujifilm_pro_400h",3f+a.greenFraction*10)
                candidate("kodak_ektar_100",2f+(if(a.muted)4 else 0)+(if(a.median>.55f)1 else 0))
                candidate("kodak_portra_800",2f+(if(a.lowLight)7 else 0))
            }
            SmartDevelopStyle.Cinema -> {
                candidate("kodak_vision3_250d",5f+(a.greenFraction+a.blueFraction)*8)
                candidate("kodak_vision3_500t",2f+(if(a.lowLight)7 else 0)+a.warmFraction*6)
                candidate("kodak_vision3_200t",3f+a.warmFraction*7+(if(a.highContrast)2 else 0))
                candidate("kodak_portra_400",2f+(if(a.clippedHighlights>.12f)8 else 0))
            }
            SmartDevelopStyle.Vintage -> {
                candidate("kodak_gold_200",5f+a.warmFraction*5)
                candidate("fujifilm_c200",3f+a.greenFraction*10)
                candidate("kodak_portra_160",3f+(if(a.highContrast)4 else 0))
                candidate("kodak_portra_800",2f+(if(a.lowLight)7 else 0))
            }
        }
        val film=films.filter { !it.monochrome }.maxByOrNull { scores[it.id] ?: -100f }
            ?: films.firstOrNull { it.id==base.filmId }
        val filmId=film?.id ?: base.filmId
        val compatible=papers.filter { it.monochrome==(film?.monochrome ?: false) }
        val paperId=when(style){
            SmartDevelopStyle.Cinema -> (if(a.highContrast)listOf("kodak_2393","kodak_2383") else listOf("kodak_2383","kodak_2393"))
            SmartDevelopStyle.Vintage -> listOf("kodak_ektacolor_edge",film?.targetPrint.orEmpty())
            SmartDevelopStyle.Natural -> listOf(film?.targetPrint.orEmpty(),"kodak_portra_endura")
        }.firstOrNull { id -> compatible.any { it.id==id } }
            ?: compatible.firstOrNull()?.id ?: base.paperId
        return base.copy().apply {
            this.filmId=filmId;this.paperId=paperId
            autoExposure=true
            autoExposureMethod=when{a.highContrast -> "protect_highlights";a.lowLight -> "protect_shadows";else -> "balanced"}
            // Engine auto exposure stays authoritative. This is only a small mood offset.
            exposureEv=if(a.lowLight)-.12f else if(a.clippedHighlights>.08f)-.10f else 0f
            printExposure=1f;mFilterShift=0f;yFilterShift=0f
            temperature=(-a.neutralBalance*.18f).coerceIn(-.10f,.10f)
            tint=0f;gradeExposure=0f
            highlights=if(a.highContrast)-18f else -5f
            shadows=if(a.lowLight)12f else if(a.darkFraction>.25f)8f else 2f
            contrast=when(style){SmartDevelopStyle.Natural -> if(a.span<.35f)8f else 2f
                SmartDevelopStyle.Cinema -> if(a.highContrast)5f else 14f
                SmartDevelopStyle.Vintage -> if(a.span<.35f)-2f else -9f}
            brightness=0f
            saturation=when(style){SmartDevelopStyle.Natural -> if(a.saturation>.55f)-6f else 3f
                SmartDevelopStyle.Cinema -> if(a.saturation>.6f)1f else 12f
                SmartDevelopStyle.Vintage -> -10f}
            // Do not add colour to an almost monochrome source.
            if(a.saturation<.04f)saturation=min(saturation,0f)
            blackPoint=if(style==SmartDevelopStyle.Cinema && !a.lowLight)2f else 0f;whitePoint=0f
            cbShadowTemp=if(style==SmartDevelopStyle.Cinema)-6f else 0f;cbShadowTint=0f
            cbMidTemp=0f;cbMidTint=0f
            cbHighTemp=if(style==SmartDevelopStyle.Vintage)4f else if(style==SmartDevelopStyle.Cinema)3f else 0f
            cbHighTint=0f
            grainActive=true
            grainAmount=when(style){SmartDevelopStyle.Natural -> .40f;SmartDevelopStyle.Cinema -> .75f;SmartDevelopStyle.Vintage -> 1.05f}*(if(a.lowLight).55f else 1f)
            halationActive=true;halationAmount=if(style==SmartDevelopStyle.Cinema).55f else .30f
            halationScatterAmount=.6f;halationBoostEv=0f
            glareActive=true;glareAmount=if(a.highContrast).25f else .4f
            vignetteAmount=when(style){SmartDevelopStyle.Natural -> 0f;SmartDevelopStyle.Cinema -> 8f;SmartDevelopStyle.Vintage -> 10f}
            vignetteSize=60f;vignetteFeather=80f
            smartDevelopStyle=style.id;smartDevelopSummary=a.summary();smartDevelopVersion=VERSION
        }
    }
}
