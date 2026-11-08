package com.spektralab.android

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Materials from the approved contact-sheet design. */
private object StandColors {
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

private data class LensTarget(val bitmap: Bitmap, val imageBounds: Rect, val clipBounds: Rect)

@Composable
internal fun ViewingStand(
    photos: List<ViewingPhoto>, importing: Boolean, importCompleted: Int, importTotal: Int,
    actionsEnabled: Boolean, onLoadThumbnail: (String) -> Unit, onLoadDetail: (String) -> Unit,
    onImport: () -> Unit, onRemove: (String) -> Unit, onEdit: (String) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    var lightOn by rememberSaveable { mutableStateOf(true) }
    var magnifierOn by rememberSaveable { mutableStateOf(false) }
    var viewerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val wallState = rememberLazyListState()
    val revealedKeys = remember { mutableSetOf<String>() }
    val wallBackground by animateColorAsState(if (lightOn) StandColors.paper else StandColors.night,
        tween(360, easing = FastOutSlowInEasing), label = "light-table")
    val brightness by androidx.compose.animation.core.animateFloatAsState(if (lightOn) 1f else .5f, tween(360), label = "light-output")
    val view = LocalView.current
    DisposableEffect(lightOn, viewerKey != null) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = lightOn && viewerKey == null
        controller?.isAppearanceLightNavigationBars = lightOn && viewerKey == null
        onDispose {
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
                    { lightOn = !lightOn }, { magnifierOn = !magnifierOn }, onLoadDetail,
                    { selectedKey = it; if (viewerKey != null) viewerKey = it },
                    { viewerKey = null }, ::removePhoto, onEdit)
            } else {
                ContactSheet(photos, selectedKey, wallState, revealedKeys, importing, importCompleted, importTotal,
                    actionsEnabled, lightOn, wallBackground, brightness, magnifierOn,
                    { lightOn = !lightOn }, { magnifierOn = !magnifierOn }, onLoadThumbnail,
                    { selectedKey = it; viewerKey = it }, onImport, onBack)
            }
        }
    }
}

@Composable
private fun ContactSheet(
    photos: List<ViewingPhoto>, selectedKey: String?, listState: LazyListState, revealedKeys: MutableSet<String>,
    importing: Boolean, importCompleted: Int, importTotal: Int, actionsEnabled: Boolean,
    lightOn: Boolean, background: Color, brightness: Float, magnifierOn: Boolean,
    onLightChange: () -> Unit, onMagnifierChange: () -> Unit, onLoadThumbnail: (String) -> Unit,
    onOpen: (String) -> Unit, onImport: () -> Unit, onBack: () -> Unit,
) {
    val ink = if (lightOn) StandColors.ink else StandColors.white
    val muted = if (lightOn) StandColors.muted else StandColors.soft
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val targets = remember { mutableStateMapOf<String, LensTarget>() }
    Column(Modifier.fillMaxSize().background(background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回暗房", tint = ink) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("观景台", color = ink, style = MaterialTheme.typography.headlineSmall)
                Text("CONTACT SHEET", color = muted, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onMagnifierChange, enabled = photos.isNotEmpty()) {
                Icon(Icons.Rounded.Search, if (magnifierOn) "关闭放大镜" else "打开放大镜",
                    tint = if (magnifierOn) StandColors.brown else muted)
            }
            IconButton(onClick = onLightChange) {
                Icon(Icons.Rounded.LightMode, if (lightOn) "关闭灯光" else "打开灯光",
                    tint = if (lightOn) StandColors.brown else StandColors.amber)
            }
        }
        HorizontalDivider(color = if (lightOn) StandColors.line else Color(0xFF393832))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(photos.size.toString() + " 帧 · 私人胶片集", Modifier.weight(1f), color = muted, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onImport, enabled = actionsEnabled && !importing,
                colors = ButtonDefaults.textButtonColors(contentColor = if (lightOn) StandColors.brown else StandColors.amber)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入照片")
            }
        }
        AnimatedVisibility(importing, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("正在装入胶片 · $importCompleted / $importTotal", color = ink, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { if (importTotal > 0) importCompleted.toFloat() / importTotal else 0f },
                    modifier = Modifier.fillMaxWidth().height(3.dp), color = if (lightOn) StandColors.brown else StandColors.amber,
                    trackColor = if (lightOn) StandColors.line else Color(0xFF3A3832))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().onGloballyPositioned { viewport = it.boundsInRoot() }) {
            val columns = when {
                maxWidth >= 1000.dp -> 6
                maxWidth >= 720.dp -> 5
                maxWidth >= 540.dp -> 4
                maxWidth < 330.dp -> 2
                else -> 3
            }
            if (photos.isEmpty()) {
                EmptyContactSheet(importing, ink, muted, actionsEnabled, onImport, Modifier.align(Alignment.Center))
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().semantics { contentDescription = "胶片墙" },
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(photos.chunked(columns), key = { _, row -> row.first().key }) { rowIndex, row ->
                        Column {
                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("STRIP " + number(rowIndex + 1), color = muted, style = MaterialTheme.typography.labelSmall)
                                Text(number(rowIndex * columns + 1) + " — " + number(rowIndex * columns + row.size),
                                    color = muted, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(Modifier.fillMaxWidth().clip(SpektraShapes.control).background(StandColors.film).padding(vertical = 3.dp)) {
                                Sprockets(background)
                                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEachIndexed { column, photo ->
                                        ContactFrame(photo, rowIndex * columns + column, photo.key == selectedKey, brightness,
                                            revealedKeys, onLoadThumbnail, { onOpen(photo.key) },
                                            { target -> if (target == null) targets.remove(photo.key) else targets[photo.key] = target },
                                            Modifier.weight(1f))
                                    }
                                    repeat(columns - row.size) {
                                        Box(Modifier.weight(1f).aspectRatio(.98f).clip(SpektraShapes.compact).background(Color(0xFF1D1C19)))
                                    }
                                }
                                Sprockets(background)
                            }
                        }
                    }
                }
            }
            if (magnifierOn && photos.isNotEmpty()) {
                val liveKeys = photos.map { it.key }.toSet()
                PhotoLoupe(viewport, targets.filterKeys { it in liveKeys }.values.toList(), brightness, background)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TouchApp, null, Modifier.size(16.dp), tint = muted)
            Text(if (magnifierOn) "拖动放大镜，查看胶片细节" else "轻触一帧，进入沉浸观片",
                Modifier.padding(start = 8.dp), color = muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyContactSheet(importing: Boolean, ink: Color, muted: Color, enabled: Boolean,
    onImport: () -> Unit, modifier: Modifier) {
    Column(modifier.widthIn(max = 320.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(44.dp), tint = muted)
        Text(if (importing) "正在装入胶片" else "让照片，连成一卷", color = ink, style = MaterialTheme.typography.titleLarge)
        Text("导入照片后，胶片会横向排列并自动换行。\n点开一帧，慢慢看。", color = muted,
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = onImport, enabled = enabled && !importing,
            colors = ButtonDefaults.buttonColors(containerColor = StandColors.brown, contentColor = StandColors.white)) {
            Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp)); Text("导入照片")
        }
    }
}

@Composable
private fun Sprockets(tableColor: Color) {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val width = 6.dp.toPx()
        val count = (size.width / 13.dp.toPx()).toInt().coerceAtLeast(1)
        val gap = (size.width - count * width) / (count + 1)
        repeat(count) { index ->
            drawRoundRect(tableColor, Offset(gap + index * (width + gap), 4.dp.toPx()),
                Size(width, 5.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
    }
}

@Composable
private fun ContactFrame(photo: ViewingPhoto, index: Int, selected: Boolean, brightness: Float,
    revealedKeys: MutableSet<String>, onLoad: (String) -> Unit, onOpen: () -> Unit,
    onTarget: (LensTarget?) -> Unit, modifier: Modifier) {
    val reveal = remember(photo.key) { Animatable(if (photo.key in revealedKeys) 1f else 0f) }
    var bounds by remember(photo.key) { mutableStateOf(Rect.Zero) }
    val bitmap = photo.thumbnail?.takeUnless { it.isRecycled }
    LaunchedEffect(photo.key) { onLoad(photo.key) }
    LaunchedEffect(photo.key, bitmap != null) {
        if (bitmap != null && revealedKeys.add(photo.key)) {
            delay((index % 3 * 45L + index / 3 * 55L).coerceAtMost(330))
            reveal.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        }
    }
    DisposableEffect(photo.key, bitmap, bounds) {
        if (bitmap != null && bounds.width > 0 && bounds.height > 0)
            onTarget(LensTarget(bitmap, imageRect(bitmap, bounds, true), bounds))
        onDispose { onTarget(null) }
    }
    Column(modifier.clickable(onClickLabel = "沉浸观看", onClick = onOpen).semantics(mergeDescendants = true) {
        contentDescription = "查看第 " + (index + 1) + " 帧 " + photo.name
        this.selected = selected
        stateDescription = if (selected) "当前照片" else "轻触沉浸观看"
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(SpektraShapes.compact).background(Color(0xFF39372F))
            .onGloballyPositioned { bounds = it.boundsInRoot() }) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
                alpha = reveal.value * brightness
                translationX = (1 - reveal.value) * 18.dp.toPx()
                scaleX = 1.035f - reveal.value * .035f; scaleY = scaleX
            }, contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.Image, null, Modifier.align(Alignment.Center).size(24.dp), tint = StandColors.soft)
            if (selected) Box(Modifier.fillMaxSize().border(1.5.dp, StandColors.amber, SpektraShapes.compact))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 22.dp).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number(index + 1), color = if (selected) StandColors.amber else StandColors.soft,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            if (selected) Text("当前", color = StandColors.amber, style = MaterialTheme.typography.labelSmall)
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
                                    File(directory, "SpektraLab-viewing-" + System.currentTimeMillis() + ".png").also { output ->
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
                        .then(if (index == currentPage) Modifier.border(1.5.dp, StandColors.amber, SpektraShapes.compact) else Modifier)) {
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
        .pointerInput(photo.key) { detectTapGestures(onTap = { onTap() }, onDoubleTap = {
            onZoom(if (currentZoom > 1.01f) 1f else 2.2f); onPan(Offset.Zero)
        }) }
        .pointerInput(photo.key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
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
                PhotoLoupe(viewport, listOf(LensTarget(bitmap, scaled, viewport)), brightness, StandColors.night)
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
private fun PhotoLoupe(viewport: Rect, targets: List<LensTarget>, brightness: Float, background: Color) {
    val diameter = with(LocalDensity.current) { 128.dp.toPx() }
    val radius = diameter / 2
    var centre by remember { mutableStateOf<Offset?>(null) }
    fun clamp(value: Offset): Offset = Offset(
        value.x.coerceIn(min(radius, viewport.width / 2), max(radius, viewport.width - radius)),
        value.y.coerceIn(min(radius, viewport.height / 2), max(radius, viewport.height - radius)))
    val initial = targets.firstOrNull()?.clipBounds?.center?.minus(viewport.topLeft) ?: Offset(viewport.width / 2, viewport.height / 2)
    val point = clamp(centre ?: initial)
    val rootPoint = point + viewport.topLeft
    val target = targets.lastOrNull { it.clipBounds.contains(rootPoint) && it.imageBounds.contains(rootPoint) }
    Box(Modifier.offset { IntOffset((point.x - radius).roundToInt(), (point.y - radius).roundToInt()) }
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
                val width = rect.width * 2.5f; val height = rect.height * 2.5f
                val x = (rootPoint.x - rect.left) / rect.width; val y = (rootPoint.y - rect.top) / rect.height
                drawImage(target.bitmap.asImageBitmap(),
                    dstOffset = IntOffset((size.width / 2 - x * width).roundToInt(), (size.height / 2 - y * height).roundToInt()),
                    dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)))
                drawRect(Color.Black.copy(alpha = 1 - brightness))
            }
        }
        Text("2.5×", color = StandColors.white, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter)
            .padding(bottom = 10.dp).background(StandColors.night.copy(alpha = .8f), SpektraShapes.badge).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun imageRect(bitmap: Bitmap, frame: Rect, crop: Boolean): Rect {
    val horizontal = frame.width / bitmap.width.coerceAtLeast(1); val vertical = frame.height / bitmap.height.coerceAtLeast(1)
    val scale = if (crop) max(horizontal, vertical) else min(horizontal, vertical)
    val width = bitmap.width * scale; val height = bitmap.height * scale
    return Rect(frame.center.x - width / 2, frame.center.y - height / 2, frame.center.x + width / 2, frame.center.y + height / 2)
}
private fun number(index: Int): String = index.toString().padStart(2, '0')
