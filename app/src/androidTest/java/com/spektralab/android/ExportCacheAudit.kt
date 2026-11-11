package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.io.RandomAccessFile

/** Uses the real source decoder, engine, cache and MediaStore writer; no fake cache-hit path. */
internal class ExportCacheAudit(private val test: Instrumentation) {
    fun run(): String {
        val context = test.targetContext
        val cache = ExportRenderCache.get(context)
        val source = File(context.cacheDir, "cache-source-${System.nanoTime()}.jpg")
        val activity =
            test.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun media(): Set<Long> = buildSet {
            resolver
                .query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { while (it.moveToNext()) add(it.getLong(0)) }
        }
        val before = media()
        var lastSaved: Uri? = null
        fun writeSource(color: Int) {
            val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                source.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
        fun export(format: String = "TIFF"): ByteArray {
            val seen = media()
            test.runOnMainSync {
                vm.exportFormat = format
                vm.export()
            }
            await { !vm.isExporting && vm.renderTaskState.active == null }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val added = media() - seen
            check(added.size == 1) { "No single committed output" }
            val uri = android.content.ContentUris.withAppendedId(collection, added.single())
            lastSaved = uri
            return resolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        try {
            await { vm.engine != null && !vm.busy }
            val color = 0xff000000.toInt() or (System.nanoTime().toInt() and 0xffffff)
            writeSource(color)
            val uri = Uri.fromFile(source)
            val params =
                EditorParams().apply {
                    grainActive = false
                    glareActive = false
                }
            EditStore.save(context, uri, params)
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.errorMessage != null || vm.hasPhoto && !vm.busy }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            test.runOnMainSync {
                vm.exportFormat = "TIFF"
                vm.exportRes = 0
                vm.exportColorSpace = "sRGB"
                vm.exportKeepCapture = false
                vm.exportKeepLocation = false
            }
            val initial = vm.exportCacheStats
            val coldStarted = SystemClock.elapsedRealtime()
            val first = export()
            val coldMs = SystemClock.elapsedRealtime() - coldStarted
            check(vm.exportCacheStats.writes == initial.writes + 1) {
                "Deterministic output was not cached: ${vm.exportCacheStats}"
            }
            val warmStarted = SystemClock.elapsedRealtime()
            val second = export()
            val warmMs = SystemClock.elapsedRealtime() - warmStarted
            check(first.contentEquals(second)) { "A cache hit changed complete TIFF bytes" }
            check(vm.exportCacheStats.hits == initial.hits + 1) {
                "Second real export did not hit cache"
            }
            val png = export("PNG")
            val mediaSource = lastSaved ?: error("PNG has no MediaStore URI")
            val copy = File(context.cacheDir, "cache-media-copy-${System.nanoTime()}.png")
            try {
                copy.writeBytes(png)
                val choices =
                    ExportOptions("PNG", 0, "sRGB", 95, false, false, vm.lensCorrectionActive)
                val mediaIdentity = cache.prepare(mediaSource, params, choices) {}
                val fileIdentity = cache.prepare(Uri.fromFile(copy), params, choices) {}
                check(mediaIdentity != null && mediaIdentity == fileIdentity) {
                    "Seekable MediaStore/file identity mismatch: media=$mediaIdentity file=$fileIdentity"
                }
            } finally {
                copy.delete()
            }
            BitmapFactory.decodeByteArray(png, 0, png.size)?.let { bitmap ->
                try {
                    check(bitmap.width == 512 && bitmap.height == 384)
                } finally {
                    bitmap.recycle()
                }
            } ?: error("Cached PNG not decodable")
            val jpeg = export("JPEG")
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { bitmap ->
                try {
                    check(bitmap.width == 512 && bitmap.height == 384)
                } finally {
                    bitmap.recycle()
                }
            } ?: error("Cached JPEG not decodable")
            check(vm.exportCacheStats.hits == initial.hits + 3) {
                "Equivalent output space was not reused across containers"
            }

            val beforeBatch = vm.exportCacheStats
            val seenBatch = media()
            test.runOnMainSync {
                vm.exportFormat = "TIFF"
                vm.batchExport()
            }
            await { !vm.isBatchExporting && vm.renderTaskState.active == null }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            check(
                vm.exportCacheStats.hits == beforeBatch.hits + 1 &&
                    media().size == seenBatch.size + 1
            )

            // Storage survives reopening its owner; corrupt data must be a miss, then recomputed.
            val options =
                ExportOptions("TIFF", 0, "sRGB", 95, false, false, vm.lensCorrectionActive)
            val request =
                cache.prepare(uri, vm.params, options) {} ?: error("Missing cache identity")
            val file = File(context.cacheDir, "export-rgba16-v1/${request.key}.rgba16")
            check(file.isFile)
            RandomAccessFile(file, "rw").use {
                it.seek(100)
                val old = it.readByte()
                it.seek(100)
                it.writeByte(old.toInt() xor 127)
            }
            val afterCorrupt = export()
            check(afterCorrupt.contentEquals(first)) {
                "Corruption did not recover to the same pixels"
            }
            check(vm.exportCacheStats.writes == initial.writes + 2)

            val beforeParam = vm.exportCacheStats
            test.runOnMainSync {
                vm.params.gradeExposure = .5f
                vm.scheduleGrade()
            }
            val graded = export()
            check(
                !graded.contentEquals(first) && vm.exportCacheStats.misses == beforeParam.misses + 1
            )

            val beforeSource = vm.exportCacheStats
            writeSource(color xor 0x00ffffff)
            val changed = export()
            check(
                !changed.contentEquals(graded) &&
                    vm.exportCacheStats.misses == beforeSource.misses + 1
            )

            val beforeRandom = vm.exportCacheStats
            test.runOnMainSync { vm.params.grainActive = true }
            export()
            check(
                vm.exportCacheStats.bypasses == beforeRandom.bypasses + 1 &&
                    vm.exportCacheStats.hits == beforeRandom.hits
            )
            val bypass =
                cache.prepare(Uri.parse("content://cloud.example/photo"), params, options) {}
            check(bypass == null) { "A cloud source was fetched just to create cache identity" }
            check(vm.renderTaskState.let { it.active == null && it.waiting.isEmpty() })
            return "Real TIFF miss/write/hit exact; JPEG/PNG/batch reuse; corrupt cache recomputed; source/parameters invalidate; stochastic/cloud bypass; cold_ms=$coldMs warm_ms=$warmMs; stats=${vm.exportCacheStats}"
        } finally {
            (media() - before).forEach {
                resolver.delete(
                    android.content.ContentUris.withAppendedId(collection, it),
                    null,
                    null,
                )
            }
            source.delete()
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 90000) { "Cache export timed out" }
            SystemClock.sleep(20)
        }
    }
}
