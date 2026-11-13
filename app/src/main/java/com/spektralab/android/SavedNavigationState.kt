package com.spektralab.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.ui.graphics.vector.ImageVector

/** Keep enum class identities stable for existing Compose saved navigation state. */
internal enum class AppRoute { Home, Editor, FilmStocks, About, ViewingStand, SavedPhotos }

internal enum class EditorTab(val label: String, val icon: ImageVector) {
    Shoot("拍摄", Icons.Rounded.CameraAlt),
    Print("放印", Icons.Rounded.CropFree),
    Grade("调色", Icons.Rounded.Tune),
    Export("导出", Icons.Rounded.Upload),
    Gallery("观景台", Icons.Rounded.PhotoLibrary),
}

internal enum class FilmFilter(val label: String) {
    All("全部"), Negative("彩色负片"), Positive("正片"), Monochrome("黑白"), Cinema("电影胶片")
}
