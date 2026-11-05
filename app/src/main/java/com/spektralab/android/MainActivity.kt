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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
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
import androidx.compose.ui.text.style.TextOverflow
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
                    SpektraLabRoot(vm)
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
    Shoot("拍摄", Icons.Outlined.CameraAlt),
    Print("放印", Icons.Outlined.CropFree),
    Grade("调色", Icons.Outlined.Tune),
    Export("导出", Icons.Outlined.Upload)
}

private enum class AppRoute { Home, Editor, FilmStocks, About }

@Composable
private fun SpektraLabRoot(vm: EditorViewModel) {
    var route by rememberSaveable { mutableStateOf(AppRoute.Home) }
    var addToHomeSession by rememberSaveable { mutableStateOf(false) }
    var openStandOnEditor by rememberSaveable { mutableStateOf(false) }
    val screenStates = rememberSaveableStateHolder()
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            if (addToHomeSession) vm.addPhotos(uris, openSelected = true) else vm.openPhotos(uris)
            route = AppRoute.Editor
        }
        addToHomeSession = false
    }

    fun launchPhotoPicker(add: Boolean) {
        addToHomeSession = add
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    BackHandler(enabled = route != AppRoute.Home) {
        route = when (route) {
            AppRoute.Home -> AppRoute.Home
            AppRoute.Editor, AppRoute.About -> AppRoute.Home
            AppRoute.FilmStocks -> AppRoute.Editor
        }
    }

    Box(Modifier.fillMaxSize()) {
        screenStates.SaveableStateProvider(route) {
          when (route) {
            AppRoute.Home -> HomeScreen(
                vm = vm,
                onImportPhotos = { launchPhotoPicker(add = false) },
                onAddPhotos = { launchPhotoPicker(add = true) },
                onOpenEditor = { route = AppRoute.Editor },
                onOpenTestImage = { vm.openTestImage(); route = AppRoute.Editor },
                onOpenViewingStand = { openStandOnEditor = true; route = AppRoute.Editor },
                onOpenAbout = { route = AppRoute.About },
            )
            AppRoute.Editor -> EditorScreen(
                vm = vm,
                onBackHome = { route = AppRoute.Home },
                onOpenFilmStocks = { route = AppRoute.FilmStocks },
                initialViewingStand = openStandOnEditor,
                onViewingStandOpened = { openStandOnEditor = false },
            )
            AppRoute.FilmStocks -> FilmStockScreen(vm, onBack = { route = AppRoute.Editor })
            AppRoute.About -> AboutScreen(onBack = { route = AppRoute.Home }, modifier = Modifier.fillMaxSize())
          }
        }

        vm.errorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { vm.dismissError() },
                title = { Text("出错了") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { vm.dismissError() }) { Text("确定") } },
            )
        }
    }
    BackHandler(enabled = vm.busy || vm.isExporting || vm.isBatchExporting) { }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    vm: EditorViewModel,
    onBackHome: () -> Unit,
    onOpenFilmStocks: () -> Unit,
    initialViewingStand: Boolean,
    onViewingStandOpened: () -> Unit,
) {
    var addToStage by rememberSaveable { mutableStateOf(false) }
    var viewingStandVisible by rememberSaveable { mutableStateOf(initialViewingStand) }
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    var viewingLightOn by rememberSaveable { mutableStateOf(true) }
    var viewingSelectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(EditorTab.Shoot) }
    var previousTab by rememberSaveable { mutableStateOf(EditorTab.Shoot) }
    var showAllShootControls by rememberSaveable { mutableStateOf(false) }
    var photoTrayVisible by rememberSaveable { mutableStateOf(false) }
    var exportHelpVisible by remember { mutableStateOf(false) }
    var adjustmentFullscreen by rememberSaveable { mutableStateOf(false) }
    val editorLayoutStates = rememberSaveableStateHolder()
    val fullParameters = selectedTab != EditorTab.Export && (selectedTab != EditorTab.Shoot || showAllShootControls)
    val panelStates = rememberSaveableStateHolder()
    val viewingPhotos = vm.viewingPhotos

    LaunchedEffect(initialViewingStand) {
        if (initialViewingStand) {
            viewingSelectedKey = viewingPhotos.firstOrNull()?.first
            viewingStandVisible = true
            onViewingStandOpened()
        }
    }

    BackHandler(enabled = adjustmentFullscreen || aboutVisible || vm.isCropping || viewingStandVisible || photoTrayVisible || selectedTab == EditorTab.Export || fullParameters) {
        when {
            adjustmentFullscreen -> adjustmentFullscreen = false
            aboutVisible -> aboutVisible = false
            vm.isCropping -> vm.cancelCrop()
            viewingStandVisible -> viewingStandVisible = false
            photoTrayVisible -> photoTrayVisible = false
            selectedTab == EditorTab.Export -> selectedTab = previousTab
            else -> { selectedTab = EditorTab.Shoot; showAllShootControls = false }
        }
    }
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
            if (addToStage) vm.addPhotos(uris, openSelected = true) else vm.openPhotos(uris)
            photoTrayVisible = false
        }
        addToStage = false
    }

    var showSaveRecipeDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var recipeName by remember { mutableStateOf("") }
    var comparing by rememberSaveable(vm.currentUri?.toString()) { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight)
        if (adjustmentFullscreen) {
            AdjustmentFullscreen(
                mode = selectedTab.label,
                fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "当前照片",
                comparing = comparing, canCompare = vm.sourceBitmap != null && vm.rendered != null,
                onCompare = { comparing = !comparing }, onClose = { adjustmentFullscreen = false },
            ) { previewModifier ->
                PhotoCanvas(vm, comparing, onImport = {}, modifier = previewModifier, showSessionBadge = false)
            }
        } else editorLayoutStates.SaveableStateProvider("editor") {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (!vm.isCropping) DarkroomTopBar(
                    title = when { selectedTab == EditorTab.Export -> "导出照片"; fullParameters -> "暗房调整"
                        else -> vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "SpektraLab" },
                    parameterView = fullParameters, exportView = selectedTab == EditorTab.Export,
                    enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                    canUndo = vm.canUndo, canRedo = vm.canRedo,
                    canCompare = vm.sourceBitmap != null && vm.rendered != null, comparing = comparing,
                    onBack = { when { selectedTab == EditorTab.Export -> selectedTab = previousTab
                        fullParameters -> { selectedTab = EditorTab.Shoot; showAllShootControls = false }
                        else -> onBackHome() } },
                    onMenu = { showMoreMenu = true }, onUndo = vm::undo, onRedo = vm::redo,
                    onCompare = { comparing = !comparing }, onReset = vm::resetAll,
                    onHelp = { exportHelpVisible = true },
                ) {
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(text = { Text("导入照片") }, onClick = {
                            showMoreMenu = false; addToStage = false
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("照片工作台 (${vm.photos.size})") }, enabled = vm.photos.isNotEmpty(),
                            onClick = { showMoreMenu = false; photoTrayVisible = true })
                        DropdownMenuItem(text = { Text("裁剪照片") }, enabled = vm.hasPhoto && !vm.params.overscanActive,
                            onClick = { showMoreMenu = false; vm.beginCrop() })
                        DropdownMenuItem(text = { Text("重置") }, enabled = vm.hasPhoto,
                            onClick = { showMoreMenu = false; vm.resetAll() })
                        DropdownMenuItem(text = { Text("测试图") }, onClick = { showMoreMenu = false; vm.openTestImage() })
                        DropdownMenuItem(text = { Text("观景台") }, onClick = { showMoreMenu = false; openViewingStand() })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(text = { Text("关于") }, onClick = { showMoreMenu = false; aboutVisible = true })
                    }
                }
            },
            bottomBar = {
                if (!expanded && !vm.isCropping) EditorNavigationBar(selectedTab, !vm.busy && !vm.isExporting && !vm.isBatchExporting) { tab ->
                    if (tab != EditorTab.Export) previousTab = tab
                    selectedTab = tab
                }
            }
        ) { contentPadding ->
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(contentPadding).imePadding()
            ) {
                val landscape = maxWidth > maxHeight
                val parameterWidth = (maxWidth * .44f).coerceIn(280.dp, 440.dp)
                if (vm.isCropping && vm.sourceBitmap != null) {
                    CropWorkspace(vm, Modifier.fillMaxSize())
                } else when {
                    selectedTab == EditorTab.Export -> ExportScreen(vm, Modifier.fillMaxSize(), onSaveRecipe = { showSaveRecipeDialog = true })
                    expanded -> Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorNavigationRail(selectedTab) { tab ->
                            if (tab != EditorTab.Export) previousTab = tab
                            selectedTab = tab
                        }
                        PhotoCanvas(vm, comparing, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(), onOpenPhotos = { photoTrayVisible = true })
                        Column(Modifier.width(parameterWidth).fillMaxHeight()) {
                            PhotoMetadataStrip(vm)
                            EditorStockSelection(vm, onOpenFilmStocks)
                            EditorPanel(vm, selectedTab, Modifier.weight(1f).fillMaxWidth(), panelStates, onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    landscape -> Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoCanvas(vm, comparing, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1.08f).fillMaxHeight(), onOpenPhotos = { photoTrayVisible = true })
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            PhotoMetadataStrip(vm)
                            EditorStockSelection(vm, onOpenFilmStocks)
                            EditorPanel(vm, selectedTab, Modifier.weight(1f).fillMaxWidth(), panelStates, onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    selectedTab == EditorTab.Shoot && !showAllShootControls -> Column(Modifier.fillMaxSize()) {
                        PhotoCanvas(vm, comparing,
                            onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f).fillMaxWidth(), onOpenPhotos = { photoTrayVisible = true })
                        PhotoMetadataStrip(vm)
                        EditorStockSelection(vm, onOpenFilmStocks)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("拍摄参数", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { showAllShootControls = true }) { Text("全部调整", fontSize = 11.sp) }
                            }
                            SliderRow("曝光补偿", vm.params.exposureEv, -8f..8f, "%+.1f EV", compact = true) {
                                vm.params.exposureEv = it; vm.scheduleRender()
                            }
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("点击顶部按钮对比原片", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (vm.dirty) "修改已应用于当前照片" else "原片始终保留", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    else -> AdjustmentWorkspace(
                        mode = selectedTab.label,
                        fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "当前照片",
                        comparing = comparing, canCompare = vm.sourceBitmap != null && vm.rendered != null,
                        onCompare = { comparing = !comparing },
                        onFullScreen = { adjustmentFullscreen = true },
                        onDone = { selectedTab = EditorTab.Shoot; showAllShootControls = false },
                        preview = { previewModifier ->
                            PhotoCanvas(vm, comparing,
                                onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = previewModifier,
                                onOpenPhotos = { photoTrayVisible = true })
                        },
                    ) {
                        panelStates.SaveableStateProvider(selectedTab) {
                            ParameterStockCaption(vm, onOpenFilmStocks)
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                                EditorParameterContent(vm, selectedTab)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
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

        if (photoTrayVisible) {
            ModalBottomSheet(
                onDismissRequest = { photoTrayVisible = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                dragHandle = {
                    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                    }
                },
            ) {
                PhotoStage(
                    vm = vm,
                    onAddPhotos = {
                        addToStage = true
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onClose = { photoTrayVisible = false },
                )
            }
        }

        if (aboutVisible) {
            AboutScreen(onBack = { aboutVisible = false }, modifier = Modifier.fillMaxSize().zIndex(5f))
        }

        if ((vm.busy || vm.isExporting || vm.isBatchExporting) && !(selectedTab == EditorTab.Export && (vm.isExporting || vm.isBatchExporting))) {
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

    if (exportHelpVisible) {
        AlertDialog(onDismissRequest = { exportHelpVisible = false }, title = { Text("导出帮助") },
            text = { Text("JPEG / PNG 使用 sRGB。TIFF 保留 16 位输出，并可选择 Display P3。\n\n原始尺寸会从原文件重新解码导出；DI、软打样和高清预览沿用各自的输出流程。导出文件保存到系统相册。") },
            confirmButton = { TextButton(onClick = { exportHelpVisible = false }) { Text("关闭") } })
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

}

@Composable
private fun EditorNavigationBar(selected: EditorTab, enabled: Boolean, onSelect: (EditorTab) -> Unit) {
    DarkroomBottomBar(EditorTab.entries.map { it.label }, EditorTab.entries.map { it.icon }, selected.ordinal, enabled) {
        onSelect(EditorTab.entries[it])
    }
}

@Composable
internal fun ExportProgressBar(value: Float, color: Color, track: Color) {
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
private fun PhotoMetadataStrip(vm: EditorViewModel) {
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
private fun ParameterStockCaption(vm: EditorViewModel, onBrowse: () -> Unit) {
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
private fun EditorStockSelection(vm: EditorViewModel, onBrowseFilms: () -> Unit) {
    val film = vm.films.firstOrNull { it.id == vm.params.filmId }
    val papers = vm.papers.filter { StockCatalog.canPrint(vm.params.filmId, it.id) }
    val paper = papers.firstOrNull { it.id == vm.params.paperId }
    var papersVisible by remember { mutableStateOf(false) }
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
                Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(14.dp))
            }
        }
        Box(Modifier.width(1.dp).height(48.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)))
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { papersVisible = true }
                .semantics { contentDescription = "相纸" }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("相纸", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(paper?.name ?: "选择相纸", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = papersVisible, onDismissRequest = { papersVisible = false }) {
                papers.forEach { stock -> DropdownMenuItem(text = { Text(stock.name) }, onClick = {
                    vm.selectPaper(stock.id); papersVisible = false
                }) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PhotoCanvas(
    vm: EditorViewModel,
    comparing: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    showSessionBadge: Boolean = true,
    onOpenPhotos: () -> Unit = {},
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
                Icon(Icons.Filled.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
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
                        .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(4.dp))
                        .padding(4.dp))
                }
            }
        }
        if (showSessionBadge && vm.photos.isNotEmpty() && !vm.isCropping) {
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)
                .heightIn(min = 48.dp).clickable(onClick = onOpenPhotos)
                .semantics { contentDescription = "${vm.currentIndex + 1} / ${vm.photos.size}" }, contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .7f), shape = RoundedCornerShape(3.dp)) {
                    Text("${vm.currentIndex + 1} / ${vm.photos.size}  ·  适合屏幕",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorPanel(
    vm: EditorViewModel,
    selectedTab: EditorTab,
    modifier: Modifier = Modifier,
    stateHolder: SaveableStateHolder,
    showHeader: Boolean = true,
    onSaveRecipe: () -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        Column {
            if (showHeader) Row(
                Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedTab.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                if (vm.dirty) Text("已修改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
            stateHolder.SaveableStateProvider(selectedTab) {
              Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                EditorParameterContent(vm, selectedTab)
                Spacer(Modifier.height(12.dp))
              }
            }
        }
    }
}

@Composable
private fun EditorParameterContent(vm: EditorViewModel, selectedTab: EditorTab) {
    when (selectedTab) {
        EditorTab.Shoot -> ShootPanel(vm)
        EditorTab.Print -> PrintPanel(vm)
        EditorTab.Grade -> GradePanel(vm)
        EditorTab.Export -> Unit
    }
}

// ============================================================
//  四个 Tab 面板
// ============================================================

@Composable
private fun ShootPanel(vm: EditorViewModel) {
    EditorSection("画幅与配光", "胶片物理画幅 · 自动配光", initiallyExpanded = false) {
        OptionsDropdown(
            "胶片画幅",
            FILM_FORMAT_MM.keys.toList(),
            FILM_FORMAT_MM.entries.firstOrNull { it.value == vm.params.filmFormatMm }?.key ?: "135 (36mm)",
            Modifier.fillMaxWidth()
        ) { label ->
            FILM_FORMAT_MM[label]?.let { vm.params.filmFormatMm = it }
            vm.scheduleRender()
        }

        ActionButton("自动配光", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.solve() })
    }

    EditorSection("曝光与测光", "曝光补偿 · 自动测光 · 测光方式", initiallyExpanded = true) {
        SliderRow("曝光补偿", vm.params.exposureEv, -8f..8f, "%+.1f EV") { vm.params.exposureEv = it; vm.scheduleRender() }
        OptionsDropdown("测光方法", listOf("balanced", "center", "protect_highlights", "protect_shadows"), vm.params.autoExposureMethod, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
            vm.params.autoExposureMethod = it; vm.scheduleRender()
        }
        ToggleRow("自动测光", vm.params.autoExposure) { vm.params.autoExposure = it; vm.scheduleRender() }
    }

    EditorSection("场景宽容度", "测量场景 · 应用场景置位", initiallyExpanded = false) {
        ActionButton("测量宽容度", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.measureLatitude() })
        vm.latitudeReadout?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (vm.latitudeCanApply) {
            ActionButton("应用场景置位", onClick = { vm.applyLatitude() })
        }
    }

    if (vm.currentIsRaw) {
        EditorSection("相机白平衡 (RAW)", "相机色温 · 镜头校正") {
        ToggleRow("镜头校正", vm.lensCorrectionActive && vm.matchedLens != null) { vm.toggleLensCorrection() }
        vm.matchedLens?.let { Text("已匹配镜头: $it", style = MaterialTheme.typography.bodySmall) }
            ?: Text("未找到镜头配置，保留原始画面", style = MaterialTheme.typography.bodySmall)
        ToggleRow("自定义白平衡", vm.params.cameraWbCustom) { vm.params.cameraWbCustom = it; vm.scheduleCameraWb() }
        if (vm.params.cameraWbCustom) {
            SliderRow("色温 K", vm.params.cameraWbKelvin, 2000f..12000f, "%.0f K") { vm.params.cameraWbKelvin = it; vm.scheduleCameraWb() }
            SliderRow("色调", vm.params.cameraWbTint, -150f..150f, "%+.0f") { vm.params.cameraWbTint = it; vm.scheduleCameraWb() }
            ActionButton("恢复拍摄白平衡", onClick = { vm.resetCameraWb() })
        }
        }
    }

    EditorSection("镜头滤镜", "Wratten 彩色滤镜", initiallyExpanded = false) {
        OptionsDropdown("镜头滤镜", listOf("无", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58"), vm.params.cameraFilter.ifEmpty { "无" }, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
            vm.params.cameraFilter = if (it == "无") "" else it; vm.scheduleRender()
        }
    }
}

@Composable
private fun PrintPanel(vm: EditorViewModel) {
    EditorSection("放大机", "打印曝光 · Y / M 滤色", initiallyExpanded = true) {
        SliderRow("打印曝光(越小越亮)", vm.params.printExposure, 0.25f..4f, "%.2f") { vm.params.printExposure = it; vm.scheduleRender() }
        SliderRow("滤镜 Y(黄/蓝)", vm.params.yFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f) { vm.params.yFilterShift = it; vm.scheduleRender() }
        SliderRow("滤镜 M(品红/绿)", vm.params.mFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f) { vm.params.mFilterShift = it; vm.scheduleRender() }
    }

    EditorSection("乳剂与暗房效应", "颗粒 · 光晕 · DIR · 纸面光晕 · 预闪 · EDR", initiallyExpanded = false) {
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
    }

    EditorSection("片边", "胶片格式 · 齿孔 · 雾化 · 漏光 · 帧号", initiallyExpanded = false) {
        ToggleRow("片边(整条胶片)", vm.params.overscanActive) { vm.params.overscanActive = it; vm.scheduleRender() }
        if (vm.params.overscanActive) {
            OptionsDropdown("画幅", listOf("135", "135_half", "135_xpan", "120_645", "120_6x6", "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"), vm.params.overscanFormat, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
                vm.params.overscanFormat = it; vm.scheduleRender()
            }
            OptionsDropdown("片边模式", listOf("strip", "filed"), vm.params.overscanMode, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanMode = it; vm.scheduleRender() }
            OptionsDropdown("门形状", listOf("auto", "square", "rounded", "eared", "shouldered", "kicked"), vm.params.overscanGate, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanGate = it; vm.scheduleRender() }
            OptionsDropdown("齿孔", listOf("white", "black"), vm.params.overscanHoles, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanHoles = it; vm.scheduleRender() }
            OptionsDropdown("边缘外", listOf("none", "black", "open"), vm.params.overscanCarrier, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanCarrier = it; vm.scheduleRender() }
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
    }

    EditorSection("日期后背", "日期文字 · 拍摄数据 · 位置 · 曝光", initiallyExpanded = false) {
        ToggleRow("日期后背", vm.params.dateImprintActive) {
            vm.params.dateImprintActive = it
            if (it && vm.params.dateImprintText.isEmpty()) vm.params.dateImprintText = vm.currentDateText()
            vm.scheduleRender()
        }
        if (vm.params.dateImprintActive) {
            OptionsDropdown("日期样式", listOf("lcd", "dots", "data"), vm.params.dateImprintStyle, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
                vm.params.dateImprintStyle = it
                if (it == "data") vm.params.overscanActive = true
                vm.scheduleRender()
            }
            OptionsDropdown("位置", listOf("frame", "rebate"), vm.params.dateImprintPlacement, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.dateImprintPlacement = it; vm.scheduleRender() }
            OptionsDropdown("角落", listOf("br", "bl", "tr", "tl"), vm.params.dateImprintCorner, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.dateImprintCorner = it; vm.scheduleRender() }
            SliderRow("大小", vm.params.dateImprintSize, 0.4f..3f, "%.2f") { vm.params.dateImprintSize = it; vm.scheduleRender() }
            SliderRow("曝光", vm.params.dateImprintEv, -2f..8f, "%+.1f") { vm.params.dateImprintEv = it; vm.scheduleRender() }
            OutlinedTextField(value = vm.params.dateImprintText, onValueChange = { vm.params.dateImprintText = it; vm.scheduleRender() },
                label = { Text("日期文字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = vm.params.dateImprintDataText, onValueChange = { vm.params.dateImprintDataText = it; vm.scheduleRender() },
                label = { Text("拍摄数据（打印在片边）") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GradePanel(vm: EditorViewModel) {
    EditorSection("白平衡", "扫描白平衡 · 暖冷与色调", initiallyExpanded = true) {
        SliderRow("色温(暖/冷)", vm.params.temperature, -1f..1f, "%+.2f") { vm.params.temperature = it; vm.scheduleGrade() }
        SliderRow("色调(绿/品红)", vm.params.tint, -1f..1f, "%+.2f") { vm.params.tint = it; vm.scheduleGrade() }
    }

    EditorSection("影调", "曝光 · 对比度 · 高光 · 阴影 · 黑白点", initiallyExpanded = true) {
        SliderRow("曝光", vm.params.gradeExposure, -5f..5f, "%+.2f EV") { vm.params.gradeExposure = it; vm.scheduleGrade() }
        SliderRow("对比度", vm.params.contrast, -100f..100f, "%+.0f") { vm.params.contrast = it; vm.scheduleGrade() }
        SliderRow("亮度", vm.params.brightness, -100f..100f, "%+.0f") { vm.params.brightness = it; vm.scheduleGrade() }
        SliderRow("高光", vm.params.highlights, -100f..100f, "%+.0f") { vm.params.highlights = it; vm.scheduleGrade() }
        SliderRow("阴影", vm.params.shadows, -100f..100f, "%+.0f") { vm.params.shadows = it; vm.scheduleGrade() }
        SliderRow("饱和度", vm.params.saturation, -100f..100f, "%+.0f") { vm.params.saturation = it; vm.scheduleGrade() }
        SliderRow("黑点", vm.params.blackPoint, -100f..100f, "%+.0f") { vm.params.blackPoint = it; vm.scheduleGrade() }
        SliderRow("白点", vm.params.whitePoint, -100f..100f, "%+.0f") { vm.params.whitePoint = it; vm.scheduleGrade() }
    }

    EditorSection("色彩平衡", "阴影 · 中间调 · 高光", initiallyExpanded = false) {
        SliderRow("阴影 色温", vm.params.cbShadowTemp, -100f..100f, "%+.0f") { vm.params.cbShadowTemp = it; vm.scheduleGrade() }
        SliderRow("阴影 色调", vm.params.cbShadowTint, -100f..100f, "%+.0f") { vm.params.cbShadowTint = it; vm.scheduleGrade() }
        SliderRow("中间调 色温", vm.params.cbMidTemp, -100f..100f, "%+.0f") { vm.params.cbMidTemp = it; vm.scheduleGrade() }
        SliderRow("中间调 色调", vm.params.cbMidTint, -100f..100f, "%+.0f") { vm.params.cbMidTint = it; vm.scheduleGrade() }
        SliderRow("高光 色温", vm.params.cbHighTemp, -100f..100f, "%+.0f") { vm.params.cbHighTemp = it; vm.scheduleGrade() }
        SliderRow("高光 色调", vm.params.cbHighTint, -100f..100f, "%+.0f") { vm.params.cbHighTint = it; vm.scheduleGrade() }
    }

    EditorSection("几何与裁剪", "旋转 · 翻转 · 拉直 · 自由裁剪", initiallyExpanded = false) {
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
        OutlinedButton(onClick = vm::beginCrop, enabled = vm.hasPhoto && !vm.busy && !vm.params.overscanActive,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("自由裁剪")
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
