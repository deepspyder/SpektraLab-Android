package com.spektralab.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

internal data class ViewingPhoto(
    val key: String,
    val thumbnail: Bitmap?,
    val detail: Bitmap?,
    val name: String,
    val caption: String = "",
    val error: String? = null,
    val wallDetail: Bitmap? = null,
    val canRefine: Boolean = false,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val sourceFormat: String = "",
)

private data class ViewingMetadata(val name: String, val caption: String,
    val width: Int, val height: Int, val format: String)

/** One contact sheet, shared by session photos and independent imports. */
@Composable
internal fun GalleryScreen(
    vm: EditorViewModel,
    onImport: () -> Unit,
    onBackToDarkroom: () -> Unit,
    onRemovePhoto: (Uri) -> Unit,
    onEditPhoto: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    canvasState: FilmCanvasState? = null,
    backDescription: String = "返回暗房",
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keys = remember(vm.photos, vm.viewingPhotos) {
        (vm.photos.map(Uri::toString) + vm.viewingPhotos.map { it.first }).distinct()
    }
    val independent = remember(vm.viewingPhotos) { vm.viewingPhotos.toMap() }
    val metadata by produceState(emptyMap<String, ViewingMetadata>(), keys) {
        val cached = value
        value = withContext(Dispatchers.IO) {
            keys.associateWith { cached[it] ?: readViewingMetadata(context, Uri.parse(it)) }
        }
    }
    val photos = keys.map { key ->
        val uri = Uri.parse(key)
        val developed = vm.developedPreviews[uri]
        val current = if (uri == vm.currentUri) vm.rendered ?: vm.sourceBitmap else null
        val thumb = developed ?: independent[key] ?: vm.thumbnails[uri]
        ViewingPhoto(
            key = key,
            thumbnail = thumb ?: current ?: vm.galleryPreviews[uri],
            detail = current ?: developed ?: vm.galleryPreviews[uri] ?: independent[key] ?: thumb,
            name = metadata[key]?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "照片",
            caption = metadata[key]?.caption.orEmpty(),
            error = vm.galleryPreviewErrors[uri],
            // A decoded original must never replace an already-developed edit.
            wallDetail = current ?: developed ?: vm.galleryPreviews[uri],
            canRefine = current == null && developed == null,
            sourceWidth = metadata[key]?.width ?: 0,
            sourceHeight = metadata[key]?.height ?: 0,
            sourceFormat = metadata[key]?.format.orEmpty(),
        )
    }
    ViewingStand(
        photos = photos,
        importing = vm.isImportingViewingPhotos,
        importCompleted = vm.viewingImportCompleted,
        importTotal = vm.viewingImportTotal,
        actionsEnabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
        onLoadThumbnail = { key ->
            if (key !in independent) vm.loadThumbnail(Uri.parse(key))
        },
        onLoadDetail = { vm.loadGalleryPreview(Uri.parse(it)) },
        onLoadWallDetails = { keys -> vm.loadGalleryWallDetails(keys.map(Uri::parse)) },
        onImport = onImport,
        onRemove = { onRemovePhoto(Uri.parse(it)) },
        onEdit = { onEditPhoto(Uri.parse(it)) },
        onBack = onBackToDarkroom,
        modifier = modifier,
        sharedCanvasState=canvasState,
        backDescription=backDescription,
    )
}

private fun readViewingMetadata(context: Context, uri: Uri): ViewingMetadata {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "照片"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0)
        }
    }
    val exif = runCatching {
        context.contentResolver.openInputStream(uri)?.use(::ExifInterface)
    }.getOrNull()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
    val rotated = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in setOf(5, 6, 7, 8)
    val width = (if (rotated) bounds.outHeight else bounds.outWidth).coerceAtLeast(0)
    val height = (if (rotated) bounds.outWidth else bounds.outHeight).coerceAtLeast(0)
    val format = name.substringAfterLast('.', "").uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
        ?: bounds.outMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty()
    val details = buildList {
        if (format.isNotBlank()) add(format)
        if (width > 0 && height > 0) add("$width × $height")
        exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 && it.isFinite() }
            ?.let { add(String.format(Locale.ROOT, "%.0f mm", it)) }
        exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 && it.isFinite() }
            ?.let { add(String.format(Locale.ROOT, "f/%.1f", it)) }
        exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 }
            ?.let { add("ISO $it") }
        (exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME))?.let { raw ->
            runCatching {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply { isLenient = false }.parse(raw)
            }.getOrNull()?.let { add(SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(it)) }
        }
    }
    return ViewingMetadata(name, details.joinToString(" · "), width, height, format)
}
