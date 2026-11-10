package com.spektralab.android.feature.library

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@DrawableRes
internal fun stockArtworkResource(id: String): Int? = when (id) {
    "kodak_portra_400" -> R.drawable.stock_kodak_portra_400
    "kodak_portra_160" -> R.drawable.stock_kodak_portra_160
    "kodak_portra_800", "kodak_portra_800_push1", "kodak_portra_800_push2" -> R.drawable.stock_kodak_portra_800
    "kodak_gold_200" -> R.drawable.stock_kodak_gold_200
    "kodak_ektar_100" -> R.drawable.stock_kodak_ektar_100
    "kodak_ultramax_400" -> R.drawable.stock_kodak_ultramax_400
    "kodak_ektachrome_100" -> R.drawable.stock_kodak_ektachrome_100
    "kodak_kodachrome_64" -> R.drawable.stock_kodak_kodachrome_64
    "fujifilm_c200" -> R.drawable.stock_fujifilm_c200
    "fujifilm_pro_400h" -> R.drawable.stock_fujifilm_pro_400h
    "fujifilm_xtra_400" -> R.drawable.stock_fujifilm_xtra_400
    "fujifilm_provia_100f" -> R.drawable.stock_fujifilm_provia_100f
    "fujifilm_velvia_100" -> R.drawable.stock_fujifilm_velvia_100
    "fujifilm_neopan_acros_100_ii" -> R.drawable.stock_fujifilm_neopan_acros_100_ii
    "ilford_hp5_plus_400" -> R.drawable.stock_ilford_hp5_plus_400
    "kodak_tmax_100" -> R.drawable.stock_kodak_tmax_100
    "kodak_tri_x_400" -> R.drawable.stock_kodak_tri_x_400
    "kodak_vision3_50d" -> R.drawable.stock_kodak_vision3_50d
    "kodak_vision3_200t" -> R.drawable.stock_kodak_vision3_200t
    "kodak_vision3_250d" -> R.drawable.stock_kodak_vision3_250d
    "kodak_vision3_500t" -> R.drawable.stock_kodak_vision3_500t
    "kodak_verita_200d" -> R.drawable.stock_kodak_verita_200d
    "ilford_multigrade_iv_rc" -> R.drawable.stock_ilford_multigrade_iv_rc
    "fujifilm_crystal_archive_typeii" -> R.drawable.stock_fujifilm_crystal_archive_typeii
    "kodak_ektacolor_edge" -> R.drawable.stock_kodak_ektacolor_edge
    "kodak_endura_premier" -> R.drawable.stock_kodak_endura_premier
    "kodak_portra_endura" -> R.drawable.stock_kodak_portra_endura
    "kodak_supra_endura" -> R.drawable.stock_kodak_supra_endura
    "kodak_ultra_endura" -> R.drawable.stock_kodak_ultra_endura
    "kodak_2383" -> R.drawable.stock_kodak_2383
    "kodak_2393" -> R.drawable.stock_kodak_2393
    else -> null
}

/** Bounded decoded cache; SVG and large PNG masters never enter the APK. */
private object StockArtworkImages {
    private val decodeMutex = Mutex()
    private val cache = object : LruCache<Int, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(@DrawableRes resource: Int): ImageBitmap? = cache.get(resource)

    suspend fun load(resources: Resources, @DrawableRes resource: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        decodeMutex.withLock {
            cache.get(resource) ?: BitmapFactory.decodeResource(resources, resource)?.asImageBitmap()?.also {
                cache.put(resource, it)
            }
        }
    }
}

@Composable
internal fun StockArtwork(id: String, modifier: Modifier = Modifier) {
    val resource = stockArtworkResource(id)
    val resources = LocalContext.current.resources
    val bitmap by produceState(resource?.let(StockArtworkImages::cached), resource, resources) {
        value = resource?.let(StockArtworkImages::cached)
        if (resource != null && value == null) value = StockArtworkImages.load(resources, resource)
    }
    // The parent reserves the same space while the small offline resource decodes.
    Box(modifier) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
internal fun StockImageCredits(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("图片来源") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("材料缩略图根据提供的标签参考高清重制，以品牌、型号及色块区分胶片和相纸；未提供的型号按同一标签风格补齐。纸卡边缘带有轻微厚度，外部背景透明。",
                    style = MaterialTheme.typography.bodySmall)
                Text("品牌名称与商标属于各自权利人。图像是材料识别示意，不是官方包装实拍；Portra 800 推片版本共用基础标签，并通过名称中的 +1 / +2 区分。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
