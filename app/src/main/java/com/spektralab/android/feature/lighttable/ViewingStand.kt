package com.spektralab.android.feature.lighttable

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.spektralab.android.ui.theme.TheiaShapes
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Materials from the approved contact-sheet design. */
internal object StandColors {
    val paper = Color(0xFFF1EEE7)
    val ink = Color(0xFF242321)
    val muted = Color(0xFF68645D)
    val line = Color(0xFFD8D2C7)
    val brown = Color(0xFF86542D)
    val night = Color(0xFF171716)
    val film = Color(0xFF252421)
    val amber = Color(0xFFE5B96F)
    val white = Color(0xFFF8F6F1)
    val soft = Color(0xFFB8B2A8)
}

internal data class LensTarget(val bitmap: Bitmap, val imageBounds: Rect, val clipBounds: Rect, val rotation: Float = 0f)

@Composable
internal fun ViewingStand(
    photos: List<ViewingPhoto>, importing: Boolean, importCompleted: Int, importTotal: Int,
    actionsEnabled: Boolean, onLoadThumbnail: (String) -> Unit, onLoadDetail: (String) -> Unit,
    onLoadWallDetails: suspend (List<String>) -> Unit,
    onImport: () -> Unit, onRemove: (String) -> Unit, onEdit: (String) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier,
    sharedCanvasState: FilmCanvasState? = null, backDescription: String = "返回暗房",
    projectControls: @Composable (Boolean) -> Unit = {}, onPhotoActions: ((String) -> Unit)? = null,
    onVisibleItems: (List<String>) -> Unit = {},
) {
    var magnifierOn by rememberSaveable { mutableStateOf(false) }
    var viewerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val canvasState = sharedCanvasState ?: rememberFilmCanvasState()
    val lightOn = canvasState.lightOn
    val wallBackground by animateColorAsState(if (lightOn) StandColors.paper else StandColors.night,
        tween(360, easing = FastOutSlowInEasing), label = "light-table")
    val lamp by androidx.compose.animation.core.animateFloatAsState(if (lightOn) 1f else 0f, tween(360), label = "light-output")
    val brightness = .075f + .925f * lamp
    val view = LocalView.current
    DisposableEffect(lightOn, viewerKey != null) {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldBrightness = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = if (lightOn) .9f else .2f }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = lightOn && viewerKey == null
        controller?.isAppearanceLightNavigationBars = lightOn && viewerKey == null
        onDispose {
            if (window != null && oldBrightness != null) window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            oldStatus?.let { controller?.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
    LaunchedEffect(photos.map { it.key }) {
        if (selectedKey !in photos.map { it.key }) selectedKey = photos.firstOrNull()?.key
        if (photos.isEmpty()) viewerKey = null
    }
    BackHandler(enabled = viewerKey != null || magnifierOn) {
        if (magnifierOn) magnifierOn = false else viewerKey = null
    }
    fun removePhoto(key: String) {
        val index = photos.indexOfFirst { it.key == key }
        val replacement = photos.getOrNull(index + 1)?.key ?: photos.getOrNull(index - 1)?.key
        onRemove(key)
        if (selectedKey == key) selectedKey = replacement
        if (viewerKey == key) viewerKey = replacement
    }
    Box(modifier.fillMaxSize().background(if (viewerKey == null) wallBackground else StandColors.night)
        .statusBarsPadding().navigationBarsPadding()) {
        AnimatedContent(targetState = viewerKey != null && photos.isNotEmpty(), modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (fadeIn(tween(300)) + scaleIn(tween(380, easing = FastOutSlowInEasing), initialScale = .97f))
                    .togetherWith(fadeOut(tween(160)) + scaleOut(tween(250), targetScale = 1.025f))
                    .using(SizeTransform(clip = false))
            }, label = "contact-sheet-viewer") { viewing ->
            if (viewing) {
                StandViewer(photos, viewerKey, brightness, lightOn, magnifierOn, actionsEnabled && !importing,
                    canvasState::toggleLight, { magnifierOn = !magnifierOn }, onLoadDetail,
                    { selectedKey = it; if (viewerKey != null) viewerKey = it },
                    { viewerKey = null }, ::removePhoto, onEdit)
            } else {
                ContactSheet(photos, selectedKey, canvasState, importing, importCompleted, importTotal,
                    actionsEnabled, lightOn, wallBackground, lamp, magnifierOn,
                    canvasState::toggleLight, { magnifierOn = !magnifierOn }, onLoadThumbnail,
                    { selectedKey = it; viewerKey = it }, onImport, onBack, onLoadWallDetails, backDescription, projectControls, onPhotoActions, onVisibleItems)
            }
        }
    }
}

@Composable
private fun ContactSheet(
    photos: List<ViewingPhoto>, selectedKey: String?, canvasState: FilmCanvasState,
    importing: Boolean, importCompleted: Int, importTotal: Int, actionsEnabled: Boolean,
    lightOn: Boolean, background: Color, lamp: Float, magnifierOn: Boolean,
    onLightChange: () -> Unit, onMagnifierChange: () -> Unit, onLoadThumbnail: (String) -> Unit,
    onOpen: (String) -> Unit, onImport: () -> Unit, onBack: () -> Unit,
    onLoadWallDetails: suspend (List<String>) -> Unit,
    backDescription: String,
    projectControls: @Composable (Boolean) -> Unit, onPhotoActions: ((String) -> Unit)?, onVisibleItems: (List<String>) -> Unit,
) {
    val ink = if (lightOn) StandColors.ink else StandColors.white
    val muted = if (lightOn) StandColors.muted else StandColors.soft
    Column(Modifier.fillMaxSize().background(background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, backDescription, tint = ink) }
            Text("观景台", Modifier.weight(1f).padding(start = 4.dp), color = ink, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onMagnifierChange, enabled = photos.isNotEmpty()) {
                Icon(Icons.Rounded.Search, if (magnifierOn) "关闭放大镜" else "打开放大镜",
                    tint = if (magnifierOn) StandColors.brown else muted)
            }
            IconToggleButton(checked = lightOn, onCheckedChange = { onLightChange() }) {
                Icon(if (lightOn) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    if (lightOn) "关闭灯光" else "打开灯光", tint = if (lightOn) StandColors.brown else StandColors.amber)
            }
        }
        projectControls(lightOn)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${photos.size} 张照片", Modifier.weight(1f), color = muted, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onImport, enabled = actionsEnabled && !importing,
                colors = ButtonDefaults.textButtonColors(contentColor = if (lightOn) StandColors.brown else StandColors.amber)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("导入照片")
            }
        }
        if (importing) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                Text("正在载入照片 · $importCompleted / $importTotal", color = ink, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { if (importTotal > 0) importCompleted.toFloat() / importTotal else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = StandColors.brown,
                    trackColor = if (lightOn) StandColors.line else StandColors.film)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (photos.isEmpty()) EmptyContactSheet(importing, ink, muted, actionsEnabled, onImport, Modifier.align(Alignment.Center))
            else PhotoWallCanvas(photos, selectedKey, canvasState, lamp, magnifierOn, onLoadThumbnail, onOpen,
                onLoadWallDetails, Modifier.fillMaxSize(), onActions = onPhotoActions, onVisible = onVisibleItems, enabled = actionsEnabled)
        }
        if (photos.isNotEmpty()) FilmCanvasToolbar(canvasState, lightOn)
    }
}

@Composable
private fun EmptyContactSheet(importing: Boolean, ink: Color, muted: Color, enabled: Boolean,
    onImport: () -> Unit, modifier: Modifier) {
    Column(modifier.widthIn(max = 320.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(44.dp), tint = muted)
        Text(if (importing) "正在载入照片" else "把照片，放在光下", color = ink, style = MaterialTheme.typography.titleLarge)
        Text("保留照片原始比例，完整显示。\n双指缩放、拖动浏览，轻触照片查看。", color = muted,
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = onImport, enabled = enabled && !importing,
            colors = ButtonDefaults.buttonColors(containerColor = StandColors.brown, contentColor = StandColors.white)) {
            Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp)); Text("导入照片")
        }
    }
}

@Composable
private fun StandViewer(
    photos: List<ViewingPhoto>, initialKey: String?, brightness: Float, lightOn: Boolean, magnifierOn: Boolean,
    actionsEnabled: Boolean, onLightChange: () -> Unit, onMagnifierChange: () -> Unit,
    onLoadDetail: (String) -> Unit, onSelected: (String) -> Unit, onClose: () -> Unit,
    onRemove: (String) -> Unit, onEdit: (String) -> Unit,
) {
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.key == initialKey }.coerceAtLeast(0), pageCount = { photos.size })
    val scope = rememberCoroutineScope()
    val thumbnails = rememberLazyListState()
    var showInfo by rememberSaveable { mutableStateOf(true) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var sharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentPage = pager.currentPage.coerceIn(0, photos.lastIndex.coerceAtLeast(0))
    val photo = photos.getOrNull(currentPage) ?: return
    val currentPhotos by rememberUpdatedState(photos)
    val currentSelected by rememberUpdatedState(onSelected)
    val currentLoad by rememberUpdatedState(onLoadDetail)
    var activeKey by remember { mutableStateOf(photo.key) }
    LaunchedEffect(pager, photos.map { it.key }) {
        val retained = photos.indexOfFirst { it.key == activeKey }
        pager.scrollToPage(if (retained >= 0) retained else currentPage.coerceAtMost(photos.lastIndex))
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            val settled = currentPhotos.getOrNull(index) ?: return@collect
            activeKey = settled.key; currentSelected(settled.key); zoom = 1f; pan = Offset.Zero
            currentPhotos.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(currentPhotos.size)).forEach { currentLoad(it.key) }
            thumbnails.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
    }
    BackHandler(enabled = zoom > 1.01f) { zoom = 1f; pan = Offset.Zero }
    fun navigate(index: Int) {
        zoom = 1f; pan = Offset.Zero
        scope.launch { pager.animateScrollToPage(index, animationSpec = tween(360, easing = FastOutSlowInEasing)) }
    }
    Column(Modifier.fillMaxSize().background(StandColors.night)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭沉浸式观片", tint = StandColors.white) }
            Text(number(currentPage + 1) + " / " + number(photos.size), Modifier.weight(1f).padding(horizontal = 8.dp),
                color = StandColors.soft, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onMagnifierChange) { Icon(Icons.Rounded.Search,
                if (magnifierOn) "关闭放大镜" else "打开放大镜", tint = if (magnifierOn) StandColors.amber else StandColors.white) }
            IconButton(onClick = onLightChange) { Icon(Icons.Rounded.LightMode,
                if (lightOn) "关闭灯光" else "打开灯光", tint = if (lightOn) StandColors.amber else StandColors.soft) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreHoriz, "照片操作", tint = StandColors.white) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("在暗房编辑") }, enabled = actionsEnabled,
                        onClick = { menuOpen = false; onEdit(photo.key) })
                    DropdownMenuItem(text = { Text(if (showInfo) "隐藏照片信息" else "显示照片信息") },
                        onClick = { menuOpen = false; showInfo = !showInfo })
                    DropdownMenuItem(text = { Text("分享预览图") }, enabled = !sharing && photo.detail != null, onClick = {
                        menuOpen = false
                        val bitmap = photo.detail ?: return@DropdownMenuItem
                        scope.launch {
                            sharing = true
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val directory = File(context.cacheDir, "lighttable").apply { mkdirs() }
                                    File(directory, "Theia-viewing-" + System.currentTimeMillis() + ".png").also { output ->
                                        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                    }
                                }
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png")
                                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享预览图"))
                            } catch (error: Exception) { shareError = "分享失败，请重试：" + error.message }
                            finally { sharing = false }
                        }
                    })
                    DropdownMenuItem(text = { Text("从观景台移除") }, enabled = actionsEnabled,
                        onClick = { menuOpen = false; onRemove(photo.key) })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            HorizontalPager(state = pager, key = { photos[it].key }, beyondViewportPageCount = 1,
                userScrollEnabled = zoom <= 1.01f && !magnifierOn, pageSpacing = 20.dp, modifier = Modifier.fillMaxSize()) { index ->
                val distance = abs((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                ViewerImage(photos[index], brightness, magnifierOn && index == pager.currentPage,
                    if (index == pager.currentPage) zoom else 1f, if (index == pager.currentPage) pan else Offset.Zero,
                    { zoom = it }, { pan = it }, { showInfo = !showInfo }, { onLoadDetail(photos[index].key) },
                    Modifier.fillMaxSize().graphicsLayer { scaleX = 1f - .035f * distance; scaleY = scaleX; alpha = 1f - .2f * distance })
            }
            androidx.compose.animation.AnimatedVisibility(showInfo && !magnifierOn, enter = fadeIn(tween(220)), exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomStart)) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, StandColors.night.copy(alpha = .92f))))
                    .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp)) {
                    Text(photo.name, color = StandColors.white, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (photo.caption.isNotBlank()) Text(photo.caption, color = StandColors.soft, style = MaterialTheme.typography.labelMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigate(currentPage - 1) }, enabled = currentPage > 0) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一帧", tint = if (currentPage > 0) StandColors.white else StandColors.muted)
            }
            Text("左右轻扫 · 双击放大", Modifier.weight(1f), color = StandColors.soft, style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { navigate(currentPage + 1) }, enabled = currentPage < photos.lastIndex) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "下一帧", tint = if (currentPage < photos.lastIndex) StandColors.white else StandColors.muted)
            }
        }
        LazyRow(state = thumbnails, modifier = Modifier.fillMaxWidth().height(74.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(photos, key = { _, item -> item.key }) { index, item ->
                Column(Modifier.width(64.dp).clickable { navigate(index) }.semantics(mergeDescendants = true) {
                    contentDescription = "第 " + (index + 1) + " 幅作品"; selected = index == currentPage
                }) {
                    val image = item.thumbnail
                    Box(Modifier.fillMaxWidth().height(44.dp).background(StandColors.film)
                        .then(if (index == currentPage) Modifier.border(1.5.dp, StandColors.amber, TheiaShapes.compact) else Modifier)) {
                        if (image != null && !image.isRecycled) Image(image.asImageBitmap(), null,
                            Modifier.fillMaxSize().graphicsLayer { alpha = if (index == currentPage) 1f else .55f }, contentScale = ContentScale.Crop)
                    }
                    Text(number(index + 1), color = if (index == currentPage) StandColors.amber else StandColors.soft, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    shareError?.let { AlertDialog(onDismissRequest = { shareError = null }, title = { Text("无法分享") }, text = { Text(it) },
        confirmButton = { TextButton(onClick = { shareError = null }) { Text("确定") } }) }
}

@Composable
private fun ViewerImage(photo: ViewingPhoto, brightness: Float, magnifierOn: Boolean, zoom: Float, pan: Offset,
    onZoom: (Float) -> Unit, onPan: (Offset) -> Unit, onTap: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var loupeBounds by remember { mutableStateOf(Rect.Zero) }
    val bitmap = photo.detail?.takeUnless { it.isRecycled }
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(pan)
    val animatedZoom by androidx.compose.animation.core.animateFloatAsState(zoom, tween(220), label = "viewer-zoom")
    val baseRect = bitmap?.let { imageRect(it, viewport, false) } ?: Rect.Zero
    val clamp by rememberUpdatedState<(Offset, Float) -> Offset>({ value, scale ->
        val x = max(0f, (baseRect.width * scale - viewport.width) / 2)
        val y = max(0f, (baseRect.height * scale - viewport.height) / 2)
        Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    })
    Box(modifier.onGloballyPositioned { viewport = it.boundsInRoot() }.clipToBounds()
        .pointerInput(photo.key) { detectTapGestures(onTap = { position ->
            if(!loupeBounds.contains(position+viewport.topLeft))onTap()
        }, onDoubleTap = { position ->
            if(!loupeBounds.contains(position+viewport.topLeft)){
                onZoom(if (currentZoom > 1.01f) 1f else 2.2f); onPan(Offset.Zero)
            }
        }) }
        .pointerInput(photo.key) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed = false)
                if(loupeBounds.contains(down.position+viewport.topLeft))return@awaitEachGesture
                do {
                    val event = awaitPointerEvent()
                    if(event.changes.any{it.isConsumed})return@awaitEachGesture
                    if (event.changes.count { it.pressed } > 1 || currentZoom > 1.01f) {
                        val next = (currentZoom * event.calculateZoom()).coerceIn(1f, 4f)
                        onZoom(next); onPan(clamp(currentPan + event.calculatePan(), next))
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), "沉浸照片 " + photo.name, Modifier.fillMaxSize().graphicsLayer {
                scaleX = animatedZoom; scaleY = animatedZoom; translationX = pan.x; translationY = pan.y; alpha = brightness
            }, contentScale = ContentScale.Fit)
            if (magnifierOn) {
                val center = viewport.center + pan
                val scaled = Rect(center.x - baseRect.width * animatedZoom / 2, center.y - baseRect.height * animatedZoom / 2,
                    center.x + baseRect.width * animatedZoom / 2, center.y + baseRect.height * animatedZoom / 2)
                // Loupe offsets are relative to the viewport, not to its centred child alignment.
                Box(Modifier.matchParentSize()) {
                    PhotoLoupe(viewport, listOf(LensTarget(bitmap, scaled, viewport)), brightness, StandColors.night,
                        onBoundsChange={loupeBounds=it})
                }
            }
        } else if (photo.error != null) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.BrokenImage, null, Modifier.size(36.dp), tint = StandColors.soft)
                Text("无法读取照片", color = StandColors.white, style = MaterialTheme.typography.titleMedium)
                Text(photo.error, color = StandColors.soft, style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = onRetry) { Text("重试读取照片", color = StandColors.amber) }
            }
        } else CircularProgressIndicator(color = StandColors.amber, modifier = Modifier.size(28.dp))
        if (zoom > 1.01f) TextButton(onClick = { onZoom(1f); onPan(Offset.Zero) }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Text("还原 " + (zoom * 100).roundToInt() + "%", color = StandColors.amber)
        }
    }
}

/** Spatial loupe: the pixel under its centre remains fixed at 2.5×. */
@Composable
internal fun PhotoLoupe(viewport: Rect, targets: List<LensTarget>, brightness: Float, background: Color,
    onBoundsChange: (Rect)->Unit = {}) {
    val latestBoundsChange by rememberUpdatedState(onBoundsChange)
    DisposableEffect(Unit){onDispose{latestBoundsChange(Rect.Zero)}}
    val diameter = with(LocalDensity.current) { 128.dp.toPx() }
    val radius = diameter / 2
    var centre by remember { mutableStateOf<Offset?>(null) }
    fun clamp(value: Offset): Offset = Offset(
        value.x.coerceIn(min(radius, viewport.width / 2), max(radius, viewport.width - radius)),
        value.y.coerceIn(min(radius, viewport.height / 2), max(radius, viewport.height - radius)))
    val initial = targets.firstOrNull()?.clipBounds?.center?.minus(viewport.topLeft) ?: Offset(viewport.width / 2, viewport.height / 2)
    val point = clamp(centre ?: initial)
    val rootPoint = point + viewport.topLeft
    val target = targets.lastOrNull { candidate ->
        val bounds = candidate.imageBounds
        candidate.clipBounds.contains(rootPoint) && WallFrame("", bounds, candidate.rotation).contains(rootPoint)
    }
    Box(Modifier.offset { IntOffset((point.x - radius).roundToInt(), (point.y - radius).roundToInt()) }
        .onGloballyPositioned { latestBoundsChange(it.boundsInRoot()) }
        .size(128.dp).shadow(12.dp, CircleShape).clip(CircleShape).background(background).border(3.dp, StandColors.amber, CircleShape)
        .semantics { contentDescription = "拖动放大镜"; stateDescription = "2.5 倍放大" }
        .pointerInput(viewport.size) {
            detectDragGestures(onDragStart = { if (centre == null) centre = point }) { change, amount ->
                change.consume(); centre = clamp((centre ?: point) + amount)
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            if (target != null && !target.bitmap.isRecycled) {
                val rect = target.imageBounds
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.save()
                canvas.translate(size.width / 2, size.height / 2)
                canvas.scale(2.5f, 2.5f)
                canvas.translate(-rootPoint.x, -rootPoint.y)
                canvas.rotate(target.rotation, rect.center.x, rect.center.y)
                canvas.drawBitmap(target.bitmap, null, android.graphics.RectF(rect.left, rect.top, rect.right, rect.bottom),
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                canvas.restoreToCount(saved)
                drawRect(Color.Black.copy(alpha = 1 - brightness))
            }
        }
        Text("2.5×", color = StandColors.white, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter)
            .padding(bottom = 10.dp).background(StandColors.night.copy(alpha = .8f), TheiaShapes.badge).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun imageRect(bitmap: Bitmap, frame: Rect, crop: Boolean): Rect {
    val horizontal = frame.width / bitmap.width.coerceAtLeast(1); val vertical = frame.height / bitmap.height.coerceAtLeast(1)
    val scale = if (crop) max(horizontal, vertical) else min(horizontal, vertical)
    val width = bitmap.width * scale; val height = bitmap.height * scale
    return Rect(frame.center.x - width / 2, frame.center.y - height / 2, frame.center.x + width / 2, frame.center.y + height / 2)
}
private fun number(index: Int): String = index.toString().padStart(2, '0')
