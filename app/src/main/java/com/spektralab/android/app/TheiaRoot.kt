package com.spektralab.android.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.spektralab.android.AppRoute
import com.spektralab.android.feature.about.AboutScreen
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.EditorScreen
import com.spektralab.android.feature.home.HomeScreen
import com.spektralab.android.feature.library.FilmStockScreen
import com.spektralab.android.feature.lighttable.GalleryScreen
import com.spektralab.android.feature.lighttable.ViewingStand
import com.spektralab.android.feature.lighttable.rememberFilmCanvasState

@Composable
internal fun TheiaRoot(vm: EditorViewModel) {
    var route by rememberSaveable { mutableStateOf(AppRoute.Home) }
    var addToHomeSession by rememberSaveable { mutableStateOf(false) }
    var libraryStartsWithPaper by rememberSaveable { mutableStateOf(false) }
    var libraryVisit by rememberSaveable { mutableStateOf(0) }
    var homeStandEditRequest by rememberSaveable { mutableStateOf(0) }
    val screenStates = rememberSaveableStateHolder()
    val viewingCanvasState=rememberFilmCanvasState()
    val homeViewingPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems=24)
    ){uris ->
        if(uris.isNotEmpty()){
            uris.forEach(vm::persistReadPermission)
            vm.loadViewingPhotos(uris)
        }
    }
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
            AppRoute.Editor, AppRoute.About, AppRoute.ViewingStand -> AppRoute.Home
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
                onOpenViewingStand = { route = AppRoute.ViewingStand },
                onOpenTestImage = { vm.openTestImage(); route = AppRoute.Editor },
                onOpenAbout = { route = AppRoute.About },
            )
            AppRoute.Editor -> EditorScreen(
                vm = vm,
                onBackHome = { route = AppRoute.Home },
                onOpenFilmStocks = {
                    libraryStartsWithPaper = false; libraryVisit++; route = AppRoute.FilmStocks
                },
                onOpenPaperStocks = {
                    libraryStartsWithPaper = true; libraryVisit++; route = AppRoute.FilmStocks
                },
                viewingCanvasState=viewingCanvasState,
                homeStandEditRequest=homeStandEditRequest,
            )
            AppRoute.ViewingStand -> GalleryScreen(
                vm=vm,
                onImport={homeViewingPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                onBackToDarkroom={route=AppRoute.Home},
                onRemovePhoto={uri ->
                    if(vm.viewingPhotos.any{it.first==uri.toString()})vm.removeViewingPhoto(uri.toString())
                    if(uri in vm.photos)vm.removePhotoFromSession(uri)
                },
                onEditPhoto={uri ->
                    val index=vm.photos.indexOf(uri)
                    if(index>=0)vm.selectPhoto(index)else vm.addPhotos(listOf(uri),openSelected=true)
                    homeStandEditRequest++
                    route=AppRoute.Editor
                },
                modifier=Modifier.fillMaxSize(),canvasState=viewingCanvasState,backDescription="返回首页",
            )
            AppRoute.FilmStocks -> key(libraryVisit) {
                FilmStockScreen(vm, onBack = { route = AppRoute.Editor }, startWithPaper = libraryStartsWithPaper)
            }
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
