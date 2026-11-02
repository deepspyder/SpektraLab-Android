package com.spektralab.android

import android.os.Bundle
import com.spektralab.android.engine.StockCatalog
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.engine.Histogram
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// 胶片画幅 → 帧长边毫米数（引擎用它缩放颗粒/光晕微米量）。
private val FILM_FORMAT_MM = linkedMapOf(
    "135 (36mm)" to 36f,
    "645 (56mm)" to 56f,
    "6×6 (56mm)" to 56f,
    "6×7 (69.5mm)" to 69.5f,
    "6×8 (76mm)" to 76f,
    "6×9 (84mm)" to 84f,
    "6×12 (112mm)" to 112f,
    "6×17 (168mm)" to 168f,
)

class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setOnExitAnimationListener { splashView ->
            splashView.view.animate()
                .alpha(0f)
                .scaleX(1.035f)
                .scaleY(1.035f)
                .setDuration(190L)
                .withEndAction { splashView.remove() }
                .start()
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.rgb(18, 19, 21)
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        vm.restoreUri(savedInstanceState?.getString("currentUri"))
        setContent {
            SpektraTheme {
                var minimumSplashElapsed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(1200L)
                    minimumSplashElapsed = true
                }
                if (vm.errorMessage == null &&
                    (!minimumSplashElapsed || (vm.engine == null && vm.busy))) {
                    LaunchSplash()
                } else {
                    EditorScreen(vm)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentUri", vm.currentUri?.toString())
    }
}

/**
 * Compose hand-off screen shown while the native engine is being prepared.
 * Android's system splash has a very small branding slot, so the product copy
 * lives here where its width, hierarchy, and line breaks are under our control.
 */
@Composable
private fun LaunchSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_spektra_mark),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(78.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "SpektraLab",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "让记忆拥有\n真实的颗粒感",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 21.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = "MEMORIES IN EVERY GRAIN",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.1.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

private enum class EditorTab(val label: String, val icon: ImageVector) {
    Shoot("胶片", Icons.Filled.CameraAlt),
    Print("暗房", Icons.Filled.Print),
    Grade("调色", Icons.Filled.Tune),
    Export("导出", Icons.Filled.FileDownload)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(vm: EditorViewModel) {
    var addToStage by remember { mutableStateOf(false) }
    var viewingStandVisible by rememberSaveable { mutableStateOf(false) }
    var viewingLightOn by rememberSaveable { mutableStateOf(true) }
    var viewingSelectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = vm.isCropping || viewingStandVisible) {
        if (vm.isCropping) vm.cancelCrop() else viewingStandVisible = false
    }
    val viewingPhotos = vm.viewingPhotos
    val viewingPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 24)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            vm.loadViewingPhotos(uris)
            viewingSelectedKey = uris.first().toString()
        }
    }
    fun openViewingStand() {
        viewingSelectedKey = viewingPhotos.firstOrNull()?.first
        viewingStandVisible = true
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            if (addToStage) vm.addPhotos(uris) else vm.openPhotos(uris)
        }
        addToStage = false
    }

    var showSaveRecipeDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var recipeName by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(EditorTab.Shoot) }
    var comparing by rememberSaveable(vm.currentUri?.toString()) { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (!vm.isCropping) TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_spektra_mark),
                                    contentDescription = null,
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Column(Modifier.padding(start = 10.dp)) {
                                Text("SpektraLab", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                if (vm.dirty) Text("已修改", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { addToStage = false; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "导入照片")
                        }
                        Spacer(Modifier.size(8.dp))
                        Box {
                            IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                            }
                            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("撤销") }, leadingIcon = { Icon(Icons.Filled.Undo, null) },
                                    enabled = vm.canUndo && !vm.busy && !vm.isExporting,
                                    onClick = { showMoreMenu = false; vm.undo() }
                                )
                                DropdownMenuItem(
                                    text = { Text("重做") }, leadingIcon = { Icon(Icons.Filled.Redo, null) },
                                    enabled = vm.canRedo && !vm.busy && !vm.isExporting,
                                    onClick = { showMoreMenu = false; vm.redo() }
                                )
                                DropdownMenuItem(
                                    text = { Text("重置") }, enabled = vm.hasPhoto,
                                    onClick = { showMoreMenu = false; vm.resetAll() }
                                )
                                DropdownMenuItem(
                                    text = { Text("测试图") },
                                    onClick = { showMoreMenu = false; vm.openTestImage() }
                                )
                                DropdownMenuItem(
                                    text = { Text("观景台") },
                                    onClick = { showMoreMenu = false; openViewingStand() }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ), expandedHeight = 56.dp
                )
            },
            bottomBar = { if (!expanded && !vm.isCropping) EditorNavigationBar(selectedTab, onSelect = { selectedTab = it }) }
        ) { contentPadding ->
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(contentPadding).imePadding()
            ) {
                val landscape = maxWidth > maxHeight
                if (vm.isCropping && vm.sourceBitmap != null) {
                    CropWorkspace(vm, Modifier.fillMaxSize())
                } else when {
                    expanded -> Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorNavigationRail(selectedTab, onSelect = { selectedTab = it })
                        PhotoCanvas(vm, comparing, { comparing = it }, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.widthIn(min = 340.dp, max = 440.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                            PhotoStage(vm, onAddPhotos = { addToStage = true; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        EditorPanel(vm, selectedTab, Modifier.weight(1f), onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    landscape -> Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoCanvas(vm, comparing, { comparing = it }, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            Modifier.weight(1.08f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                            PhotoStage(vm, onAddPhotos = { addToStage = true; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        EditorPanel(vm, selectedTab, Modifier.weight(1f), onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoCanvas(vm, comparing, { comparing = it }, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            Modifier.weight(0.92f).fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(8.dp)))
                        PhotoStage(vm, onAddPhotos = { addToStage = true; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        EditorPanel(vm, selectedTab, Modifier.weight(1f).fillMaxWidth().heightIn(min = 180.dp).clip(RoundedCornerShape(8.dp)),
                            onSaveRecipe = { showSaveRecipeDialog = true })
                    }
                }
            }
        }

        if (viewingStandVisible) {
            ViewingStand(
                photos = viewingPhotos,
                selectedKey = viewingSelectedKey,
                lightOn = viewingLightOn,
                onLightChange = { viewingLightOn = it },
                onSelect = { viewingSelectedKey = it },
                onRemove = { key ->
                    val index = viewingPhotos.indexOfFirst { it.first == key }
                    val remaining = viewingPhotos.filterNot { it.first == key }
                    vm.removeViewingPhoto(key)
                    if (viewingSelectedKey == key) {
                        viewingSelectedKey = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.first
                    }
                },
                onPickPhotos = { viewingPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onClose = { viewingStandVisible = false },
                modifier = Modifier.fillMaxSize().zIndex(4f),
            )
        }

        if (vm.busy || vm.isExporting || vm.isBatchExporting) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .zIndex(2f),
                contentAlignment = Alignment.Center
            ) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Column(Modifier.widthIn(min = 280.dp, max = 360.dp).padding(horizontal = 22.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val pct = vm.renderProgress
                        val progressAccent = MaterialTheme.colorScheme.primary
                        val progressTrack = MaterialTheme.colorScheme.outlineVariant
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.renderStage.ifBlank { if (vm.isBatchExporting) "逐张导出照片" else "处理中" },
                                Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                            if (pct != null) Text("${pct.roundToInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        if (pct != null) ExportProgressBar(pct / 100f, progressAccent, progressTrack)
                        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = progressAccent, trackColor = progressTrack)
                        if (vm.isExporting || vm.isBatchExporting) {
                            FilledTonalButton(onClick = { vm.cancelRender() },
                                enabled = vm.renderStage != "正在取消…" && !(vm.isExporting && (pct ?: 0f) >= 96f),
                                modifier = Modifier.fillMaxWidth()) { Text(if (vm.renderStage == "正在取消…") "正在取消…" else "取消导出") }
                        }
                    }
                }
            }
        }

    }

    // ---- 保存配方对话框 ----
    if (showSaveRecipeDialog) {
        AlertDialog(
            onDismissRequest = { showSaveRecipeDialog = false },
            title = { Text("保存配方") },
            text = {
                OutlinedTextField(value = recipeName, onValueChange = { recipeName = it }, label = { Text("配方名") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveRecipe(recipeName)
                    recipeName = ""
                    showSaveRecipeDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveRecipeDialog = false }) { Text("取消") } }
        )
    }

    // ---- 错误弹窗 ----
    vm.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.dismissError() },
            title = { Text("出错了") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.dismissError() }) { Text("确定") } }
        )
    }
}

@Composable
private fun EditorNavigationBar(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        EditorTab.entries.forEach { tab ->
            NavigationBarItem(
                modifier = Modifier.heightIn(min = 64.dp),
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun ExportProgressBar(value: Float, color: Color, track: Color) {
    val animated by animateFloatAsState(value.coerceIn(0f, 1f), animationSpec = tween(160), label = "export-progress")
    Canvas(Modifier.fillMaxWidth().height(4.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
        contentDescription = "导出进度"
    }) {
        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
        if (animated > 0f) drawLine(color, Offset(0f, size.height / 2), Offset(size.width * animated, size.height / 2), size.height, StrokeCap.Round)
    }
}

@Composable
private fun EditorNavigationRail(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        EditorTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CropWorkspace(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val source = vm.sourceBitmap ?: return
    Column(modifier.background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { vm.cancelCrop() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("取消")
            }
            Text("裁剪照片", Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = { vm.finishCrop() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("完成裁剪", color = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black)
                .semantics { contentDescription = "照片预览" },
            contentAlignment = Alignment.Center
        ) {
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
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OptionsDropdown(
                "比例", listOf("原始", "1:1", "4:3", "3:2", "16:9"), vm.cropAspect,
                Modifier.weight(1f)
            ) { aspect ->
                val crop = computeCenteredCrop(aspect, source.width, source.height)
                vm.cropAspect = aspect
                vm.params.cropX = crop[0]; vm.params.cropY = crop[1]
                vm.params.cropW = crop[2]; vm.params.cropH = crop[3]
            }
            TextButton(onClick = { vm.resetCropSelection() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("重置")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PhotoCanvas(vm: EditorViewModel, comparing: Boolean, onComparingChange: (Boolean) -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val canCompare = vm.sourceBitmap != null && vm.rendered != null && !vm.isCropping
    Box(
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
                Icon(Icons.Filled.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                Text("导入照片", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Button(onClick = onImport) { Text("选择照片") }
            }
        }
        if (!comparing || !canCompare) {
            vm.histogram?.let { HistogramView(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)) }
        }
        if (source != null && !vm.isCropping) {
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                IconButton(
                    onClick = { vm.beginCrop() },
                    enabled = !vm.params.overscanActive,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Filled.Crop, contentDescription = "裁剪照片",
                        tint = if (vm.params.overscanActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (canCompare) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(if (comparing) "关闭前后对比" else "前后对比") } }, state = rememberTooltipState()) {
                IconToggleButton(checked = comparing, onCheckedChange = onComparingChange,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), RoundedCornerShape(6.dp))) {
                    Icon(Icons.Filled.Compare, contentDescription = if (comparing) "关闭前后对比" else "前后对比",
                        tint = if (comparing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            }
        }
    }
}

@Composable
private fun EditorPanel(vm: EditorViewModel, selectedTab: EditorTab, modifier: Modifier = Modifier, onSaveRecipe: () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedTab.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                if (vm.dirty) Text("已修改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(remember(selectedTab) { ScrollState(0) })
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                when (selectedTab) {
                    EditorTab.Shoot -> ShootPanel(vm)
                    EditorTab.Print -> PrintPanel(vm)
                    EditorTab.Grade -> GradePanel(vm)
                    EditorTab.Export -> ExportPanel(vm, onSaveRecipe)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ============================================================
//  四个 Tab 面板
// ============================================================

@Composable
private fun ShootPanel(vm: EditorViewModel) {
    SectionLabel("胶片与相纸")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StockDropdown("胶片", vm.films, vm.params.filmId, Modifier.fillMaxWidth(), StockCatalog.filmGroups(vm.films)) {
            vm.selectFilm(it.id)
        }
        StockDropdown("相纸", vm.papers.filter { StockCatalog.canPrint(vm.params.filmId, it.id) }, vm.params.paperId, Modifier.fillMaxWidth()) {
            vm.selectPaper(it.id)
        }
    }
    OptionsDropdown(
        "胶片画幅",
        FILM_FORMAT_MM.keys.toList(),
        FILM_FORMAT_MM.entries.firstOrNull { it.value == vm.params.filmFormatMm }?.key ?: "135 (36mm)",
        Modifier.fillMaxWidth()
    ) { label ->
        FILM_FORMAT_MM[label]?.let { vm.params.filmFormatMm = it }
        vm.scheduleRender()
    }

    ActionButton("配光 (Solve)", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.solve() })

    SectionLabel("曝光")
    SliderRow("曝光补偿", vm.params.exposureEv, -8f..8f, "%+.1f EV") { vm.params.exposureEv = it; vm.scheduleRender() }
    OptionsDropdown("测光方法", listOf("balanced", "center", "protect_highlights", "protect_shadows"), vm.params.autoExposureMethod, Modifier.fillMaxWidth()) {
        vm.params.autoExposureMethod = it; vm.scheduleRender()
    }
    ToggleRow("自动测光", vm.params.autoExposure) { vm.params.autoExposure = it; vm.scheduleRender() }

    SectionLabel("宽容度")
    ActionButton("测量宽容度", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.measureLatitude() })
    vm.latitudeReadout?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (vm.latitudeCanApply) {
        ActionButton("应用场景置位", onClick = { vm.applyLatitude() })
    }

    if (vm.currentIsRaw) {
        SectionLabel("相机白平衡 (RAW)")
        ToggleRow("镜头校正", vm.lensCorrectionActive && vm.matchedLens != null) { vm.toggleLensCorrection() }
        vm.matchedLens?.let { Text("已匹配镜头: $it", style = MaterialTheme.typography.bodySmall) }
            ?: Text("未找到镜头配置，保留原始画面", style = MaterialTheme.typography.bodySmall)
        ToggleRow("自定义白平衡", vm.params.cameraWbCustom) { vm.params.cameraWbCustom = it; vm.scheduleCameraWb() }
        if (vm.params.cameraWbCustom) {
            SliderRow("色温 K", vm.params.cameraWbKelvin, 2000f..12000f, "%.0f K") { vm.params.cameraWbKelvin = it; vm.scheduleCameraWb() }
            SliderRow("色调", vm.params.cameraWbTint, -150f..150f, "%+.0f") { vm.params.cameraWbTint = it; vm.scheduleCameraWb() }
            ActionButton("恢复 as-shot", onClick = { vm.resetCameraWb() })
        }
    }

    SectionLabel("镜头滤镜")
    OptionsDropdown("镜头滤镜", listOf("无", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58"), vm.params.cameraFilter.ifEmpty { "无" }, Modifier.fillMaxWidth()) {
        vm.params.cameraFilter = if (it == "无") "" else it; vm.scheduleRender()
    }
}

@Composable
private fun PrintPanel(vm: EditorViewModel) {
    SectionLabel("放大机")
    SliderRow("打印曝光(越小越亮)", vm.params.printExposure, 0.25f..4f, "%.2f") { vm.params.printExposure = it; vm.scheduleRender() }
    SliderRow("滤镜 Y(黄/蓝)", vm.params.yFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f) { vm.params.yFilterShift = it; vm.scheduleRender() }
    SliderRow("滤镜 M(品红/绿)", vm.params.mFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f) { vm.params.mFilterShift = it; vm.scheduleRender() }

    SectionLabel("胶片效果")
    ToggleRow("颗粒", vm.params.grainActive) { vm.params.grainActive = it; vm.scheduleRender() }
    SliderRow("颗粒强度", vm.params.grainAmount, 0f..2f, "%.2f×") { vm.params.grainAmount = it; vm.scheduleRender() }
    ToggleRow("分层颗粒", vm.params.grainLayered) { vm.params.grainLayered = it; vm.scheduleRender() }
    ToggleRow("光晕", vm.params.halationActive) { vm.params.halationActive = it; vm.scheduleRender() }
    SliderRow("光晕强度", vm.params.halationAmount, 0f..4f, "%.1f") { vm.params.halationAmount = it; vm.scheduleRender() }
    SliderRow("光晕散射", vm.params.halationScatterAmount, 0f..1f, "%.2f") { vm.params.halationScatterAmount = it; vm.scheduleRender() }
    SliderRow("高光增强", vm.params.halationBoostEv, 0f..8f, "%.1f EV") { vm.params.halationBoostEv = it; vm.scheduleRender() }
    ToggleRow("保留抗晕层", !vm.params.antihalationRemoved) { vm.params.antihalationRemoved = !it; vm.scheduleRender() }
    ToggleRow("DIR 成色剂耦合", vm.params.dirCouplersActive) { vm.params.dirCouplersActive = it; vm.scheduleRender() }
    SliderRow("DIR 强度", vm.params.dirCouplersAmount, 0f..1.5f, "%.1f") { vm.params.dirCouplersAmount = it; vm.scheduleRender() }
    ToggleRow("纸面光晕", vm.params.glareActive) { vm.params.glareActive = it; vm.scheduleRender() }
    SliderRow("纸面光晕强度", vm.params.glareAmount, 0f..30f, "%.1f") { vm.params.glareAmount = it; vm.scheduleRender() }
    SliderRow("预闪", vm.params.preflashExposure, 0f..0.03f, "%.2f×") { vm.params.preflashExposure = it; vm.scheduleRender() }
    ToggleRow("扩展动态范围", vm.params.extendedDynamicRange) { vm.params.extendedDynamicRange = it; vm.scheduleRender() }

    SectionLabel("片边")
    ToggleRow("片边(整条胶片)", vm.params.overscanActive) { vm.params.overscanActive = it; vm.scheduleRender() }
    if (vm.params.overscanActive) {
        OptionsDropdown("画幅", listOf("135", "135_half", "135_xpan", "120_645", "120_6x6", "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"), vm.params.overscanFormat, Modifier.fillMaxWidth()) {
            vm.params.overscanFormat = it; vm.scheduleRender()
        }
        OptionsDropdown("片边模式", listOf("strip", "filed"), vm.params.overscanMode, Modifier.fillMaxWidth()) { vm.params.overscanMode = it; vm.scheduleRender() }
        OptionsDropdown("门形状", listOf("auto", "square", "rounded", "eared", "shouldered", "kicked"), vm.params.overscanGate, Modifier.fillMaxWidth()) { vm.params.overscanGate = it; vm.scheduleRender() }
        OptionsDropdown("齿孔", listOf("white", "black"), vm.params.overscanHoles, Modifier.fillMaxWidth()) { vm.params.overscanHoles = it; vm.scheduleRender() }
        OptionsDropdown("边缘外", listOf("none", "black", "open"), vm.params.overscanCarrier, Modifier.fillMaxWidth()) { vm.params.overscanCarrier = it; vm.scheduleRender() }
        SliderRow("边缘雾化", vm.params.overscanFog, 0f..4f, "%.1f") { vm.params.overscanFog = it; vm.scheduleRender() }
        SliderRow("漏光", vm.params.overscanLeaks, 0f..4f, "%.1f") { vm.params.overscanLeaks = it; vm.scheduleRender() }
        SliderRow("光圈(门边光晕)", vm.params.overscanFNumber, 0f..64f, "%.1f") { vm.params.overscanFNumber = it; vm.scheduleRender() }
        SliderRow("帧号", vm.params.overscanFrameNumber.toFloat(), 0f..99f, "%.0f") { vm.params.overscanFrameNumber = it.roundToInt(); vm.scheduleRender() }
        Text(
            "片边文字（自动）: ${StockCatalog.edgeText(vm.params.filmId, vm.params.overscanFormat)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        SliderRow("相机种子", vm.params.overscanCameraSeed.toFloat(), 0f..999f, "%.0f") { vm.params.overscanCameraSeed = it.roundToInt(); vm.scheduleRender() }
        SliderRow("过片种子", vm.params.overscanFrameSeed.toFloat(), 0f..999f, "%.0f") { vm.params.overscanFrameSeed = it.roundToInt(); vm.scheduleRender() }
    }

    SectionLabel("日期后背")
    ToggleRow("日期后背", vm.params.dateImprintActive) {
        vm.params.dateImprintActive = it
        if (it && vm.params.dateImprintText.isEmpty()) vm.params.dateImprintText = vm.currentDateText()
        vm.scheduleRender()
    }
    if (vm.params.dateImprintActive) {
        OptionsDropdown("日期样式", listOf("lcd", "dots", "data"), vm.params.dateImprintStyle, Modifier.fillMaxWidth()) {
            vm.params.dateImprintStyle = it
            if (it == "data") vm.params.overscanActive = true
            vm.scheduleRender()
        }
        OptionsDropdown("位置", listOf("frame", "rebate"), vm.params.dateImprintPlacement, Modifier.fillMaxWidth()) { vm.params.dateImprintPlacement = it; vm.scheduleRender() }
        OptionsDropdown("角落", listOf("br", "bl", "tr", "tl"), vm.params.dateImprintCorner, Modifier.fillMaxWidth()) { vm.params.dateImprintCorner = it; vm.scheduleRender() }
        SliderRow("大小", vm.params.dateImprintSize, 0.4f..3f, "%.2f") { vm.params.dateImprintSize = it; vm.scheduleRender() }
        SliderRow("曝光", vm.params.dateImprintEv, -2f..8f, "%+.1f") { vm.params.dateImprintEv = it; vm.scheduleRender() }
        OutlinedTextField(value = vm.params.dateImprintText, onValueChange = { vm.params.dateImprintText = it; vm.scheduleRender() },
            label = { Text("日期文字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = vm.params.dateImprintDataText, onValueChange = { vm.params.dateImprintDataText = it; vm.scheduleRender() },
            label = { Text("拍摄数据（打印在片边）") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GradePanel(vm: EditorViewModel) {
    SectionLabel("白平衡")
    SliderRow("色温(暖/冷)", vm.params.temperature, -1f..1f, "%+.2f") { vm.params.temperature = it; vm.scheduleGrade() }
    SliderRow("色调(绿/品红)", vm.params.tint, -1f..1f, "%+.2f") { vm.params.tint = it; vm.scheduleGrade() }

    SectionLabel("影调")
    SliderRow("曝光", vm.params.gradeExposure, -5f..5f, "%+.2f EV") { vm.params.gradeExposure = it; vm.scheduleGrade() }
    SliderRow("对比度", vm.params.contrast, -100f..100f, "%+.0f") { vm.params.contrast = it; vm.scheduleGrade() }
    SliderRow("亮度", vm.params.brightness, -100f..100f, "%+.0f") { vm.params.brightness = it; vm.scheduleGrade() }
    SliderRow("高光", vm.params.highlights, -100f..100f, "%+.0f") { vm.params.highlights = it; vm.scheduleGrade() }
    SliderRow("阴影", vm.params.shadows, -100f..100f, "%+.0f") { vm.params.shadows = it; vm.scheduleGrade() }
    SliderRow("饱和度", vm.params.saturation, -100f..100f, "%+.0f") { vm.params.saturation = it; vm.scheduleGrade() }
    SliderRow("黑点", vm.params.blackPoint, -100f..100f, "%+.0f") { vm.params.blackPoint = it; vm.scheduleGrade() }
    SliderRow("白点", vm.params.whitePoint, -100f..100f, "%+.0f") { vm.params.whitePoint = it; vm.scheduleGrade() }

    SectionLabel("色彩平衡")
    SliderRow("阴影 色温", vm.params.cbShadowTemp, -100f..100f, "%+.0f") { vm.params.cbShadowTemp = it; vm.scheduleGrade() }
    SliderRow("阴影 色调", vm.params.cbShadowTint, -100f..100f, "%+.0f") { vm.params.cbShadowTint = it; vm.scheduleGrade() }
    SliderRow("中间调 色温", vm.params.cbMidTemp, -100f..100f, "%+.0f") { vm.params.cbMidTemp = it; vm.scheduleGrade() }
    SliderRow("中间调 色调", vm.params.cbMidTint, -100f..100f, "%+.0f") { vm.params.cbMidTint = it; vm.scheduleGrade() }
    SliderRow("高光 色温", vm.params.cbHighTemp, -100f..100f, "%+.0f") { vm.params.cbHighTemp = it; vm.scheduleGrade() }
    SliderRow("高光 色调", vm.params.cbHighTint, -100f..100f, "%+.0f") { vm.params.cbHighTint = it; vm.scheduleGrade() }

    SectionLabel("几何")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { vm.params.quarterTurns = (vm.params.quarterTurns + 3) % 4; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.RotateLeft, contentDescription = "左转90度")
        }
        IconButton(onClick = { vm.params.quarterTurns = (vm.params.quarterTurns + 1) % 4; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.RotateRight, contentDescription = "右转90度")
        }
        IconToggleButton(checked = vm.params.flipH, onCheckedChange = { vm.params.flipH = it; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = "水平翻转")
        }
        IconToggleButton(checked = vm.params.flipV, onCheckedChange = { vm.params.flipV = it; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.SwapVert, contentDescription = "垂直翻转")
        }
    }
    SliderRow("旋转/拉直", vm.params.rotationDeg, -45f..45f, "%+.1f°") { vm.params.rotationDeg = it; vm.scheduleRender() }
}

@Composable
private fun ExportPanel(vm: EditorViewModel, onSaveRecipe: () -> Unit) {
    SectionLabel("配方")
    val currentRecipe = vm.recipes.firstOrNull { it.format == vm.exportFormat && it.res == vm.exportRes && it.colorSpace == vm.exportColorSpace && it.jpegQuality == vm.jpegQuality }
    OptionsDropdown("配方", vm.recipes.map { it.name }, currentRecipe?.name ?: "（自定义）", Modifier.fillMaxWidth()) { name ->
        vm.recipes.firstOrNull { it.name == name }?.let { vm.applyRecipe(it) }
    }
    ActionButton("保存当前为配方", onClick = onSaveRecipe)

    SectionLabel("设置")
    Row {
        OptionsDropdown("格式", listOf("JPEG", "PNG", "TIFF"), vm.exportFormat, Modifier.weight(1f)) { vm.exportFormat = it }
        OptionsDropdown(
            "分辨率",
            listOf("2048", "2560", "3840", "原始"),
            when (vm.exportRes) { 2048 -> "2048"; 3840 -> "3840"; 0 -> "原始"; else -> "2560" },
            Modifier.weight(1f)
        ) { label ->
            vm.exportRes = when (label) { "2048" -> 2048; "3840" -> 3840; "原始" -> 0; else -> 2560 }
        }
    }
    OptionsDropdown("色彩空间(TIFF)", listOf("sRGB", "Display P3"), vm.exportColorSpace, Modifier.fillMaxWidth()) { vm.exportColorSpace = it }
    if (vm.exportFormat == "JPEG") {
        SliderRow("JPEG 质量", vm.jpegQuality.toFloat(), 50f..100f, "%.0f") { vm.jpegQuality = it.roundToInt() }
    }

    SectionLabel("动作")
    ActionButton(if (vm.isExporting) "导出中…" else "导出", enabled = vm.hasPhoto && !vm.isExporting,
        primary = true, onClick = { vm.export() })
    ActionButton(
        if (vm.isBatchExporting) "批量导出中…" else "批量导出 (${vm.photos.size} 张)",
        enabled = vm.photos.size > 1 && !vm.isBatchExporting && !vm.isExporting,
        onClick = { vm.batchExport() }
    )
    ActionButton("同步当前设置到其他照片", enabled = vm.photos.size > 1 && vm.currentUri != null && !vm.isBatchExporting,
        onClick = { vm.syncSettingsToOtherPhotos() })
    ActionButton("导出 DI (16-bit TIFF)", enabled = vm.hasPhoto && !vm.isExporting, onClick = { vm.exportDi() })
    ActionButton("软打样 (预览导出)", enabled = vm.hasPhoto && !vm.isExporting, onClick = { vm.softProof() })
    ActionButton("高清预览 (3400px)", enabled = vm.hasPhoto && !vm.isExporting, onClick = { vm.previewTier() })
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
