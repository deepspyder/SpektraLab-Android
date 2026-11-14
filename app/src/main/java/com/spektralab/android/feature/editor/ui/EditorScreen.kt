package com.spektralab.android.feature.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.spektralab.android.EditorTab
import com.spektralab.android.feature.about.AboutScreen
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.crop.CropWorkspace
import com.spektralab.android.feature.export.ExportProgressBar
import com.spektralab.android.feature.export.ExportScreen
import com.spektralab.android.feature.lighttable.FilmCanvasState
import com.spektralab.android.feature.lighttable.GalleryScreen
import com.spektralab.android.feature.smartdevelop.SmartDevelopSheet
import com.spektralab.android.ui.components.DarkroomTopBar
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(
    vm: EditorViewModel,
    onBackHome: () -> Unit,
    onOpenFilmStocks: () -> Unit,
    onOpenPaperStocks: () -> Unit,
    viewingCanvasState: FilmCanvasState,
    homeStandEditRequest: Int,
) {
    var addToStage by rememberSaveable { mutableStateOf(false) }
    var smartDevelopVisible by rememberSaveable { mutableStateOf(false) }
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(EditorTab.Shoot) }
    var previousTab by rememberSaveable { mutableStateOf(EditorTab.Shoot) }
    var showAllShootControls by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(homeStandEditRequest) {
        if(homeStandEditRequest>0){selectedTab=EditorTab.Shoot;showAllShootControls=false}
    }
    var galleryReturnTab by rememberSaveable { mutableStateOf(EditorTab.Shoot) }
    var photoTrayVisible by rememberSaveable { mutableStateOf(false) }
    var exportHelpVisible by remember { mutableStateOf(false) }
    var adjustmentFullscreen by rememberSaveable { mutableStateOf(false) }
    val editorLayoutStates = rememberSaveableStateHolder()
    val fullParameters = selectedTab !in setOf(EditorTab.Export, EditorTab.Gallery) &&
        (selectedTab != EditorTab.Shoot || showAllShootControls)
    val panelStates = rememberSaveableStateHolder()
    BackHandler(enabled = adjustmentFullscreen || aboutVisible || vm.isCropping || photoTrayVisible || selectedTab in setOf(EditorTab.Export, EditorTab.Gallery) || fullParameters) {
        when {
            adjustmentFullscreen -> adjustmentFullscreen = false
            aboutVisible -> aboutVisible = false
            vm.isCropping -> vm.cancelCrop()
            photoTrayVisible -> photoTrayVisible = false
            selectedTab == EditorTab.Gallery -> selectedTab = galleryReturnTab
            selectedTab == EditorTab.Export -> selectedTab = previousTab
            else -> { selectedTab = EditorTab.Shoot; showAllShootControls = false }
        }
    }
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 24)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            vm.loadViewingPhotos(uris)
        }
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            vm.importPhotos(uris, addToSession = addToStage)
            photoTrayVisible = false
        }
        addToStage = false
    }

    var showSaveRecipeDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var editToolsMode by rememberSaveable { mutableStateOf<String?>(null) }
    var recipeName by remember { mutableStateOf("") }
    var comparing by rememberSaveable(vm.currentUri?.toString()) { mutableStateOf(false) }

    fun selectEditorTab(tab: EditorTab) {
        if (tab == EditorTab.Gallery) {
            if (selectedTab != EditorTab.Gallery) galleryReturnTab = selectedTab
        } else if (tab != EditorTab.Export) {
            previousTab = tab
        }
        selectedTab = tab
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A phone held sideways is wide but too short for all five rail destinations.
        val expanded = maxHeight >= 500.dp && (maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight))
        if (adjustmentFullscreen) {
            AdjustmentFullscreen(
                mode = selectedTab.label,
                fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "当前照片",
                comparing = comparing, canCompare = vm.sourceBitmap != null && vm.rendered != null,
                onCompare = { comparing = !comparing }, onClose = { adjustmentFullscreen = false },
            ) { previewModifier ->
                PhotoCanvas(vm, comparing, onImport = {}, modifier = previewModifier)
            }
        } else editorLayoutStates.SaveableStateProvider("editor") {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (selectedTab == EditorTab.Gallery) androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (!vm.isCropping && selectedTab != EditorTab.Gallery) Column {
                    DarkroomTopBar(
                        title = when { selectedTab == EditorTab.Export -> "导出照片"; fullParameters -> "暗房调整"
                            else -> vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "Theia" },
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
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.edit_copy_title)) },
                                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ContentCopy, null) },
                                enabled = vm.canTransferEdits, onClick = { showMoreMenu = false; editToolsMode = "copy" })
                            DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.edit_presets_title)) },
                                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Bookmarks, null) },
                                enabled = vm.canTransferEdits, onClick = { showMoreMenu = false; editToolsMode = "presets" })
                            DropdownMenuItem(text = { Text("关于") }, onClick = { showMoreMenu = false; aboutVisible = true })
                        }
                    }
                    if (selectedTab != EditorTab.Export && vm.photos.isNotEmpty()) EditorPhotoStrip(
                        vm = vm,
                        onAddPhotos = {
                            addToStage = true
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onManagePhotos = { photoTrayVisible = true },
                        onSmartDevelop = { smartDevelopVisible = true },
                    )
                }
            },
            bottomBar = {
                if (!expanded && !vm.isCropping && selectedTab != EditorTab.Gallery) {
                    EditorNavigationBar(selectedTab, !vm.busy && !vm.isExporting && !vm.isBatchExporting, ::selectEditorTab)
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
                } else AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                            slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 24 }) togetherWith
                            (fadeOut(tween(165, easing = FastOutSlowInEasing)) +
                                slideOutHorizontally(tween(190, easing = FastOutSlowInEasing)) { -it / 32 })
                    },
                    label = "editor-destination-transition",
                ) { destination ->
                    when {
                    destination == EditorTab.Export -> ExportScreen(vm, Modifier.fillMaxSize(), onCopySettings = { editToolsMode = "copy" }, onSaveRecipe = { showSaveRecipeDialog = true })
                    destination == EditorTab.Gallery -> {
                        GalleryScreen(
                            vm = vm,
                            onImport = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onBackToDarkroom = { selectedTab = galleryReturnTab },
                            onRemovePhoto = { uri ->
                                if (vm.viewingPhotos.any { it.first == uri.toString() }) vm.removeViewingPhoto(uri.toString())
                                if (uri in vm.photos) vm.removePhotoFromSession(uri)
                            },
                            onEditPhoto = { uri ->
                                val index = vm.photos.indexOf(uri)
                                if (index >= 0) vm.selectPhoto(index) else vm.addPhotos(listOf(uri), openSelected = true)
                                selectedTab = EditorTab.Shoot
                                showAllShootControls = false
                            },
                            modifier = Modifier.fillMaxSize(),
                            canvasState=viewingCanvasState,
                        )
                    }
                    expanded -> Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorNavigationRail(destination, ::selectEditorTab)
                        PhotoCanvas(vm, comparing, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(parameterWidth).fillMaxHeight()) {
                            PhotoMetadataStrip(vm)
                            EditorStockSelection(vm, onOpenFilmStocks, onOpenPaperStocks)
                            EditorPanel(vm, destination, Modifier.weight(1f).fillMaxWidth(), panelStates, onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    landscape -> Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoCanvas(vm, comparing, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1.08f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (destination != EditorTab.Grade) {
                                EditorStockSelection(vm, onOpenFilmStocks, onOpenPaperStocks)
                            }
                            EditorPanel(vm, destination, Modifier.weight(1f).fillMaxWidth(), panelStates,
                                showHeader = false, onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    destination == EditorTab.Shoot && !showAllShootControls -> Column(Modifier.fillMaxSize()) {
                        PhotoCanvas(vm, comparing,
                            onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f).fillMaxWidth())
                        PhotoMetadataStrip(vm)
                        EditorStockSelection(vm, onOpenFilmStocks, onOpenPaperStocks)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("拍摄参数", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { showAllShootControls = true }) { Text("全部调整", fontSize = 11.sp) }
                            }
                            SliderRow("曝光补偿", vm.params.exposureEv, -8f..8f, "%+.1f EV", compact = true) {
                                vm.params.exposureEv = it; vm.scheduleRender()
                            }
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("按住照片查看原图", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (vm.dirty) "修改已应用于当前照片" else "原片始终保留", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    else -> AdjustmentWorkspace(
                        mode = destination.label,
                        fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "当前照片",
                        comparing = comparing, canCompare = vm.sourceBitmap != null && vm.rendered != null,
                        onCompare = { comparing = !comparing },
                        onFullScreen = { adjustmentFullscreen = true },
                        onDone = { selectedTab = EditorTab.Shoot; showAllShootControls = false },
                        preview = { previewModifier ->
                            PhotoCanvas(vm, comparing,
                                onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = previewModifier)
                        },
                    ) {
                        panelStates.SaveableStateProvider(destination) {
                            ParameterStockCaption(vm, onOpenFilmStocks)
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                                EditorParameterContent(vm, destination)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
                }
            }
        }
        }

        if(smartDevelopVisible)SmartDevelopSheet(vm,onDismiss={smartDevelopVisible=false})
        if (photoTrayVisible) {
            ModalBottomSheet(
                onDismissRequest = { photoTrayVisible = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = TheiaShapes.sheet,
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

        if ((vm.busy || vm.isExporting || vm.isBatchExporting) && selectedTab !in setOf(EditorTab.Export, EditorTab.Gallery)) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .zIndex(2f),
                contentAlignment = Alignment.Center
            ) {
                        Surface(shape = TheiaShapes.panel, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
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
                                enabled = vm.renderStage != "正在取消…" && (pct ?: 0f) < 100f &&
                                    !vm.isExportCommitCompleted,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(when {
                                    vm.renderStage == "正在取消…" -> "正在取消…"
                                    vm.isExporting && !vm.isBatchExporting -> "取消处理"
                                    else -> "取消导出"
                                })
                            }
                        }
                    }
                }
            }
        }

    }

    editToolsMode?.let { mode -> EditToolsSheet(vm, presets = mode == "presets", onDismiss = { editToolsMode = null }) }

    if (exportHelpVisible) {
        AlertDialog(onDismissRequest = { exportHelpVisible = false }, title = { Text("导出帮助") },
            text = { Text("JPEG / PNG 使用 sRGB。TIFF 保留 16 位输出，并可选择 Display P3。\n\n原始尺寸会从原文件重新解码导出；DI、软打样和高清预览沿用各自的输出流程。导出文件保存到系统相册。\n\n大尺寸照片建议：如果照片像素较大，尽量避免使用「原始尺寸」全尺寸导出。建议优先选择长边 2048、2560 或 3840 px，以减少内存占用和等待时间；确有需要时再尝试全尺寸导出。",
                modifier = Modifier.verticalScroll(rememberScrollState())) },
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
