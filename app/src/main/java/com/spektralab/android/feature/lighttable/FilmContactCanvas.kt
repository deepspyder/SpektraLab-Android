package com.spektralab.android.feature.lighttable

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.spektralab.android.core.model.TableGeometry
import com.spektralab.android.core.model.TablePoint

private const val WALL_WIDTH = 600f
private const val PHOTO_GAP = 14f

internal data class WallPhoto(val index: Int, val bounds: Rect, val rotation: Float = 0f) {
    val polygon = TableGeometry.corners(bounds.left, bounds.top, bounds.width, bounds.height, rotation)
    private val outerBounds = polygonBounds(polygon)
    fun frame(key: String) = WallFrame(key, bounds, rotation)
    fun contains(point: Offset) = if (rotation == 0f) bounds.contains(point) else TableGeometry.contains(polygon, TablePoint(point.x, point.y))
    fun outer(): Rect = outerBounds
}

private fun polygonBounds(points: List<TablePoint>): Rect = if (points.isEmpty()) Rect.Zero else
    Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

/** A quiet photo wall: original order, natural ratios, no synthetic film gates. */
internal fun photoWallLayout(photos: List<ViewingPhoto>, state: FilmCanvasState): List<WallPhoto> {
    val columnBottoms = floatArrayOf(12f, 48f, 26f)
    return photos.mapIndexed { index, photo ->
        val column = index % 3
        val bitmap = photo.thumbnail ?: photo.wallDetail
        val sourceW = bitmap?.width ?: photo.sourceWidth
        val sourceH = bitmap?.height ?: photo.sourceHeight
        val aspect = if (sourceW > 0 && sourceH > 0) sourceW.toFloat() / sourceH else 1f
        // Natural rectangles on an initially loose table, not a locked grid.
        val width = if (aspect > 2.5f) 205f else if (aspect < .8f) 150f else 170f
        val naturalHeight = width / aspect
        val size = state.photoSizes[photo.key] ?: androidx.compose.ui.geometry.Size(width, naturalHeight)
        val height = size.height
        val left = column * 203f + floatArrayOf(4f, -7f, 3f, 11f)[index % 4]
        val top = columnBottoms[column]
        columnBottoms[column] = top + height + PHOTO_GAP
        val position = state.position(photo.key, Offset(left, top))
        WallPhoto(index, Rect(position, size), state.rotations[photo.key] ?: 0f)
    }.sortedBy { state.photoOrder.indexOf(photos[it.index].key).takeIf { i -> i >= 0 } ?: (state.photoOrder.size + it.index) }
}

private fun Rect.screen(origin: Offset, scale: Float) = Rect(origin + topLeft * scale, size * scale)
private fun Rect.android() = RectF(left, top, right, bottom)

/** Fit the complete photograph without cropping, rounded masks or stretching. */
internal fun fitContactPhoto(width: Int, height: Int, gate: Rect): Rect {
    if (width <= 0 || height <= 0) return Rect(gate.center, androidx.compose.ui.geometry.Size.Zero)
    val available = gate
    val scale = min(available.width / width, available.height / height).coerceAtLeast(0f)
    val size = androidx.compose.ui.geometry.Size(width * scale, height * scale)
    return Rect(gate.center - Offset(size.width, size.height) / 2f, size)
}

/** Only visible photographs are drawn. Higher-resolution decodes are bounded and demand driven. */
@Composable
internal fun PhotoWallCanvas(
    photos: List<ViewingPhoto>, selectedKey: String?, state: FilmCanvasState,
    lamp: Float, magnifierOn: Boolean, onLoad: (String) -> Unit, onOpen: (String) -> Unit,
    onLoadDetails: suspend (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    onActions: ((String) -> Unit)? = null,
    onVisible: (List<String>) -> Unit = {}, enabled: Boolean = true,
) {
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    var loupeBounds by remember { mutableStateOf(Rect.Zero) }
    val layout = photoWallLayout(photos, state)
    val latestLayout by rememberUpdatedState(layout)
    val joinMotion=state.joinMotion
    LaunchedEffect(joinMotion) {
        if(joinMotion!=null){
            animate(0f,1f,animationSpec=tween(240,easing=FastOutSlowInEasing)){value,_ ->
                if(state.joinMotion===joinMotion)state.joinFraction=value
            }
            if(state.joinMotion===joinMotion)state.finishJoin()
        }
    }
    DisposableEffect(state) { onDispose { state.endDrag(false);state.finishJoin() } }
    val inset = with(density) { 12.dp.toPx() }
    val fit = ((viewport.width - inset * 2) / WALL_WIDTH).coerceAtLeast(.01f)
    val anchor = Offset(inset, inset)
    val origin = anchor + state.pan
    val scale = fit * state.zoom
    val viewportRect = Rect(Offset.Zero, androidx.compose.ui.geometry.Size(viewport.width.toFloat(), viewport.height.toFloat()))
    SideEffect {
        state.focus = viewportRect.center; state.origin = anchor; state.viewportFit = fit
        if (viewport.width > 0 && viewport.height > 0) {
            val start = (Offset.Zero - origin) / scale
            val end = (Offset(viewport.width.toFloat(), viewport.height.toFloat()) - origin) / scale
            state.exportViewport = com.spektralab.android.core.model.TableBox(start.x, start.y, end.x, end.y)
            state.exportFade = with(density) { 12.dp.toPx() } / scale
        }
        photos.map { it.key }.filterNot(state.photoOrder::contains).forEach(state.photoOrder::add)
    }
    val frames = layout.filter { it.outer().screen(origin, scale).overlaps(viewportRect) }
    val overlaps=frames.mapIndexed { index, frame ->
        frames.take(index).mapNotNull { lower ->
            if (!frame.outer().overlaps(lower.outer())) null
            else if (frame.rotation == 0f && lower.rotation == 0f) frame.bounds.intersect(lower.bounds).let {
                TableGeometry.corners(it.left, it.top, it.width, it.height, 0f)
            } else TableGeometry.intersect(frame.polygon, lower.polygon).takeIf { TableGeometry.area(it) > .001f }
        }
    }
    val overlapOpacity=frames.mapIndexed { index, frame ->
        key(photos[frame.index].key){
            val depth=overlaps[index].maxOfOrNull { polygonBounds(it).let { bounds -> min(bounds.width, bounds.height) * scale } } ?: 0f
            animateFloatAsState(com.spektralab.android.core.model.TableMaterial.transmission(depth, with(density){12.dp.toPx()}),
                tween(120),label="overlap-transmission").value
        }
    }
    val latestPhotos by rememberUpdatedState(photos)
    val latestOpen by rememberUpdatedState(onOpen)
    val latestLoadDetails by rememberUpdatedState(onLoadDetails)
    // A stable request set independent of cache arrivals avoids eviction/refetch loops.
    // Request detail only when the displayed image outgrows its thumbnail (or loupe).
    val detailKeys = frames.filter { frame ->
        val photo = photos[frame.index]
        val thumb = photo.thumbnail
        val image = fitContactPhoto(thumb?.width ?: 640, thumb?.height ?: 640, frame.bounds)
        photo.canRefine && max(image.width, image.height) * scale * (if (magnifierOn) 2.5f else 1f) >
            max(thumb?.width ?: 640, thumb?.height ?: 640) * 1.1f
    }.sortedBy { (it.bounds.screen(origin, scale).center - viewportRect.center).getDistanceSquared() }
        .take(4).map { photos[it.index].key }
    LaunchedEffect(detailKeys) {
        if (detailKeys.isNotEmpty()) {
            delay(140) // Do not decode every intermediate viewport during a pinch or pan.
            latestLoadDetails(detailKeys)
        }
    }
    fun wallBitmap(photo: ViewingPhoto) = (if (state.zoom > 1.05f || magnifierOn) photo.wallDetail else null)
        ?.takeUnless { it.isRecycled } ?: photo.thumbnail?.takeUnless { it.isRecycled }
    val background = lerp(Color(0xFF171819), Color(0xFFF3F0E8), lamp)
    val bitmapPaint = remember(lamp) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val light = .075f + .925f * lamp
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            light, 0f, 0f, 0f, 1.5f * (1 - lamp),
            0f, light, 0f, 0f, 1.2f * (1 - lamp),
            0f, 0f, light, 0f, .8f * (1 - lamp),
            0f, 0f, 0f, 1f, 0f)))
    } }
    val fill = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val overlapPath = remember { Path() }
    val seamPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND } }
    LaunchedEffect(frames.map { latestPhotos[it.index].key }) {
        onVisible(frames.map { latestPhotos[it.index].key })
        frames.forEach { onLoad(latestPhotos[it.index].key) }
    }
    val targets = frames.mapNotNull { frame ->
        val bitmap = wallBitmap(photos[frame.index]) ?: return@mapNotNull null
        val image = fitContactPhoto(bitmap.width, bitmap.height, frame.bounds)
            .screen(origin, scale).translate(rootBounds.topLeft)
        LensTarget(bitmap, image, frame.outer().screen(origin, scale).translate(rootBounds.topLeft).intersect(rootBounds), frame.rotation)
    }
    Box(modifier.clipToBounds().onSizeChanged { viewport = it }
        .onGloballyPositioned { rootBounds = it.boundsInRoot() }
        .semantics {
            contentDescription = "照片墙"
            stateDescription = "自由摆放照片，画布缩放 ${(state.zoom * 100).roundToInt()}%，靠边吸附，重叠透光"
            progressBarRangeInfo = ProgressBarRangeInfo(state.zoom, .35f..6f)
            setProgress { value ->
                state.edit { state.transform(viewportRect.center, Offset.Zero, value.coerceIn(.35f, 6f) / state.zoom, anchor) }; true
            }
            scrollBy { x, y -> state.edit { state.move(Offset(-x, -y)) }; true }
        }
        .pointerInput(state, viewport, enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!enabled) return@awaitEachGesture
                // The floating loupe owns the whole gesture, including after it leaves this photo.
                if(loupeBounds.contains(down.position+rootBounds.topLeft))return@awaitEachGesture
                val point = (down.position - anchor - state.pan) / (fit * state.zoom)
                val photo = latestLayout.lastOrNull { it.contains(point) }
                val photoKey = photo?.let { latestPhotos[it.index].key }
                var moving = false
                var canvasGesture = false
                var totalPan = Offset.Zero
                var photoDragStarted=false
                state.beginGesture()
                var completed = false
                try { do {
                    val event = awaitPointerEvent()
                    if(event.changes.any { it.isConsumed }){
                        if(photoDragStarted)state.endDrag(false)
                        return@awaitEachGesture
                    }
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    if (event.changes.count { it.pressed } > 1) canvasGesture = true
                    totalPan += pan
                    val wasMoving=moving
                    if (totalPan.getDistance() > viewConfiguration.touchSlop || canvasGesture) moving = true
                    if (moving && event.changes.any { it.pressed }) {
                        if (canvasGesture || photoKey == null) {
                            if(photoDragStarted){state.endDrag(false);photoDragStarted=false}
                            state.transform(event.calculateCentroid(), pan, zoom, anchor)
                        } else {
                            if(!photoDragStarted){state.beginDrag(photoKey);photoDragStarted=true}
                            state.movePhoto(photoKey, photo.bounds.topLeft, (if(wasMoving)pan else totalPan) / (fit * state.zoom))
                            val bounds=Rect(state.position(photoKey,photo.bounds.topLeft),photo.bounds.size)
                            state.previewJoin(findWallJoin(WallFrame(photoKey,bounds,photo.rotation),latestLayout.map {
                                WallFrame(latestPhotos[it.index].key,it.bounds,it.rotation)
                            },with(density){18.dp.toPx()}/(fit*state.zoom)))
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                    if(photoDragStarted)state.endDrag(true)
                    completed = true
                } finally {
                    if(state.draggingKey==photoKey)state.endDrag(false)
                    state.finishGesture(cancelled = !completed)
                }
            }
        }
        .pointerInput(state, viewport, enabled) {
            detectTapGestures(onTap = { position ->
                if(!enabled || loupeBounds.contains(position+rootBounds.topLeft))return@detectTapGestures
                val point = (position - anchor - state.pan) / (fit * state.zoom)
                latestLayout.lastOrNull { it.contains(point) }
                    ?.let { latestOpen(latestPhotos[it.index].key) }
            }, onLongPress = { position ->
                if (enabled && !loupeBounds.contains(position + rootBounds.topLeft)) {
                    val point = (position - anchor - state.pan) / (fit * state.zoom)
                    latestLayout.lastOrNull { it.contains(point) }?.let { onActions?.invoke(latestPhotos[it.index].key) }
                }
            }, onDoubleTap = { position ->
                if(enabled && !loupeBounds.contains(position+rootBounds.topLeft))
                    state.edit { state.transform(position, Offset.Zero, (if (state.zoom < 1.5f) 2.2f else 1f) / state.zoom, anchor) }
            })
        }) {
        Canvas(Modifier.fillMaxSize()) {
            // The table lighting is fixed in the viewport while the photographs move.
            drawRect(background)
            drawRect(Brush.radialGradient(listOf(
                lerp(Color(0xFF22201D), Color(0xFFFFFDF5), lamp), background),
                center = center.copy(y = size.height * .4f), radius = max(size.width, size.height) * .85f))
            val canvas = drawContext.canvas.nativeCanvas
            val saved = canvas.save()
            canvas.translate(origin.x, origin.y); canvas.scale(scale, scale)
            for ((frameIndex,frame) in frames.withIndex()) {
                val photo = photos[frame.index]
                val bitmap = wallBitmap(photo)
                overlapPath.reset()
                overlaps[frameIndex].forEach { polygon ->
                    overlapPath.moveTo(polygon.first().x, polygon.first().y)
                    polygon.drop(1).forEach { overlapPath.lineTo(it.x, it.y) }; overlapPath.close()
                }
                fun drawPhoto(fitted: Rect) {
                    val transformed = canvas.save()
                    canvas.rotate(frame.rotation, frame.bounds.center.x, frame.bounds.center.y)
                    canvas.drawBitmap(requireNotNull(bitmap), null, fitted.android(), bitmapPaint)
                    canvas.restoreToCount(transformed)
                }
                if (bitmap != null) {
                    val fitted = fitContactPhoto(bitmap.width, bitmap.height, frame.bounds)
                    if(overlaps[frameIndex].isEmpty()){
                        bitmapPaint.alpha=255
                        drawPhoto(fitted)
                    } else {
                        val outside=canvas.save();canvas.clipOutPath(overlapPath)
                        bitmapPaint.alpha=255
                        drawPhoto(fitted)
                        canvas.restoreToCount(outside)
                        val inside=canvas.save();canvas.clipPath(overlapPath)
                        bitmapPaint.alpha=(overlapOpacity[frameIndex]*255).roundToInt()
                        drawPhoto(fitted)
                        canvas.restoreToCount(inside)
                    }
                } else {
                    fill.color = lerp(Color(0xFF242424), Color(0xFFE2DED5), lamp).toArgb()
                    fill.alpha=255
                    val transformed = canvas.save()
                    canvas.rotate(frame.rotation, frame.bounds.center.x, frame.bounds.center.y)
                    canvas.drawRect(frame.bounds.android(), fill)
                    canvas.restoreToCount(transformed)
                }
            }
            bitmapPaint.alpha=255
            val seam=state.joinPreview ?: state.joinMotion?.join
            if(seam!=null){
                val reveal=if(state.joinPreview!=null).75f else 1f-state.joinFraction
                seamPaint.color=lerp(StandColors.amber,StandColors.brown,lamp).toArgb()
                seamPaint.alpha=(255*reveal).roundToInt()
                seamPaint.strokeWidth=with(density){1.5.dp.toPx()}/scale
                canvas.drawLine(seam.seamStart.x,seam.seamStart.y,seam.seamEnd.x,seam.seamEnd.y,seamPaint)
            }
            canvas.restoreToCount(saved)
        }
        // Hit/reading regions track the transformed frames, without composing off-screen images.
        for (frame in frames) {
            val photo = photos[frame.index]
            val bounds = frame.outer().screen(origin, scale).intersect(viewportRect)
            if (bounds.width < 1 || bounds.height < 1) continue
            Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .semantics {
                    contentDescription = "查看第 ${frame.index + 1} 张 ${photo.name}"
                    selected = photo.key == selectedKey
                    stateDescription = (if (photo.key == selectedKey) "当前照片，" else "") +
                        "完整原比例，${photo.sourceFormat}，${photo.sourceWidth} × ${photo.sourceHeight}" +
                        if(overlaps[frames.indexOf(frame)].isNotEmpty()) "，叠片透光" else ""
                    onClick("沉浸观看") { onOpen(photo.key); true }
                    customActions = listOf(
                        CustomAccessibilityAction("调整摆放") { onActions?.invoke(photo.key); onActions != null },
                        CustomAccessibilityAction("向左移动照片") { state.edit { state.movePhoto(photo.key, frame.bounds.topLeft, Offset(-20f, 0f)) }; true },
                        CustomAccessibilityAction("向右移动照片") { state.edit { state.movePhoto(photo.key, frame.bounds.topLeft, Offset(20f, 0f)) }; true },
                        CustomAccessibilityAction("向上移动照片") { state.edit { state.movePhoto(photo.key, frame.bounds.topLeft, Offset(0f, -20f)) }; true },
                        CustomAccessibilityAction("向下移动照片") { state.edit { state.movePhoto(photo.key, frame.bounds.topLeft, Offset(0f, 20f)) }; true },
                        CustomAccessibilityAction("吸附到相邻照片") {
                            val join=findWallJoin(WallFrame(photo.key,frame.bounds,frame.rotation),layout.map {
                                WallFrame(photos[it.index].key,it.bounds,it.rotation)
                            },with(density){32.dp.toPx()}/scale)
                            if(join!=null){state.edit { state.movePhoto(photo.key,frame.bounds.topLeft,Offset.Zero)
                                state.previewJoin(join);state.endDrag(true) };true}else false
                        })
                })
        }
        if (magnifierOn && targets.isNotEmpty()) PhotoLoupe(rootBounds, targets, .075f + .925f * lamp, background,
            onBoundsChange={loupeBounds=it})
    }
}

@Composable
internal fun FilmCanvasToolbar(state: FilmCanvasState, lightOn: Boolean, modifier: Modifier = Modifier) {
    val ink = if (lightOn) StandColors.ink else StandColors.white
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if(state.joinPreview!=null) "松手拼接" else "靠边吸附 · 重叠透光", Modifier.weight(1f).padding(start = 8.dp),
            color = if (lightOn) StandColors.muted else StandColors.soft, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = { state.zoomBy(1f / 1.25f) }, enabled = state.zoom > .35f) {
            Icon(Icons.Rounded.Remove, "缩小画布", tint = ink)
        }
        TextButton(onClick = { state.reset() }, modifier = Modifier.widthIn(min = 64.dp).semantics { contentDescription = "重置画布" }) {
            Text("${(state.zoom * 100).roundToInt()}%", color = ink, style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = { state.zoomBy(1.25f) }, enabled = state.zoom < 6f) {
            Icon(Icons.Rounded.Add, "放大画布", tint = ink)
        }
    }
}
