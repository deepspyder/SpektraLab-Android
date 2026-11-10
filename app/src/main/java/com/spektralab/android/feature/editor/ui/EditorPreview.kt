package com.spektralab.android.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.engine.Histogram
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.crop.CropOverlay
import com.spektralab.android.ui.components.BeforeAfterPreview
import com.spektralab.android.ui.components.DarkroomChrome
import com.spektralab.android.ui.components.captureReadouts
import com.spektralab.android.ui.components.rememberSourcePhotoInfo
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun PhotoMetadataStrip(vm: EditorViewModel) {
    if (!vm.hasPhoto) return
    val identity = rememberSourcePhotoInfo(vm.currentUri,
        vm.photoInfo.firstOrNull { it.first == "文件名" }?.second, vm.currentIsRaw)
    val values = captureReadouts(vm.photoInfo).ifEmpty {
        listOfNotNull(vm.photoInfo.firstOrNull { it.first == "原始尺寸" }?.second)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 12.dp)
            .semantics { contentDescription = "照片参数信息" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            values.forEach { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(if (vm.currentUri == null) "测试图" else identity.detail,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ParameterStockCaption(vm: EditorViewModel, onBrowse: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onBrowse)
        .semantics { contentDescription = "胶片与相纸" }.padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(vm.films.firstOrNull { it.id == vm.params.filmId }?.name ?: "胶片", Modifier.weight(1f),
            fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(vm.papers.firstOrNull { it.id == vm.params.paperId }?.name ?: "相纸", Modifier.weight(1f),
            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun EditorStockSelection(vm: EditorViewModel, onBrowseFilms: () -> Unit, onBrowsePapers: () -> Unit) {
    val film = vm.films.firstOrNull { it.id == vm.params.filmId }
    val papers = vm.papers.filter { StockCatalog.canPrint(vm.params.filmId, it.id) }
    val paper = papers.firstOrNull { it.id == vm.params.paperId }
    Row(Modifier.fillMaxWidth().background(DarkroomChrome.navigation).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.15f).heightIn(min = 64.dp).clickable(onClick = onBrowseFilms)
            .semantics { contentDescription = "胶片与相纸" }.padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("胶片 / ${if (StockCatalog.isMonochrome(vm.params.filmId)) "黑白" else if (StockCatalog.isPositive(vm.params.filmId)) "彩色正片" else "彩色负片"}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(22.dp).background(MaterialTheme.colorScheme.primary))
                Text(film?.name ?: "选择胶片", Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(14.dp))
            }
        }
        Box(Modifier.width(1.dp).height(48.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)))
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onBrowsePapers)
                .semantics { contentDescription = "相纸" }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("相纸", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(paper?.name ?: "选择相纸", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PhotoCanvas(
    vm: EditorViewModel,
    comparing: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canCompare = vm.sourceBitmap != null && vm.rendered != null && !vm.isCropping
    BoxWithConstraints(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = "照片预览" },
        contentAlignment = Alignment.Center
    ) {
        val source = vm.sourceBitmap
        val rendered = vm.rendered
        if (vm.isCropping && source != null) {
            CropOverlay(
                bitmap = source,
                cropX = vm.params.cropX, cropY = vm.params.cropY,
                cropW = vm.params.cropW, cropH = vm.params.cropH,
                onCropChange = { x, y, w, h ->
                    vm.params.cropX = x; vm.params.cropY = y
                    vm.params.cropW = w; vm.params.cropH = h
                    vm.cropAspect = "自定义"
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (comparing && canCompare) {
            BeforeAfterPreview(source!!, rendered!!, Modifier.fillMaxSize())
        } else if (rendered != null) {
            Image(rendered.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else if (source != null) {
            Image(source.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                Text("导入照片", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Button(onClick = onImport) { Text("选择照片") }
            }
        }
        val displayed = rendered ?: source
        if ((!comparing || !canCompare) && displayed != null) {
            val aspect = displayed.width.toFloat() / displayed.height
            val photoWidth = minOf(maxWidth, maxHeight * aspect)
            val photoHeight = photoWidth / aspect
            if (photoWidth >= 144.dp && photoHeight >= 120.dp) {
                vm.histogram?.let { histogram ->
                    HistogramView(histogram, Modifier.align(Alignment.BottomEnd)
                        .padding(end = (maxWidth - photoWidth) / 2 + 12.dp,
                            bottom = (maxHeight - photoHeight) / 2 + 12.dp)
                        .size(112.dp, 46.dp)
                        .background(Color.Black.copy(alpha = .5f), TheiaShapes.badge)
                        .padding(4.dp))
                }
            }
        }

    }
}

@Composable
private fun HistogramView(histogram: Histogram, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val maxCount = maxOf(
            histogram.r.maxOrNull() ?: 1,
            histogram.g.maxOrNull() ?: 1,
            histogram.b.maxOrNull() ?: 1,
        ).toFloat().coerceAtLeast(1f)
        val binWidth = size.width / 256f
        fun drawChannel(bins: IntArray, color: Color) {
            val path = Path()
            path.moveTo(0f, size.height)
            for (i in 0 until 256) {
                val h = (bins[i] / maxCount) * size.height
                path.lineTo(i * binWidth, size.height - h)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, color.copy(alpha = 0.4f))
        }
        drawChannel(histogram.r, Color.Red)
        drawChannel(histogram.g, Color.Green)
        drawChannel(histogram.b, Color.Blue)
    }
}
