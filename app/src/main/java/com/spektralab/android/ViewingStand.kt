package com.spektralab.android

import android.graphics.Bitmap
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An independent light table: removing an entry never deletes the source photo. */
@Composable
fun ViewingStand(
    photos: List<Pair<String, Bitmap>>,
    selectedKey: String?,
    lightOn: Boolean,
    onLightChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPickPhotos: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val selectedId = photos.firstOrNull { it.first == selectedKey }?.first ?: photos.firstOrNull()?.first
    val tableColor by animateColorAsState(if (lightOn) Color(0xFFF4F2ED) else Color(0xFF151719), label = "light-table")
    val photoBrightness by animateFloatAsState(if (lightOn) 1f else .50f, label = "film-light")
    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasPan by remember { mutableStateOf(Offset.Zero) }
    var magnifierOn by remember { mutableStateOf(false) }
    var lensCenter by remember { mutableStateOf<Offset?>(null) }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var exportPreview by remember { mutableStateOf<Bitmap?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val photoBounds = remember { mutableStateMapOf<String, Rect>() }
    val photoOffsets = remember { mutableStateMapOf<String, Offset>() }
    // Keep each photo's slot for the life of this canvas so removals cannot
    // recenter or resize the remaining photos.
    val photoSlots = remember { mutableMapOf<String, Int>() }
    val stackingOrder = remember { mutableStateListOf<String>() }
    val accent = Color(0xFFE0BD7A)

    LaunchedEffect(photos) {
        val keys = photos.map { it.first }.toSet()
        stackingOrder.removeAll { it !in keys }
        photos.forEach { if (it.first !in stackingOrder) stackingOrder.add(it.first) }
        photoOffsets.keys.toList().filterNot { it in keys }.forEach { photoOffsets.remove(it) }
        photoBounds.keys.toList().filterNot { it in keys }.forEach { photoBounds.remove(it) }
    }

    Column(modifier.fillMaxSize().background(Color(0xFF121315)).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("观景台", color = Color(0xFFF0EFEB), style = MaterialTheme.typography.titleMedium)
                Text(if (lightOn) "灯箱已开启" else "灯箱已关闭", color = Color(0xFFB8BABF), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { magnifierOn = !magnifierOn; lensCenter = null }, enabled = selectedId != null) {
                Icon(Icons.Filled.Search, if (magnifierOn) "关闭放大镜" else "打开放大镜",
                    tint = if (selectedId == null) Color(0xFF77797E) else if (magnifierOn) accent else Color(0xFFF0EFEB))
            }
            IconButton(onClick = {
                scope.launch {
                    exporting = true
                    try {
                        delay(150)
                        val screenshot = view.drawToBitmap()
                        val left = canvasBounds.left.roundToInt().coerceIn(0, screenshot.width - 1)
                        val top = canvasBounds.top.roundToInt().coerceIn(0, screenshot.height - 1)
                        val width = canvasBounds.width.roundToInt().coerceIn(1, screenshot.width - left)
                        val height = canvasBounds.height.roundToInt().coerceIn(1, screenshot.height - top)
                        val output = Bitmap.createBitmap(screenshot, left, top, width, height)
                        screenshot.recycle()
                        exportPreview = output
                    } catch (e: Exception) {
                        Toast.makeText(context, "预览失败：${e.message}", Toast.LENGTH_LONG).show()
                    } finally { exporting = false }
                }
            }, enabled = photos.isNotEmpty() && !exporting) {
                Icon(Icons.Filled.Share, "导出观片台", tint = if (photos.isNotEmpty()) Color(0xFFF0EFEB) else Color(0xFF77797E))
            }
            IconButton(onClick = { onLightChange(!lightOn) }) {
                Icon(Icons.Filled.LightMode, if (lightOn) "关闭灯箱" else "打开灯箱", tint = if (lightOn) accent else Color(0xFFB8BABF))
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭观景台", tint = Color(0xFFF0EFEB)) }
        }

        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp)).background(tableColor)
                .onGloballyPositioned { canvasBounds = it.boundsInRoot() }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        canvasScale = (canvasScale * zoom).coerceIn(.45f, 4f)
                        canvasPan += pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val canvasWidth = maxWidth
            val canvasHeight = maxHeight
            if (lightOn) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF5F3F0), Color(0xFFFFFEFC), Color(0xFFF1EEEA)))))
            }
            Canvas(Modifier.fillMaxSize()) {
                val tick = 3.dp.toPx()
                val step = 3.dp.toPx()
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.GRAY; textSize = 9.dp.toPx() }
                var i = 0
                while (i * step < maxOf(size.width, size.height)) {
                    val p = i * step
                    val length = tick * if (i % 10 == 0) 2.2f else if (i % 5 == 0) 1.5f else .8f
                    if (p < size.width) drawLine(Color.Gray.copy(alpha = .65f), Offset(p, 0f), Offset(p, length), 1f)
                    if (p < size.height) drawLine(Color.Gray.copy(alpha = .65f), Offset(0f, p), Offset(length, p), 1f)
                    if (i > 0 && i % 10 == 0) {
                        if (p < size.width) drawContext.canvas.nativeCanvas.drawText((i / 10).toString(), p, 17.dp.toPx(), paint)
                        if (p < size.height) drawContext.canvas.nativeCanvas.drawText((i / 10).toString(), 8.dp.toPx(), p + 3.dp.toPx(), paint)
                    }
                    i++
                }
            }
            if (photos.isEmpty()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("放上照片，开始查看", color = if (lightOn) Color(0xFF34312D) else Color(0xFFE8E5DF), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onPickPhotos) { Text("添加照片") }
                }
            } else {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = canvasScale; scaleY = canvasScale
                    translationX = canvasPan.x; translationY = canvasPan.y
                }) {
                    val columns = if (canvasWidth > canvasHeight) 3 else 2
                    val cellW = canvasWidth / columns * .88f
                    val cellH = minOf(canvasHeight * .27f, 220.dp)
                    photos.forEach { (key, bitmap) ->
                        val slot = photoSlots.getOrPut(key) {
                            generateSequence(0) { it + 1 }.first { it !in photoSlots.values }
                        }
                        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                        val width = minOf(cellW, cellH * ratio)
                        val height = width / ratio
                        val col = slot % columns
                        val row = slot / columns
                        val gridX = (col - (columns - 1) / 2f) * (canvasWidth / columns).value
                        val rowOffset = if (row == 0) 0 else if (row % 2 == 1) -(row + 1) / 2 else row / 2
                        val gridY = rowOffset * (cellH.value + 16f)
                        val userOffset = photoOffsets[key] ?: Offset.Zero
                        val bounds = photoBounds[key]
                        val overlapsAnother = bounds != null && photos.any { (otherKey, _) ->
                            if (otherKey == key) return@any false
                            val other = photoBounds[otherKey] ?: return@any false
                            val overlapWidth = minOf(bounds.right, other.right) - maxOf(bounds.left, other.left)
                            val overlapHeight = minOf(bounds.bottom, other.bottom) - maxOf(bounds.top, other.top)
                            overlapWidth > 2f && overlapHeight > 2f
                        }
                        StandPhoto(
                            bitmap = bitmap, selected = key == selectedId && !exporting, brightness = photoBrightness,
                            translucency = if (overlapsAnother) .78f else 1f,
                            onSelect = { stackingOrder.remove(key); stackingOrder.add(key); onSelect(key) },
                            onDrag = { delta -> photoOffsets[key] = (photoOffsets[key] ?: Offset.Zero) + delta / canvasScale },
                            modifier = Modifier.align(Alignment.Center).zIndex(stackingOrder.indexOf(key).coerceAtLeast(0).toFloat()).offset(
                                x = gridX.dp + with(density) { userOffset.x.toDp() },
                                y = gridY.dp + with(density) { userOffset.y.toDp() },
                            ).width(width).height(height).onGloballyPositioned { coordinates ->
                                val topLeft = coordinates.localToRoot(Offset.Zero)
                                val bottomRight = coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                                photoBounds[key] = Rect(topLeft, bottomRight)
                            },
                        )
                    }
                }
            }
            if (magnifierOn && photos.isNotEmpty() && !exporting) {
                val lensSize = with(density) { 132.dp.toPx() }
                val center = lensCenter ?: Offset(
                    (canvasBounds.width - lensSize / 2 - with(density) { 12.dp.toPx() }).coerceAtLeast(lensSize / 2),
                    lensSize / 2 + with(density) { 12.dp.toPx() },
                )
                val rootPoint = center + canvasBounds.topLeft
                val topKey = stackingOrder.lastOrNull { photoBounds[it]?.contains(rootPoint) == true }
                val hit = photos.firstOrNull { it.first == topKey }
                val bounds = hit?.let { photoBounds[it.first] }
                val point = if (bounds != null && bounds.width > 0f && bounds.height > 0f) {
                    Offset((rootPoint.x - bounds.left) / bounds.width, (rootPoint.y - bounds.top) / bounds.height)
                } else Offset.Zero
                MagnifierPreview(hit?.second, point, bounds?.let { Size(it.width, it.height) }, photoBrightness, tableColor, Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset((center.x - lensSize / 2).roundToInt(), (center.y - lensSize / 2).roundToInt()) }
                    .pointerInput(photos, canvasBounds) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            val next = (lensCenter ?: center) + amount
                            val clamped = Offset(next.x.coerceIn(0f, canvasBounds.width), next.y.coerceIn(0f, canvasBounds.height))
                            lensCenter = clamped
                        }
                    })
            }
        }

        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedId == null) "尚未选择照片" else "${photos.indexOfFirst { it.first == selectedId } + 1} / ${photos.size}",
                color = Color(0xFFB8BABF), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
            Row(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF292B2F)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val zoomTint = if (photos.isNotEmpty()) Color(0xFFF0EFEB) else Color(0xFF77797E)
                IconButton(onClick = { canvasScale = (canvasScale / 1.2f).coerceAtLeast(.45f) }, enabled = photos.isNotEmpty()) {
                    Icon(Icons.Filled.Remove, "缩小", tint = zoomTint)
                }
                Text("${(canvasScale * 100).roundToInt()}%", color = Color(0xFFF0EFEB), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { canvasScale = (canvasScale * 1.2f).coerceAtMost(4f) }, enabled = photos.isNotEmpty()) {
                    Icon(Icons.Filled.Add, "放大", tint = zoomTint)
                }
            }
            IconButton(onClick = { canvasScale = 1f; canvasPan = Offset.Zero; photoOffsets.clear() }, enabled = photos.isNotEmpty()) {
                Icon(Icons.Filled.RestartAlt, "复位画布", tint = if (photos.isNotEmpty()) Color(0xFFF0EFEB) else Color(0xFF77797E))
            }
            IconButton(onClick = { selectedId?.let { photoSlots.remove(it); onRemove(it) } }, enabled = selectedId != null) {
                Icon(Icons.Filled.DeleteOutline, "从观景台移除照片", tint = if (selectedId != null) Color(0xFFF0EFEB) else Color(0xFF77797E))
            }
        }
        Row(Modifier.fillMaxWidth().height(76.dp).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = onPickPhotos,
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color(0xFF383329), contentColor = accent,
                ),
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "添加照片")
            }
            Spacer(Modifier.width(10.dp))
            LazyRow(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(photos, key = { it.first }) { (key, bitmap) ->
                    Image(bitmap.asImageBitmap(), "选择照片", Modifier.size(72.dp, 56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (key == selectedId) Modifier.border(2.dp, accent, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { stackingOrder.remove(key); stackingOrder.add(key); onSelect(key) }, contentScale = ContentScale.Crop)
                }
            }
        }
    }

    exportPreview?.let { output ->
        fun export(share: Boolean) {
            scope.launch {
                exporting = true
                try {
                    withContext(Dispatchers.IO) {
                        if (share) {
                            val dir = File(context.cacheDir, "lighttable").apply { mkdirs() }
                            val file = File(dir, "SpektraLab-lighttable-${System.currentTimeMillis()}.png")
                            file.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            withContext(Dispatchers.Main) {
                                val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(intent, "分享观片台"))
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, "SpektraLab-lighttable-${System.currentTimeMillis()}.png")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpektraLab")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                            try {
                                checkNotNull(context.contentResolver.openOutputStream(uri)).use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                                context.contentResolver.update(uri, values, null, null)
                            } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
                            withContext(Dispatchers.Main) { Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show() }
                        } else {
                            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                            val file = File(dir, "SpektraLab-lighttable-${System.currentTimeMillis()}.png")
                            file.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            withContext(Dispatchers.Main) { Toast.makeText(context, "已保存：${file.absolutePath}", Toast.LENGTH_LONG).show() }
                        }
                    }
                    exportPreview = null
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                } finally { exporting = false }
            }
        }
        AlertDialog(onDismissRequest = { if (!exporting) exportPreview = null }, title = { Text("导出预览") },
            text = { Image(output.asImageBitmap(), contentDescription = "观片台导出预览", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit) },
            confirmButton = { TextButton(onClick = { export(true) }) { Text("分享") } },
            dismissButton = { TextButton(onClick = { export(false) }) { Text("保存图片") } })
    }
}

@Composable
private fun StandPhoto(
    bitmap: Bitmap,
    selected: Boolean,
    brightness: Float,
    translucency: Float,
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier
        .graphicsLayer {
            alpha = (translucency * brightness).coerceIn(.35f, 1f)
            shadowElevation = if (selected) 18.dp.toPx() else 9.dp.toPx()
            shape = RoundedCornerShape(2.dp)
            clip = false
        }
        .shadow(if (selected) 12.dp else 7.dp, RoundedCornerShape(2.dp), ambientColor = Color.Black.copy(alpha = .28f), spotColor = Color.Black.copy(alpha = .42f))
        .clip(RoundedCornerShape(2.dp))
        .background(Color(0xFF111213))
        .pointerInput(bitmap) {
            detectDragGestures(onDragStart = { onSelect() }, onDrag = { change, amount ->
                change.consume()
                onDrag(amount)
            })
        }
        .clickable(onClick = onSelect)
        .then(if (selected) Modifier.border(2.dp, Color(0xFFE0BD7A), RoundedCornerShape(2.dp)) else Modifier)) {
        Image(bitmap.asImageBitmap(), "画布照片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - brightness)))
    }
}

@Composable
private fun MagnifierPreview(bitmap: Bitmap?, point: Offset, displaySize: Size?, brightness: Float, tableColor: Color, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Canvas(modifier.size(132.dp).shadow(8.dp, CircleShape).clip(CircleShape)
        .background(tableColor).border(3.dp, Color(0xFFB8B5AE), CircleShape)) {
        if (image != null && displaySize != null) {
            // Keep the pixel under the lens centre fixed, including at image edges.
            val scaledWidth = displaySize.width * 2.5f
            val scaledHeight = displaySize.height * 2.5f
            drawImage(image, dstOffset = IntOffset((size.width / 2f - point.x * scaledWidth).roundToInt(),
                (size.height / 2f - point.y * scaledHeight).roundToInt()),
                dstSize = IntSize(scaledWidth.roundToInt().coerceAtLeast(1), scaledHeight.roundToInt().coerceAtLeast(1)))
            drawCircle(Color.Black.copy(alpha = 1f - brightness))
        }
    }
}
